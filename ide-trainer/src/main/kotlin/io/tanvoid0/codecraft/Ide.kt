package io.tanvoid0.codecraft

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.ide.projectView.ProjectView
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The three things the curriculum panel asks the IDE to do. These used to be
 * a JSON protocol spoken over a JCEF bridge; now the panel is Swing and just
 * calls them.
 */
/** A lone backslash: the Windows path separator, and shell escaping. */
private const val BACKSLASH = "\\"

class Ide(private val project: Project) {

    /** The one terminal tab this plugin runs commands in, while it is open. */
    private var terminal: TerminalWidget? = null

    /**
     * The step's folder inside *this* project, or null when the project has no
     * such folder. A learner may open the repo root or a single service, so
     * "account-service" is a real subfolder in the first case and already the
     * project itself in the second - null then, and every caller falls back to
     * the root, which is the same directory either way.
     */
    private fun subdir(dir: String?): VirtualFile? = dir?.takeIf(String::isNotBlank)?.let { d ->
        project.guessProjectDir()?.let { root ->
            if (root.name.equals(d.trim('/').substringAfterLast('/'), ignoreCase = true)) null
            else root.findFileByRelativePath(d.trim('/'))?.takeIf { it.isDirectory }
        }
    }

    /**
     * Board labels are project-relative ("src/main/.../Wallet.java") but a
     * quarter of them are module-relative ("application/PlaceHold.java"), so
     * match by name and then by path suffix. Unique hit or nothing: an
     * ambiguous label opening the wrong file is worse than opening none.
     */
    fun resolve(rel: String, dir: String? = null): VirtualFile? {
        // A step's folder first, and only if it is really there: the learner
        // may have opened the service itself rather than the repo root, in
        // which case the step's "account-service/" is already where they are.
        subdir(dir)?.let { d ->
            d.findFileByRelativePath(rel.replace(BACKSLASH, "/").trimStart('/'))
                ?.takeIf { !it.isDirectory }
                ?.let { return it }
        }
        var clean = rel.replace('\\', '/').trimStart('/')
        // An absolute path inside the project is a path this project has; the
        // chat hands them over routinely (a pasted path, a model echoing one
        // back), and refusing them sent the model round in circles asking for
        // "the project-relative path" of a file it had already named.
        project.basePath?.replace('\\', '/')?.let { base ->
            val basePrefix = base.trimEnd('/') + "/"
            if (clean.startsWith(basePrefix, ignoreCase = true)) clean = clean.removeRange(0, basePrefix.length)
            else if (clean.equals(base.trimEnd('/'), ignoreCase = true)) clean = ""
        }
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath(if (clean.isEmpty()) base else "$base/$clean")
                ?.let { return it }
        }
        val name = clean.substringAfterLast('/')
        val hits = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            .filter { it.path.replace('\\', '/').endsWith("/$clean") }
        return hits.singleOrNull()
    }

    /**
     * `hint` is what to do about a file that is not there yet - the block's own
     * Create button, usually. Not being there is the ordinary case for a step
     * you have not reached, so this is a balloon rather than a status-bar line
     * a learner can miss and then wonder why Open did nothing.
     */
    fun openFile(rel: String, line: Int = 1, dir: String? = null, hint: String? = null) =
        ApplicationManager.getApplication().invokeLater {
        val vf = runReadAction { resolve(rel, dir) }
            ?: return@invokeLater notify(
                "No $rel yet",
                hint ?: "Nothing in this project is called that. Create it first.",
                NotificationType.INFORMATION,
            )
        OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    /**
     * A `file` block is a whole file, so the learner should not have to paste
     * it: create it under the project root with the body already in, and open
     * it. Never overwrites - a file that already exists is opened untouched,
     * because a mis-click on step 12 must not wipe what step 03 wrote.
     */
    fun createFile(rel: String, body: String, dir: String? = null): VirtualFile? {
        val clean = rel.replace('\\', '/').trimStart('/')
        val vf = runReadAction { resolve(clean, dir) } ?: runWriteAction {
            // Under the step's folder when the project has one, otherwise at
            // the root - which is the same place when the root is that module.
            val root = subdir(dir) ?: project.guessProjectDir() ?: return@runWriteAction null
            runCatching {
                VfsUtil.createDirectoryIfMissing(root, clean.substringBeforeLast('/', ""))
                    ?.findOrCreateChildData(this, clean.substringAfterLast('/'))
                    ?.apply { setBinaryContent((body.trimEnd() + "\n").toByteArray()) }
            }.getOrNull()
        } ?: return null
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
        return vf
    }

    /**
     * The other half of [createFile]: the file is already there, and the block
     * is a later version of the whole of it. No model is asked anything here -
     * a `file` block *is* the file, so the placement is not in doubt and the
     * diff is exact rather than a guess. Same dialog, same undoable write, so
     * "Create" on an existing file stops being a no-op.
     */
    fun updateFile(rel: String, body: String, dir: String? = null) {
        val vf = runReadAction { resolve(rel, dir) } ?: return run { createFile(rel, body, dir); Unit }
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(vf) }
            ?: return say("Cannot edit ${vf.name}")
        val before = document.text
        if (Patch.alreadyIn(before, body)) return say("${vf.name} already says this")
        applyPatch(
            vf, document, before, body,
            Patch.Edit(1, before.lines().size + 1, "", "The step's version of the whole file."),
        )
    }

    /**
     * A `paste` block is a fragment of a file that already exists, and half of
     * them say only "add to <dependencies>" or "call it from the controller" -
     * so the placement is the work, and it is the part a learner gets wrong.
     *
     * The local model is asked *where* the snippet goes, never *what* goes
     * there: it answers in line numbers ([Patch]), the snippet itself is
     * written verbatim from the curriculum, and nothing reaches the file until
     * the learner has approved the diff. A model that guesses badly costs a
     * cancelled dialog; it cannot invent code and it cannot silently overwrite.
     *
     * `rel` is null for the many labels that name no file - then it patches
     * whatever is open, which is where the learner is already looking.
     */
    fun patchFile(rel: String?, body: String, dir: String? = null) {
        val vf = rel?.let { runReadAction { resolve(it, dir) } }
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?: return say(if (rel != null) "No $rel in this project yet" else "Open the file to patch first")
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(vf) }
            ?: return say("Cannot edit ${vf.name}")
        val before = document.text
        if (Patch.alreadyIn(before, body)) return say("Already in ${vf.name}")

        val settings = ReviewerSettings.get().state
        say("Asking the model where this goes in ${vf.name}...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                Reviewers.locate(vf.name, before, body, settings.ollamaEndpoint.orEmpty(), settings.ollamaModel.orEmpty())
            }
            ApplicationManager.getApplication().invokeLater {
                outcome.fold(
                    onSuccess = { edit -> applyPatch(vf, document, before, body, edit) },
                    onFailure = {
                        notify(
                            "Patch failed",
                            reason(it, "Is <b>${settings.ollamaModel}</b> serving at ${settings.ollamaEndpoint}?"),
                            NotificationType.WARNING,
                        )
                    },
                )
            }
        }
    }

    /** The diff is the safety net, so it is shown every time and never remembered. */
    private fun applyPatch(vf: VirtualFile, document: Document, before: String, body: String, edit: Patch.Edit) {
        // The document can have moved on while the model was thinking; the
        // line numbers it chose describe the text that was sent, not this one.
        if (document.text != before) return say("${vf.name} changed while the model was thinking")
        val after = Patch.apply(before, body, edit)
        val what = when {
            edit.start == 1 && edit.end > before.lines().size -> "Update ${vf.name} to the step's version"
            edit.replaces > 0 -> "Replace lines ${edit.start}-${edit.end - 1} of ${vf.name}"
            else -> "Insert at line ${edit.start} of ${vf.name}"
        }
        if (!PatchPreview(project, vf, before, after, what, edit.reason).showAndGet()) return
        WriteCommandAction.runWriteCommandAction(project, "Patch ${vf.name}", null, {
            document.setText(after)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        OpenFileDescriptor(project, vf, (edit.start - 1).coerceAtLeast(0), 0).navigate(true)
    }

    /**
     * "Select the folder", "open the migration file" — do it instead of
     * describing it. A file opens in the editor, a folder is selected in the
     * Project view, and a path that does not exist yet falls back to the
     * nearest parent that does — which is exactly what a step saying "select
     * the folder and create the file" needs.
     */
    fun reveal(rel: String) = ApplicationManager.getApplication().invokeLater {
        var p = rel.replace('\\', '/').trimStart('/')
        var vf = runReadAction { resolve(p) }
        while (vf == null && '/' in p) {
            p = p.substringBeforeLast('/')
            vf = runReadAction { resolve(p) }
        }
        val target = vf ?: return@invokeLater say("No $rel in this project yet")
        if (!target.isDirectory) return@invokeLater OpenFileDescriptor(project, target, 0, 0).navigate(true)
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null)
        ProjectView.getInstance(project).select(null, target, true)
        if (p != rel) say("$rel does not exist yet — selected $p")
    }

    /**
     * A shortcut the prose names, run through the learner's *own* keymap, so
     * "Ctrl+Alt+Insert" does whatever they have bound there rather than what
     * the default keymap had when the lesson was written.
     */
    fun runShortcut(shortcut: String) {
        val ks = keyStrokeOf(shortcut) ?: return say("Cannot read the shortcut $shortcut")
        val id = KeymapManager.getInstance().activeKeymap.getActionIds(ks).firstOrNull()
            ?: return say("Nothing bound to $shortcut in this keymap")
        runAction(id)
    }

    /**
     * An action id — the one a lesson's trigger is watching for, usually, so
     * the panel can perform the feature it is teaching. Runs with the project
     * as context and nothing else: an action that wants a selection or an
     * editor will decline, which is the honest outcome for a click in a dock.
     */
    fun runAction(actionId: String) = ApplicationManager.getApplication().invokeLater {
        val action = ActionManager.getInstance().getAction(actionId)
            ?: return@invokeLater say("No $actionId action in this IDE")
        runCatching {
            ActionUtil.invokeAction(
                action, SimpleDataContext.getProjectContext(project), ActionPlaces.UNKNOWN, null, null
            )
        }.onFailure { say("$actionId did not run from here") }
    }

    /** Tool-window lessons name one by id ("Database", "Services"); open it. */
    fun openToolWindow(id: String) = ApplicationManager.getApplication().invokeLater {
        ToolWindowManager.getInstance(project).getToolWindow(id)?.activate(null)
            ?: say("No $id tool window in this IDE")
    }

    /**
     * Commands land in the IDE's own terminal, which is the point of being
     * in-IDE - and in the *same* tab each time. A step runs half a dozen
     * commands, and a tab per command buries the terminal in "Trainer (1..6)".
     *
     * createShellWidget always launches whatever shell is configured as the
     * IDE default - PowerShell or cmd on a stock Windows box - but lesson
     * bodies are written as bash (`trainer.sh`, see Blocks.kt). Routing
     * through `bash -lc` makes the command run the same regardless of what
     * the default terminal shell happens to be.
     */
    /**
     * The folder a step's commands belong in. A step names one relative to the
     * project root; a step that names none, or names one that is not there yet,
     * runs from the root - which is where the learner would have been anyway.
     */
    /** A path as one shell word, safe for spaces and for the odd apostrophe. */
    private fun quote(path: String) = "'" + path.replace("'", "'" + BACKSLASH + "''") + "'"

    private fun workDir(dir: String?): File? =
        project.basePath?.let(::File)?.takeIf { it.isDirectory }
            ?.let { base -> runReadAction { subdir(dir) }?.path?.let(::File)?.takeIf { it.isDirectory } ?: base }

    fun runInTerminal(cmd: String, dir: String? = null) = ApplicationManager.getApplication().invokeLater {
        val cwd = workDir(dir)?.path ?: return@invokeLater
        runCatching {
            val manager = TerminalToolWindowManager.getInstance(project)
            // Closing the tab drops it from the set, so this reopens rather than
            // writing into a widget that is no longer on screen.
            val widget = terminal?.takeIf { it in manager.terminalWidgets }
                ?: manager.createShellWidget(cwd, "Trainer", true, true).also { terminal = it }
            ToolWindowManager.getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            // cd every time rather than trusting the widget: it is reused across
            // steps that live in different folders, and a lesson body may cd on
            // its own (step 01 does). Where a command runs must not depend on
            // which buttons were clicked before it.
            // Joined by a newline, not by &&: a lesson body may be a bare
            // comment ("blank the display-name and start the app"), and && with
            // a comment on its right is a syntax error rather than a no-op.
            val script = "cd " + quote(cwd.replace(BACKSLASH, "/")) + " || exit 1\n" + cmd
            val toSend = if (windows) "bash -lc '${script.replace("'", "'\\''")}'" else script
            widget.sendCommandToExecute(toSend)
        }
    }

    fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        say("Copied to clipboard")
    }

    /** The status bar, because a balloon for a click the learner just made is noise. */
    private fun say(text: String) = StatusBar.Info.set(text, project)

    /** The same line, for the panels — "checking...", "did not pass". */
    fun status(text: String) = say(text)

    /**
     * Small-bug review via a local model, off the EDT since a reply can take
     * seconds. The answer lands in the same "Codecraft" balloon the panel
     * already uses for step-complete notifications.
     */
    fun reviewWithOllama(code: String) {
        val settings = ReviewerSettings.get().state
        say("Asking the model...")
        inBackground(
            work = { Reviewers.ollama(code, settings.ollamaEndpoint.orEmpty(), settings.ollamaModel.orEmpty()) },
            title = "Model review",
            hint = "Is a model serving at ${settings.ollamaEndpoint}?",
        )
    }

    /**
     * The same block, reviewed by a team: agent-platform plans the team and
     * fans its tasks out concurrently on its own side, so there is nothing to
     * thread here beyond staying off the EDT while it runs.
     */
    fun reviewWithAgentPlatform(code: String) {
        val settings = ReviewerSettings.get().state
        val teamId = settings.agentTeamId?.trim()?.toIntOrNull()
        if (teamId == null) {
            return notify(
                "Agent platform not configured",
                "Set a team template id in Settings | Tools | Codecraft - " +
                    "<code>GET ${settings.agentBaseUrl}/api/v1/teams/</code> lists them.",
                NotificationType.WARNING
            )
        }
        say("Starting agent team review...")
        inBackground(
            work = {
                Reviewers.agentTeam(
                    code,
                    settings.agentBaseUrl.orEmpty(),
                    teamId,
                    ReviewerSettings.get().agentToken,
                )
            },
            title = "Agent team review",
            hint = "Is the agent platform running at ${settings.agentBaseUrl}?",
        )
    }

    /** Both reviewers are the same shape: off the EDT, back as a balloon either way. */
    private fun inBackground(work: () -> String, title: String, hint: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching(work)
            ApplicationManager.getApplication().invokeLater {
                outcome.fold(
                    onSuccess = { notify(title, it, NotificationType.INFORMATION) },
                    onFailure = { notify("$title failed", reason(it, hint), NotificationType.WARNING) },
                )
            }
        }
    }

    /**
     * "Connection refused: no further information" is what a dead local server
     * actually throws, and on its own it does not say which server, where, or
     * what to do — so the address is always in the balloon, alongside the way
     * to change it.
     */
    private fun reason(e: Throwable, hint: String) =
        "${e.message ?: e::class.java.simpleName}<br><br>$hint<br>" +
            "Change it in <b>Settings | Tools | Codecraft</b>, which can also test it."

    fun notify(title: String, content: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("Codecraft")
            .createNotification(title, content, type)
            .notify(project)

    /**
     * A setup tool's check is a version/presence probe (`java -version`, `docker
     * --version`, ...), so exit code 0 is "installed" - off the EDT with a hard
     * timeout, since a hung command must not freeze the panel. `onResult` also
     * gets the tail of combined stdout/stderr on failure, so "not installed"
     * turns into "not on PATH" or "permission denied" instead of a bare X.
     */
    fun verify(cmd: String, timeoutSeconds: Long = 10, dir: String? = null, onResult: (Boolean, String?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val run = runCapturing(cmd, timeoutSeconds, limit = 500, dir = dir)
            val ok = run.exit == 0
            val result = ok to if (ok) null else run.output.takeLast(200).ifEmpty { "exit ${run.exit}" }
            ApplicationManager.getApplication().invokeLater {
                // A tooltip only reaches a learner who thinks to hover the
                // status icon; the status bar reaches whoever just clicked.
                result.second?.let { say(it) }
                onResult(result.first, result.second)
            }
        }
    }

    /**
     * Shows an agent's proposed edit as a diff and waits for an answer. This is
     * the gate ADR-002 chose for `edit_file`: the model may propose any change,
     * but in Code (manual) nothing reaches the file until the exact diff has
     * been seen and accepted - the same review a curriculum patch already gets.
     *
     * Called from the tool loop's pooled thread, so it marshals to the EDT and
     * blocks there. Returns false when cancelled, which the model is told.
     */
    fun confirmEdit(vf: VirtualFile, before: String, after: String, what: String, reason: String): Boolean {
        var approved = false
        ApplicationManager.getApplication().invokeAndWait {
            approved = PatchPreview(project, vf, before, after, what, reason).showAndGet()
        }
        return approved
    }

    /**
     * Writes text the agent produced, as one undoable command per file. Auto
     * mode skips the diff dialog but never the undo stack - Ctrl+Z and git stay
     * the net, which is the whole reason auto is tolerable at all.
     */
    fun applyEdit(vf: VirtualFile, after: String, title: String) {
        ApplicationManager.getApplication().invokeAndWait {
            val document = runReadAction { FileDocumentManager.getInstance().getDocument(vf) } ?: return@invokeAndWait
            WriteCommandAction.runWriteCommandAction(project, title, null, {
                document.setText(after)
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }
    }

    /**
     * What the learner is looking at: the open file, where the caret sits, and
     * what the IDE already thinks is wrong with it. The problems are the part
     * worth having - the model would otherwise ask for a build to learn what
     * the editor has had underlined the whole time.
     *
     * Every field is best-effort. Nothing here is worth failing a turn over, so
     * a project with no editor open, or a file the analyser has not reached
     * yet, simply produces less.
     */
    data class EditorContext(val path: String, val caretLine: Int, val problems: List<String>)

    /**
     * Reads that context off the EDT-bound editor and hands back plain data.
     * Called from the tool loop's pooled thread, so it marshals and waits;
     * `selectedTextEditor` cannot be touched from anywhere else.
     */
    fun editorContext(limit: Int = 8): EditorContext? {
        var found: EditorContext? = null
        ApplicationManager.getApplication().invokeAndWait {
            found = runCatching {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runCatching null
                val document = editor.document
                val vf = FileDocumentManager.getInstance().getFile(document) ?: return@runCatching null
                val root = project.guessProjectDir()
                val path = root?.let { VfsUtilCore.getRelativePath(vf, it) } ?: vf.name
                val caret = document.getLineNumber(editor.caretModel.offset) + 1
                EditorContext(path, caret, problems(document, limit))
            }.getOrNull()
        }
        return found
    }

    /**
     * The tabs the learner has open, project-relative, selected one first.
     * Marshalled and waited on for the same reason [editorContext] is:
     * FileEditorManager is EDT-only and the tool loop is not on the EDT.
     */
    fun openFiles(limit: Int = 20): List<String> {
        var found: List<String> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            found = runCatching {
                val manager = FileEditorManager.getInstance(project)
                val root = project.guessProjectDir()
                val selected = manager.selectedFiles.toList()
                (selected + manager.openFiles.toList()).distinct().mapNotNull { vf ->
                    root?.let { VfsUtilCore.getRelativePath(vf, it) } ?: vf.name
                }.take(limit)
            }.getOrElse { emptyList() }
        }
        return found
    }

    /**
     * The IDE's own analysis of the open file - the same underlines the learner
     * can see. Warnings and above only: weak-warning noise would fill the
     * budget with "unused import" on a file the question is not about.
     *
     * `DaemonCodeAnalyzerImpl.getHighlights` is the only way to read them
     * without re-running inspections, and it returns nothing until the analyser
     * has passed over the file - which is a normal state, not an error.
     */
    private fun problems(document: Document, limit: Int): List<String> = runCatching {
        runReadAction {
            DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project)
                .take(limit)
                .map { info ->
                    val line = document.getLineNumber(info.startOffset) + 1
                    "line $line ${info.severity.name}: ${info.description.orEmpty().take(200)}"
                }
        }
    }.getOrElse { emptyList() }

    /** What a command did: its exit code and the head of its combined output. */
    data class Run(val exit: Int, val output: String)

    /**
     * Runs a command and *keeps what it printed* - the difference between this
     * and [runInTerminal], which shows output to a human but can never read it
     * back. An agent asking "did the build pass, and why not" needs the text,
     * so the tool loop and the setup checks both come through here.
     *
     * Blocking, and never to be called on the EDT. A non-zero exit is a normal
     * result, not an exception: "tests failed, here is the output" is exactly
     * what the caller wants to see. -1 means it could not be run at all.
     */
    fun runCapturing(cmd: String, timeoutSeconds: Long = 60, limit: Int = 8000, dir: String? = null): Run = runCatching {
        // A basePath that is not a real directory (a light fixture, a project
        // on a remote filesystem) would make ProcessBuilder throw and report
        // every tool as missing; running from the IDE's own directory answers
        // "is this on PATH" just as well.
        val cwd = workDir(dir)
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val shell = if (windows) listOf("cmd", "/c", cmd) else listOf("sh", "-c", cmd)
        val proc = ProcessBuilder(shell).directory(cwd).redirectErrorStream(true).start()
        // Read on a separate thread while waiting, not after: a command that
        // prints more than the pipe buffer holds would otherwise deadlock
        // waiting for a reader. Capped, so a runaway log cannot exhaust heap
        // or bury a model in a megabyte of output.
        val output = StringBuilder()
        val drain = Thread {
            val buf = CharArray(1024)
            proc.inputStream.bufferedReader().use { r ->
                while (true) {
                    val n = runCatching { r.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    if (output.length < limit) output.append(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; start() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()   // a hung command must not outlive the answer
            return Run(-1, "timed out after ${timeoutSeconds}s")
        }
        drain.join(1000)
        Run(proc.exitValue(), output.toString().trim())
    }.getOrElse { Run(-1, it.message ?: "could not run") }
}

/**
 * The IDE's own diff viewer, in a dialog with an Apply button — the one place
 * a learner can see exactly what a model decided before it touches their file.
 * Nothing here is remembered or defaulted: every patch is confirmed.
 */
private class PatchPreview(
    project: Project,
    file: VirtualFile,
    before: String,
    after: String,
    private val what: String,
    private val reason: String,
) : DialogWrapper(project) {

    private val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

    init {
        title = what
        setOKButtonText("Apply")
        val factory = DiffContentFactory.getInstance()
        panel.setRequest(
            SimpleDiffRequest(
                what,
                factory.create(project, before, file.fileType),
                factory.create(project, after, file.fileType),
                "Now",
                "After patch",
            )
        )
        init()
    }

    override fun createNorthPanel(): JComponent =
        JBLabel(reason.ifBlank { "The model gave no reason." }).apply { border = JBUI.Borders.emptyBottom(6) }

    override fun createCenterPanel(): JComponent =
        panel.component.apply { preferredSize = JBUI.size(760, 520) }
}
