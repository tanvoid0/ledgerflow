package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.tanvoid0.codecraft.Cheat
import io.tanvoid0.codecraft.Cheatsheet
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.fill
import io.tanvoid0.codecraft.holes
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The path's quick reference: a command template, one field per hole in it,
 * and the finished line ready to copy or run.
 *
 * Typing the branch name into the dock beats retyping the whole command from
 * memory — and the assembled line is editable, because the last tweak is
 * always the one no template anticipated.
 */
class CheatsView(private val ide: Ide) : JPanel(BorderLayout()) {

    private var sheet: Cheatsheet? = null
    private var query = ""
    private val body = Wrapper()

    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search commands"
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                query = text.trim().lowercase()
                render()
            }
        })
    }

    init {
        add(search, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    fun show(sheet: Cheatsheet?) {
        this.sheet = sheet
        query = ""
        search.text = ""
        search.isVisible = sheet != null
        render()
    }

    private fun render() {
        val s = sheet
        if (s == null) {
            body.setContent(emptyState(AllIcons.General.Information, "This path ships no cheatsheet."))
            return
        }
        body.setContent(JBScrollPane(WidthTracking(build(s))).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
    }

    private fun matches(c: Cheat): Boolean {
        if (query.isEmpty()) return true
        val text = "${c.name.orEmpty()} ${c.cmd.orEmpty()} ${c.note.orEmpty()} ${c.ide.orEmpty()}"
        return text.lowercase().contains(query)
    }

    private fun build(s: Cheatsheet): JComponent = panel {
        var groups = 0
        s.groups.orEmpty().forEach { group ->
            val entries = group.entries.orEmpty().filter(::matches)
            if (entries.isEmpty()) return@forEach
            groups++
            // Only the first group is open while browsing: eight groups of ten
            // commands is not a list you scroll looking for one. A search opens
            // everything it kept.
            val open = query.isNotEmpty() || groups == 1
            collapsibleGroup("${group.name.orEmpty()} (${entries.size})") {
                entries.forEach { entry(it) }
            }.expanded = open
        }
        if (groups == 0) row { comment("Nothing matches \"$query\".") }
    }.apply { border = JBUI.Borders.empty(8, 10, 12, 10) }

    private fun com.intellij.ui.dsl.builder.Panel.entry(c: Cheat) {
        val template = c.cmd.orEmpty()
        val names = holes(template)
        val values = linkedMapOf<String, String>()

        row { text("<b>${c.name.orEmpty()}</b>", MAX_LINE_LENGTH_WORD_WRAP) }
        c.note?.let { row { proseComment(ide, it) } }
        c.ide?.let { row { proseComment(ide, "IDE: $it") } }

        // The finished line: shown even with nothing typed, so the shape of
        // the command is readable before any field is filled.
        lateinit var out: JBTextField
        val defaults = c.vars.orEmpty().mapNotNull { v ->
            v.name?.let { n -> v.default?.let { n to it } }
        }.toMap()
        values.putAll(defaults)

        names.forEach { name ->
            val spec = c.vars.orEmpty().firstOrNull { it.name == name }
            row(name) {
                textField()
                    .align(AlignX.FILL)
                    .applyToComponent {
                        text = defaults[name].orEmpty()
                        spec?.hint?.let { emptyText.text = it }
                        document.addDocumentListener(object : DocumentListener {
                            override fun insertUpdate(e: DocumentEvent) = changed()
                            override fun removeUpdate(e: DocumentEvent) = changed()
                            override fun changedUpdate(e: DocumentEvent) = changed()
                            private fun changed() {
                                values[name] = text
                                out.text = fill(template, values)
                            }
                        })
                    }
            }
        }

        row {
            out = textField().align(AlignX.FILL).resizableColumn()
                .applyToComponent { text = fill(template, values) }
                .component
        }
        row {
            iconLink("Copy", AllIcons.Actions.Copy, showText = true) { ide.copy(out.text) }
            iconLink("Run", AllIcons.Actions.Execute, showText = true) { ide.runInTerminal(out.text, null) }
        }
    }
}
