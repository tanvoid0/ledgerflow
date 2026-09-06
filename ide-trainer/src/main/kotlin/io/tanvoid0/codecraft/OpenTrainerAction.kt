package io.tanvoid0.codecraft

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * A second way in. "View → Tool Windows → Trainer" is easy to miss and easy to
 * mistake for "the plugin did not install", so the same thing is on the Tools
 * menu and in Search Everywhere under "Codecraft".
 */
class OpenTrainerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Trainer")?.activate(null)
    }
}
