package io.tanvoid0.codecraft.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.ModelInfo
import io.tanvoid0.codecraft.ReviewerSettings
import io.tanvoid0.codecraft.Reviewers
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel

/**
 * **Settings | Tools | Codecraft** — which model does the work, where the
 * two servers are, and whether anything is actually listening on them.
 *
 * Both servers are local and optional, which in practice means both are often
 * *not* running, and without this page that shows up as a balloon in the
 * middle of a step. **Test** asks here instead, and for the local model the
 * same call fills the dropdown: the only honest list of models is the one that
 * machine has pulled.
 */
class ReviewerConfigurable : BoundConfigurable("Codecraft") {

    override fun createPanel(): DialogPanel {
        val settings = ReviewerSettings.get()
        val state = settings.state
        lateinit var endpoint: JBTextField
        lateinit var models: ComboBox<ModelInfo>
        // Nullable, not lateinit: the DSL applies the binding while the panel is
        // still being built, so the combo fires its listener before this row exists.
        var modelMeta: JLabel? = null
        lateinit var ollamaStatus: JLabel
        lateinit var agentUrl: JBTextField
        lateinit var agentModels: ComboBox<ModelInfo>
        lateinit var agentStatus: JLabel

        return panel {
            group("Review and patching (local model)") {
                row("Endpoint:") {
                    endpoint = textField()
                        .bindText(state::ollamaEndpoint.nonNull())
                        .align(AlignX.FILL)
                        .component
                }
                row("Model:") {
                    models = comboBox<ModelInfo>(emptyList(), metaRenderer())
                        .applyToComponent {
                            // The platform's own popup rather than Swing's: it
                            // has speed search, so a machine with thirty models
                            // pulled is still one "coder" away from the right one.
                            setSwingPopup(false)
                            // Editable as well, because pulling a model and
                            // configuring it are done in either order: a name
                            // this machine has never seen is a legitimate entry,
                            // and Test will tell you it is not installed yet.
                            isEditable = true
                            addActionListener { modelMeta?.text = metaOf(currentName()) }
                        }
                        // Bound to the name only, and the name is what
                        // `trainer-reviewers.xml` already persists — so the
                        // choice survives closing the dialog, the project and
                        // the IDE, with or without a server to confirm it.
                        .bind(
                            { it.currentName() },
                            { combo, name -> combo.select(name) },
                            state::ollamaModel.nonNull(),
                        )
                        .align(AlignX.FILL)
                        .component
                    models.select(state.ollamaModel.orEmpty())
                }
                row("") {
                    modelMeta = label(models.metaOf(models.currentName())).component
                        .apply { foreground = UIUtil.getContextHelpForeground() }
                }
                row {
                    button("Test and list models") {
                        val url = endpoint.text
                        val wanted = models.currentName()
                        ollamaStatus.text = "Asking $url..."
                        offEdt(
                            work = { Reviewers.models(url) },
                            ok = { found ->
                                models.fill(found, keep = wanted)
                                modelMeta?.text = models.metaOf(models.currentName())
                                ollamaStatus.text = when {
                                    found.isEmpty() -> "Running, nothing pulled - try: ollama pull qwen2.5-coder"
                                    // Server up, model missing is the failure
                                    // people read as "the plugin is broken".
                                    wanted.isNotBlank() && found.none { it.name.startsWith(wanted) } ->
                                        "Running, but $wanted is not installed - try: ollama pull $wanted"
                                    else -> "Running - ${found.size} models"
                                }
                            },
                            fail = { ollamaStatus.text = "Not reachable: $it" },
                        )
                    }
                    ollamaStatus = label("").component.apply { foreground = UIUtil.getContextHelpForeground() }
                }
                row {
                    comment(
                        "This model answers reviews, and for a <b>paste</b> block it is asked only <i>where</i> the " +
                            "snippet goes - the lines written are the curriculum's own. Parameter size is the part " +
                            "that decides: a 1.5B model will place a snippet in the wrong place often enough to be " +
                            "annoying. A tagged name (<code>qwen2.5-coder:7b</code>) pins one size."
                    )
                }
            }
            group("Deep Review (agent platform)") {
                row("Base URL:") {
                    agentUrl = textField()
                        .bindText(state::agentBaseUrl.nonNull())
                        .align(AlignX.FILL)
                        .component
                }
                row("Team template id:") {
                    textField().bindText(state::agentTeamId.nonNull()).align(AlignX.FILL)
                }
                row("Its models:") {
                    // Nothing to bind: which model each agent uses is that
                    // platform's business, set in the team template. This is
                    // here to answer "what is it going to review with".
                    agentModels = comboBox<ModelInfo>(emptyList(), metaRenderer())
                        .applyToComponent { setSwingPopup(false) }
                        .enabled(false)
                        .align(AlignX.FILL)
                        .component
                }
                row {
                    button("Test and list models") {
                        val url = agentUrl.text
                        agentStatus.text = "Asking $url..."
                        offEdt(
                            work = { Reviewers.agentAlive(url, settings.agentToken) to Reviewers.agentModels(url, settings.agentToken) },
                            ok = { (alive, found) ->
                                agentModels.fill(found, keep = "")
                                agentModels.isEnabled = found.isNotEmpty()
                                agentStatus.text =
                                    if (found.isEmpty()) "$alive, publishes no model list"
                                    else "$alive, ${found.size} models"
                            },
                            fail = { agentStatus.text = "Not reachable: $it" },
                        )
                    }
                    agentStatus = label("").component.apply { foreground = UIUtil.getContextHelpForeground() }
                }
                row {
                    comment(
                        "The id of a team already created in that platform - <code>GET /api/v1/teams/</code> there " +
                            "lists them, and <b>Test</b> counts them. The token is read from the " +
                            "<code>AGENT_PLATFORM_TOKEN</code> environment variable rather than kept here, because " +
                            "this file is plain text."
                    )
                }
            }
        }
    }
}

/** Name first, everything the server said about it after. */
internal fun metaRenderer() = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
    label.text = value?.label().orEmpty()
}

/**
 * The name in the field, whether it was picked or typed. An editable combo's
 * editor holds a `String` the moment anyone touches it, and `selectedItem` only
 * catches up when the edit is committed — reading the editor first is what makes
 * a name typed and never Entered still get saved.
 */
internal fun ComboBox<ModelInfo>.currentName(): String {
    val typed = editor?.item
    return when {
        typed is ModelInfo -> typed.name
        typed is String && typed.isNotBlank() -> typed.trim()
        else -> (selectedItem as? ModelInfo)?.name.orEmpty()
    }
}

internal fun ComboBox<ModelInfo>.items() = (0 until model.size).map { model.getElementAt(it) }

/** Blank for a name this machine has not pulled — there is nothing honest to say about it. */
internal fun ComboBox<ModelInfo>.metaOf(name: String) =
    items().firstOrNull { it.name == name }?.meta.orEmpty()

/**
 * Refill without losing the current choice: a model configured on a machine
 * that is not running Ollama right now — or typed before it is pulled — is
 * still the choice, so it stays in the list (bare, with no metadata to show)
 * rather than being silently swapped for whatever answers first.
 */
internal fun ComboBox<ModelInfo>.fill(found: List<ModelInfo>, keep: String) {
    val items = if (keep.isBlank() || found.any { it.name == keep }) found else found + ModelInfo(keep, emptyList())
    model = DefaultComboBoxModel(items.toTypedArray())
    select(keep.ifBlank { items.firstOrNull()?.name.orEmpty() })
}

/** Select by name, adding it if the list has never been fetched or does not have it. */
internal fun ComboBox<ModelInfo>.select(name: String) {
    if (name.isBlank()) return
    val item = items().firstOrNull { it.name == name }
        ?: ModelInfo(name, emptyList()).also { (model as DefaultComboBoxModel).addElement(it) }
    selectedItem = item
    editor?.item = item
}

/**
 * A probe must not freeze the settings dialog while a dead port times out, and
 * the answer has to come back at `any()` modality or it sits in the queue until
 * the dialog the user is looking at closes.
 */
private fun <T> offEdt(work: () -> T, ok: (T) -> Unit, fail: (String) -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val outcome = runCatching(work)
        ApplicationManager.getApplication().invokeLater({
            outcome.fold(ok) { fail(it.message ?: it::class.java.simpleName) }
        }, ModalityState.any())
    }
}

/** The DSL binds a non-null String; the state's fields are nullable strings. */
private fun kotlin.reflect.KMutableProperty0<String?>.nonNull(default: String = "") =
    MutableProperty(getter = { get() ?: default }, setter = { set(it) })
