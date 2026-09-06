package io.tanvoid0.codecraft

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager

/**
 * How the editor reaches the chat panel without either of them knowing about
 * the other. The panel puts a callback here when it is built; the action looks
 * for one. Nothing holds a strong reference to a disposed panel because the
 * panel clears it on dispose.
 *
 * A project-level service rather than a global: two open projects each have
 * their own chat, and a selection belongs to the one it came from.
 */
@Service(Service.Level.PROJECT)
class ChatBus {
    /** Set by the chat panel: (path, fromLine, toLine, code). */
    var attach: ((String, Int, Int, String) -> Unit)? = null
}

/**
 * "Add Selection to Chat" - the gesture every agentic editor has, and the
 * cheapest way to give the model the exact code being asked about instead of
 * hoping an `@` mention of the whole file lands near it.
 *
 * Lines are 1-based, matching what the editor gutter shows, because the label
 * goes to a human as much as to the model.
 */
class AddSelectionToChatAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        // Only offered when there is something to add - a greyed-out item says
        // "select something first" better than a no-op that looks broken.
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel
        val code = selection.selectedText ?: return

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val root = project.guessProjectDir()
        val path = file?.let { vf -> root?.let { VfsUtilCore.getRelativePath(vf, it) } ?: vf.name } ?: "selection"
        val doc = editor.document
        val from = doc.getLineNumber(selection.selectionStart) + 1
        val to = doc.getLineNumber(selection.selectionEnd) + 1

        // Opening the tool window builds the panel if this is the first use,
        // which is what registers the callback - so ask for it afterwards.
        ToolWindowManager.getInstance(project).getToolWindow("Trainer")?.activate(null)
        project.service<ChatBus>().attach?.invoke(path, from, to, code)
    }
}
