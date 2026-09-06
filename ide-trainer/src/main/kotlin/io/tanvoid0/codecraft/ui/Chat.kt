package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.components.service
import io.tanvoid0.codecraft.ChatBus
import io.tanvoid0.codecraft.ChatController
import io.tanvoid0.codecraft.ChatUi
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.Mode
import io.tanvoid0.codecraft.ModelInfo
import io.tanvoid0.codecraft.ReviewerSettings
import io.tanvoid0.codecraft.Reviewers
import io.tanvoid0.codecraft.ChatDb
import java.awt.BorderLayout
import io.tanvoid0.codecraft.Experiments
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * A continue.dev-style chat, over whichever backend the dropdown picks:
 * Ollama (plain text, this panel keeps the turn history) or agent-platform's
 * Coder agent - repo-aware, its own read_file/write_file/list_dir/search/
 * run_command tools, looping over them server-side (`run_agent_turn`) until
 * it has an answer, same idea as Claude Code's or continue.dev's own tool
 * loop, just running on that platform instead of in this plugin.
 *
 * Turn-based, not streamed - a reply lands whole, not word by word. Multi-turn
 * history lives only in this panel; closing the tool window drops it, same as
 * continue.dev's own "New Session".
 */
private val ONLINE_COLOR = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(98, 200, 98))
private val OFFLINE_COLOR = JBColor(java.awt.Color(178, 0, 0), java.awt.Color(230, 100, 100))

// Sender-label colors - the only thing distinguishing one speaker from the
// next in a flat scrolling transcript. Body text stays the pane's default
// color; only the "Who:" prefix is colored, so code/output inside a reply
// still reads as plain text rather than fighting a tint.
private val USER_COLOR = JBColor(java.awt.Color(0x1a, 0x73, 0xe8), java.awt.Color(0x8a, 0xb4, 0xf8))
private val ASSISTANT_COLOR = JBColor(java.awt.Color(0x6f, 0x42, 0xc1), java.awt.Color(0xb3, 0x92, 0xf0))
private val TOOL_COLOR = JBColor(java.awt.Color(0x75, 0x75, 0x75), java.awt.Color(0x9a, 0xa0, 0xa6))

/**
 * The paths worth offering for an "@" query, best first. Uses the IDE's own
 * fuzzy matcher (the one behind Goto File), then prefers the shortest path -
 * with `Wallet` typed, `Wallet.java` should beat a test fixture six directories
 * down that merely contains the same letters. An empty query offers everything,
 * which is what a bare "@" should do.
 */
internal fun rankMentions(paths: List<String>, query: String, limit: Int = 15): List<String> {
    val matcher = NameUtil.buildMatcher("*$query").build()
    return paths.filter { query.isEmpty() || matcher.matches(it) }
        .sortedWith(compareBy({ it.length }, { it }))
        .take(limit)
}

class ChatPanel(private val project: Project, private val ide: Ide) : Wrapper() {

    private val backend = JComboBox(arrayOf("Ollama", "Agent Platform"))
    // Same combo the settings page uses (editable, metadata-labelled) - a
    // machine with thirty models pulled needs the list, not a text field.
    private val models = ComboBox<ModelInfo>().apply {
        setSwingPopup(false)
        isEditable = true
        renderer = metaRenderer()
        // An editable combo sizes itself to its longest entry, and model ids run
        // long ("qwen2.5-coder:32b-instruct-q4_K_M"). Unbounded, this one control
        // pushes the whole row past the width of a docked tool window and the
        // rest of the row is clipped away. The popup still shows names in full.
        preferredSize = java.awt.Dimension(JBUI.scale(150), preferredSize.height)
    }
    private val providerStatus = JBLabel().apply { toolTipText = "Whether the selected provider answered the last check" }
    // Explicit type: the listener below calls currentMode(), which reads this
    // property, and inference cannot chase that circle on its own.
    private val mode: ComboBox<Mode> = ComboBox(Mode.entries.toTypedArray()).apply {
        selectedItem = Mode.CHAT
        addActionListener { toolTipText = currentMode().hint }
        toolTipText = Mode.CHAT.hint
    }
    private val transcript = JTextPane().apply {
        isEditable = false
        border = JBUI.Borders.empty(8)
        // Replies are mostly code and tool output, and a proportional font puts
        // every fenced block out of alignment. The editor's own font, so it
        // matches what the same code looks like in a tab. ponytail: one font
        // for the whole transcript, not per-block - a real Markdown renderer
        // needs an editor pane, which is the upgrade path if prose suffers.
        font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
            .globalScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
    }
    // Attrs the current streaming reply is being typed with - appendToken has
    // no "who" of its own, so beginReply hands it the label's color once.
    private var streamAttrs = SimpleAttributeSet()
    private val input = JBTextField().apply { emptyText.text = "Ask about this curriculum, or @ a file..." }
    private val send = JButton("Send", AllIcons.Actions.Execute)
    private val stop = JButton("Stop", AllIcons.Actions.Suspend).apply {
        isVisible = false
        toolTipText = "Stop after the current tool call"
        addActionListener { controller.stop(); status.text = "Stopping..." }
    }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    // Appears only after a failed turn. The message that failed is usually
    // still worth sending - a dead socket or a model still loading - and
    // retyping it is the wrong price for that.
    private val retry = JButton("Retry", AllIcons.Actions.Refresh).apply {
        isVisible = false
        toolTipText = "Send the last message again"
        addActionListener { retryLast() }
    }
    private var lastPrompt: String? = null

    /**
     * The "still working" indicator. A label, deliberately *not* a line in the
     * transcript: the document is append-only and the streaming tokens own its
     * tail, so a ticking line that rewrites itself there corrupts the reply it
     * is sitting under. The elapsed count is the informative part - it is what
     * separates "slow model" from "hung panel".
     */
    private val thinking = JBLabel(com.intellij.ui.AnimatedIcon.Default.INSTANCE).apply {
        isVisible = false
        foreground = UIUtil.getContextHelpForeground()
    }
    private var thinkingSince = 0L
    private val thinkingTimer = javax.swing.Timer(1000) { tickThinking() }.apply { isRepeats = true }

    private var mentionStart: Int? = null
    private var mentionPopup: JBPopup? = null
    // What the popup is currently showing, so Enter can take the top one
    // without asking the popup - it does not have focus to ask.
    private var matches: List<String> = emptyList()

    private var busy = false

    /**
     * The turn itself - backends, approvals, history, persistence - lives there
     * rather than in the panel, so what "Code (manual)" means is testable
     * without building a tool window to ask it.
     */
    private val controller = ChatController(project, ide, TranscriptUi())

    /** Messages typed while a turn was running, oldest first. */
    private val queued = mutableListOf<String>()

    /**
     * Code sent over from the editor by "Add Selection to Chat", waiting to go
     * with the next message. Held apart from the input text because the input
     * is a single-line field - a forty-line selection cannot live in it - and
     * because these are already-formatted blocks, not something to re-resolve.
     */
    private val attachments = mutableListOf<String>()

    init {
        // CENTER, not WEST: this panel lives in a narrow right-hand dock, and
        // WEST hands a row its preferred width and then clips whatever does not
        // fit - which silently ate the last control on the row. In CENTER the
        // FlowLayout gets the real width and wraps to a second line instead.
        val top = JPanel(BorderLayout()).apply {
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
                    add(backend); add(providerStatus)
                    add(JButton(AllIcons.Actions.Refresh).apply {
                        toolTipText = "Recheck whether the server is up"
                        addActionListener { refreshModels() }
                    })
                    add(models)
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                    add(JButton(AllIcons.Vcs.History).apply {
                        toolTipText = "Past conversations in this project"
                        addActionListener { showHistory(this) }
                    })
                    add(JButton("New Chat", AllIcons.General.Add).apply { addActionListener { clear() } })
                },
                BorderLayout.EAST,
            )
            border = JBUI.Borders.empty(4)
        }
        // Mode sits with the input, not up with the connection settings: it is a
        // per-turn choice ("plan this", "now go do it"), which is how every other
        // agentic editor places it, and down here it cannot be crowded off a row.
        val bottom = JPanel(BorderLayout()).apply {
            add(mode, BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                add(retry); add(stop); add(send)
            }, BorderLayout.EAST)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(thinking); add(status)
            }, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(
                JBScrollPane(transcript).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                },
                BorderLayout.CENTER,
            )
            add(bottom, BorderLayout.SOUTH)
        })

        // Lets "Add Selection to Chat" find this panel without the editor
        // action knowing the tool window exists.
        project.service<ChatBus>().attach = { path, from, to, code -> attachSelection(path, from, to, code) }

        send.addActionListener { submit() }
        input.addActionListener { submit() } // Enter in a JTextField fires this same event.
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) = onInputKey(e)
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onMentionEdit(e.offset + e.length)
            override fun removeUpdate(e: DocumentEvent) = onMentionEdit(e.offset)
            override fun changedUpdate(e: DocumentEvent) {}
        })
        backend.addActionListener { refreshModels() }
        // Fires on a manual pick and on the auto-pick-first inside fill() -
        // either way, what is selected is what gets remembered.
        models.addActionListener { rememberModel(models.currentName()) }
        refreshModels()
    }

    private fun rememberModel(name: String) {
        if (name.isBlank()) return
        val state = ReviewerSettings.get().state
        if (backend.selectedItem == "Agent Platform") state.agentModel = name else state.ollamaModel = name
    }

    /**
     * "New Chat" starts a new one; it does not throw the old one away. Dropping
     * chatId is the whole of it - the previous conversation is already saved
     * under its own id, and the next turn inserts a new row.
     */
    private fun clear() {
        transcript.text = ""
        controller.clear()
        // Anything waiting was queued against the conversation being left; it
        // must not land on the new one.
        queued.clear()
        lastPrompt = null
        retry.isVisible = false
        status.text = ""
    }

    /** Reopens a saved conversation: its text back in the transcript, its turns back in play. */
    private fun openSession(chat: ChatDb.Chat) {
        val saved = controller.openSession(chat)
        clear()
        saved.forEach { append(if (it.role == "user") "You" else "Assistant", it.text) }
    }

    private fun showHistory(under: JComponent) {
        val chats = controller.history()
        if (chats.isEmpty()) return ide.status("No saved conversations yet")
        val stamp = java.text.SimpleDateFormat("d MMM HH:mm")
        // Matched back by position, not by parsing the label: two conversations
        // can start with the same question in the same minute.
        val labels = chats.map { "${stamp.format(java.util.Date(it.at))}  ${it.title}" }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Past conversations")
            .setItemChosenCallback { chosen -> chats.getOrNull(labels.indexOf(chosen))?.let(::openSession) }
            .createPopup()
            .showUnderneathOf(under)
    }

    private fun refreshModels() {
        val state = ReviewerSettings.get().state
        val token = ReviewerSettings.get().agentToken
        val useAgent = backend.selectedItem == "Agent Platform"
        // Whatever is already picked in the box wins over the saved setting -
        // switching backend and back should not forget an in-session choice.
        val keep = models.currentName().ifBlank { if (useAgent) state.agentModel.orEmpty() else state.ollamaModel.orEmpty() }
        providerStatus.text = "checking..."
        providerStatus.foreground = UIUtil.getContextHelpForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            // agentModels() alone can't tell "server down" from "server up,
            // publishes no catalogue" - agentAlive() is what actually probes.
            val result = runCatching {
                if (useAgent) {
                    Reviewers.agentAlive(state.agentBaseUrl.orEmpty(), token)
                    Reviewers.agentModels(state.agentBaseUrl.orEmpty(), token)
                } else {
                    Reviewers.models(state.ollamaEndpoint.orEmpty())
                }
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { found -> models.fill(found, keep) }
                providerStatus.text = if (result.isSuccess) "● online" else "● offline"
                providerStatus.foreground = if (result.isSuccess) ONLINE_COLOR else OFFLINE_COLOR
                providerStatus.toolTipText = result.exceptionOrNull()?.message ?: "Reachable"
            }
        }
    }

    /** Finds the "@query" run touching `caret`, if any, and (re)opens the popup for it. */
    private fun onMentionEdit(caret: Int) {
        val text = input.text
        val at = text.lastIndexOf('@', (caret - 1).coerceAtLeast(0))
        val touchesSpace = at >= 0 && text.substring(at, caret.coerceAtMost(text.length)).contains(' ')
        val precededByWord = at > 0 && !text[at - 1].isWhitespace()
        if (at < 0 || touchesSpace || precededByWord) return closeMention()
        mentionStart = at
        showMentionPopup(text.substring(at + 1, caret.coerceAtMost(text.length)))
    }

    private fun showMentionPopup(query: String) {
        mentionPopup?.cancel()
        matches = rankMentions(controller.projectPaths(), query)
        if (matches.isEmpty()) { mentionPopup = null; return }
        mentionPopup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(matches)
            .setItemChosenCallback { chosen -> insertMention(chosen) }
            // Without this the popup takes focus the moment it opens, and the
            // rest of what you type goes to its speed-search instead of the
            // input - filter-as-you-type would break on the second character.
            // Keyboard handling moves to the input field instead, below.
            .setRequestFocus(false)
            .createPopup()
            .also { it.showUnderneathOf(input) }
    }

    /**
     * Enter/Tab take the top match while the popup is up, Escape dismisses it.
     * A KeyListener runs before the field's own ENTER binding, so consuming
     * here is what stops a mention-completing Enter from also sending the turn.
     */
    private fun onInputKey(e: java.awt.event.KeyEvent) {
        if (mentionPopup == null) return
        when (e.keyCode) {
            java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_TAB -> {
                matches.firstOrNull()?.let { insertMention(it); e.consume() }
            }
            java.awt.event.KeyEvent.VK_ESCAPE -> { closeMention(); e.consume() }
        }
    }

    private fun insertMention(path: String) {
        val start = mentionStart ?: return
        val doc = input.document
        val end = input.caretPosition
        doc.remove(start, end - start)
        doc.insertString(start, "@$path ", null)
        input.caretPosition = (start + path.length + 2).coerceAtMost(doc.length)
        closeMention()
    }

    private fun closeMention() {
        mentionPopup?.cancel()
        mentionPopup = null
        mentionStart = null
        matches = emptyList()
    }

    /**
     * Attaches an editor selection to the next message, from the editor action.
     * Called on the EDT.
     */
    fun attachSelection(path: String, fromLine: Int, toLine: Int, code: String) {
        val where = if (fromLine == toLine) "$path:$fromLine" else "$path:$fromLine-$toLine"
        attachments.add("\n\n--- $where ---\n$code")
        status.text = "${attachments.size} selection${if (attachments.size == 1) "" else "s"} attached"
        input.requestFocusInWindow()
    }

    /** Bold, colored by who's speaking - the label is the only thing that isn't plain body text. */
    private fun labelAttrs(who: String): SimpleAttributeSet = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setForeground(
            this,
            when {
                who == "You" -> USER_COLOR
                who == "Error" -> OFFLINE_COLOR
                who.startsWith("·") -> TOOL_COLOR // "· toolname"
                else -> ASSISTANT_COLOR
            },
        )
    }

    /** Tool output is muted and italic so it reads as a side note, not part of the reply. */
    private fun bodyAttrs(who: String): SimpleAttributeSet = SimpleAttributeSet().apply {
        if (who.startsWith("·")) {
            StyleConstants.setForeground(this, TOOL_COLOR)
            StyleConstants.setItalic(this, true)
        }
    }

    /** Retitles the indicator with how long this turn has been going. */
    private fun tickThinking() {
        val secs = (System.currentTimeMillis() - thinkingSince) / 1000
        thinking.text = "Working... ${secs}s"
    }

    private fun append(who: String, text: String) {
        val doc = transcript.styledDocument
        doc.insertString(doc.length, "$who: ", labelAttrs(who))
        doc.insertString(doc.length, "$text\n\n", bodyAttrs(who))
        transcript.caretPosition = doc.length
    }

    /** Opens a reply the tokens will be appended into, without its trailing gap yet. */
    private fun beginReply(who: String) {
        val doc = transcript.styledDocument
        doc.insertString(doc.length, "$who: ", labelAttrs(who))
        transcript.caretPosition = doc.length
        streamAttrs = bodyAttrs(who)
    }

    /**
     * One streamed fragment. Called for every token, so it does the least it
     * can: append, and follow the caret only if the view is already at the
     * bottom - yanking the scroll back down while someone is reading earlier
     * output is the thing that makes streaming panels unusable.
     */
    private fun appendToken(piece: String) {
        val doc = transcript.styledDocument
        val atBottom = transcript.caretPosition >= doc.length - 1
        doc.insertString(doc.length, piece, streamAttrs)
        if (atBottom) transcript.caretPosition = doc.length
    }

    private fun endReply() {
        val doc = transcript.styledDocument
        doc.insertString(doc.length, "\n\n", SimpleAttributeSet())
        transcript.caretPosition = doc.length
    }

    private companion object {
        /** How much of a tool's output the transcript shows before eliding. */
        const val TOOL_PREVIEW_LINES = 6

        /** Tool rounds a Code-mode turn may take; edit-build-fix spends them fast. */
        const val CODE_ROUNDS = 20
    }

    /** A project file's text, for [rulesPreamble]. Null when it is not there. */
    private fun currentMode() = mode.selectedItem as? Mode ?: Mode.CHAT

    /**
     * The panel's side of [ChatUi]. Every method is called from the pooled
     * thread a turn runs on, so each marshals - and [askToRun] marshals *and
     * waits*, because the turn cannot proceed until a human has answered.
     */
    private inner class TranscriptUi : ChatUi {

        override fun say(who: String, text: String) =
            ApplicationManager.getApplication().invokeLater { append(who, text) }

        override fun beginReply(who: String) =
            ApplicationManager.getApplication().invokeLater { this@ChatPanel.beginReply(who) }

        override fun token(piece: String) =
            ApplicationManager.getApplication().invokeLater { appendToken(piece) }

        override fun endReply() =
            ApplicationManager.getApplication().invokeLater { this@ChatPanel.endReply() }

        /**
         * Three buttons, because "yes" and "yes forever" are different answers.
         * Returns the index of the one pressed; -1 when the dialog is closed,
         * which counts as Deny - dismissing a permission prompt is not consent.
         */
        override fun askToRun(command: String): Int {
            var answer = -1
            ApplicationManager.getApplication().invokeAndWait {
                answer = com.intellij.openapi.ui.Messages.showDialog(
                    project,
                    "The assistant wants to run:\n\n" + command,
                    "Run this command?",
                    arrayOf("Run Once", "Always Allow", "Deny"),
                    0,
                    com.intellij.openapi.ui.Messages.getQuestionIcon(),
                )
            }
            return answer
        }
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        // Sending mid-turn queues rather than being refused. Paired with Stop
        // this covers both of the things you want here: wait your turn, or cut
        // the current one short and go straight to the new message.
        if (busy) {
            queued.add(text)
            input.text = ""
            status.text = "${queued.size} queued - Stop to run ${if (queued.size == 1) "it" else "them"} now"
            return
        }
        input.text = ""
        closeMention()
        append("You", text)
        setBusy(true)

        val useAgent = backend.selectedItem == "Agent Platform"
        val model = models.currentName()
        val turnMode = currentMode()
        // Taken now and cleared: attachments belong to the message being sent,
        // and leaving them would silently re-attach the same code every turn.
        val attached = attachments.joinToString("").also { attachments.clear() }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = controller.run(text, turnMode, useAgent, model, attached)
            ApplicationManager.getApplication().invokeLater {
                if (!result.ok) {
                    // Only a failure offers Retry, and only for the message that
                    // failed - a Retry that outlived its turn would resend an old
                    // question against a new conversation.
                    lastPrompt = text
                    retry.isVisible = true
                }
                setBusy(false)
                status.text = result.usage
                    ?.let { "${it.promptTokens ?: "?"} prompt · ${it.completionTokens ?: "?"} reply tokens" }
                    .orEmpty()
                // Last, so it runs after busy is cleared - drainQueue calls
                // submit(), which would otherwise just re-queue the message.
                drainQueue()
            }
        }
    }

    /** Puts the failed message back in the input and sends it again. */
    private fun retryLast() {
        val text = lastPrompt ?: return
        if (busy) return
        retry.isVisible = false
        input.text = text
        submit()
    }

    /**
     * The input stays live while a turn runs. Typing during a long answer is
     * the normal thing to want, and disabling the field meant losing the
     * thought - now it queues instead (see [submit]).
     */
    private fun setBusy(b: Boolean) {
        busy = b
        // Stop appears while a turn is in flight: a loop that may run for
        // several rounds needs a way out that is not "wait or kill the IDE".
        stop.isVisible = b
        send.text = if (b) "Queue" else "Send"
        send.toolTipText = if (b) "Send when the current turn finishes" else null
        if (b) {
            status.text = ""
            retry.isVisible = false
            thinkingSince = System.currentTimeMillis()
            tickThinking()
            thinking.isVisible = true
            thinkingTimer.start()
        } else {
            thinkingTimer.stop()
            thinking.isVisible = false
        }
    }

    /**
     * Starts the next queued message, if there is one. Called at the end of
     * every turn - including a stopped or failed one, which is what makes
     * "Stop, then work on this instead" a two-click gesture rather than a
     * feature of its own: queue the new message, hit Stop, and the abort runs
     * straight into it.
     */
    private fun drainQueue() {
        val next = queued.removeFirstOrNull() ?: return run { status.text = "" }
        status.text = if (queued.isEmpty()) "" else "${queued.size} queued"
        input.text = next
        submit()
    }
}
