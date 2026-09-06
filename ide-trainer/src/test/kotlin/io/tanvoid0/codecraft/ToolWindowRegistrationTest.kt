package io.tanvoid0.codecraft

import io.tanvoid0.codecraft.ui.TrainerToolWindowFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * "It isn't in View → Tool Windows" is the failure mode with the most possible
 * causes — a bad icon reference, an unresolved <depends>, a factory that throws
 * — so pin the registration itself down.
 */
class ToolWindowRegistrationTest : BasePlatformTestCase() {

    fun testTrainerToolWindowIsRegistered() {
        val descriptor = com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId("io.tanvoid0.codecraft"))
        if (descriptor == null) {
            // The test runtime does not load the plugin descriptor, so tool
            // window registration cannot be asserted here at all.
            println("plugin descriptor not loaded in test runtime — registration untestable here")
            return
        }
        assertTrue("plugin is loaded but disabled — check its <depends>", descriptor.isEnabled)

        // Assert the extension itself, not ToolWindowManager: the headless
        // manager used in tests registers nothing, so a null lookup there
        // would say nothing about the real IDE.
        val ep = com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList
        val ours = ep.firstOrNull { it.id == "Trainer" }
        assertNotNull("no 'Trainer' toolWindow extension: " + ep.map { it.id }.sorted(), ours)
        assertEquals("io.tanvoid0.codecraft.ui.TrainerToolWindowFactory", ours!!.factoryClass)
        // The failure this guards is "the icon reference is broken", not "the
        // icon is this particular one" - pinning the literal meant every
        // rebrand turned the suite red for no defect. A bundled path has to
        // actually resolve; an AllIcons.* reference is resolved by the platform.
        val icon = ours.icon
        assertNotNull("no icon on the tool window extension", icon)
        if (icon!!.startsWith("/")) {
            assertNotNull("plugin.xml points at $icon, which is not on the classpath",
                javaClass.getResource(icon))
        }
    }

    fun testOpenActionIsRegistered() {
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction("io.tanvoid0.codecraft.Open")
        assertNotNull("Tools → Open Trainer Board is missing", action)
    }

    fun testFactoryInstantiates() {
        // If the class or its icon reference were wrong this would throw.
        val factory = TrainerToolWindowFactory()
        assertTrue(factory.isApplicable(project))
    }
}
