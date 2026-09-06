package io.tanvoid0.codecraft

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.ui.ReviewerConfigurable

/**
 * The settings page is where the model is chosen, and the choice has to
 * survive a restart — which is exactly the kind of thing that quietly stops
 * working when a field is rewired. Building the real panel and applying it is
 * cheap enough to pin here rather than in a sandbox IDE nobody reruns.
 */
class ReviewerConfigurableTest : BasePlatformTestCase() {

    private val state get() = ReviewerSettings.get().state
    private var saved: String? = null

    override fun setUp() {
        super.setUp()
        saved = state.ollamaModel
    }

    override fun tearDown() {
        try {
            state.ollamaModel = saved
        } finally {
            super.tearDown()
        }
    }

    private fun panelWith(model: String): Pair<ReviewerConfigurable, ComboBox<*>> {
        state.ollamaModel = model
        val configurable = ReviewerConfigurable()
        val panel = configurable.createComponent()!!
        // The model dropdown is the first combo on the page; the agent
        // platform's read-only one comes after it.
        return configurable to UIUtil.findComponentOfType(panel, ComboBox::class.java)!!
    }

    fun testTheSavedModelIsSelectedWithNoServerToConfirmIt() {
        val (_, combo) = panelWith("qwen2.5-coder:7b")

        // Nothing has been fetched — the name persisted in trainer-reviewers.xml
        // is the whole list, and it is what shows.
        assertEquals("qwen2.5-coder:7b", combo.selectedItem.toString())
        assertEquals(1, combo.model.size)
    }

    fun testTheFieldIsEditableAndSearchable() {
        val (_, combo) = panelWith("qwen2.5-coder")

        assertTrue("a model can be typed before it is pulled", combo.isEditable)
        assertFalse("the platform popup is what carries speed search", combo.isSwingPopup)
    }

    /** Typed and never Entered still has to save, or the choice is lost on OK. */
    fun testAModelTypedButNotCommittedIsStillApplied() {
        val (configurable, combo) = panelWith("qwen2.5-coder")

        combo.editor.item = "deepseek-coder:33b"
        configurable.apply()

        assertEquals("deepseek-coder:33b", state.ollamaModel)
    }

    fun testPickingFromTheListApplies() {
        val (configurable, combo) = panelWith("qwen2.5-coder")

        @Suppress("UNCHECKED_CAST")
        (combo as ComboBox<ModelInfo>).model.let { it as javax.swing.DefaultComboBoxModel<ModelInfo> }
            .addElement(ModelInfo("llama3.2:3b", listOf("3.2B", "Q4_K_M")))
        combo.selectedItem = combo.model.getElementAt(1)
        configurable.apply()

        // The metadata is for reading; only the name is ever persisted.
        assertEquals("llama3.2:3b", state.ollamaModel)
    }
}
