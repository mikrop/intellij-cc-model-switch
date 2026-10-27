package com.github.mikrop.intellijccmodelswitch

import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

private const val WIDGET_ID = "ccModelSwitch.statusBar"

class ModelSwitchWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID

    override fun getDisplayName() = "Claude Code Model Switch"

    override fun isAvailable(project: Project) = true

    override fun createWidget(project: Project): StatusBarWidget = ModelSwitchWidget()

    override fun canBeEnabledOn(statusBar: StatusBar) = true
}

// Shows the preset in use; a click opens the quick actions
private class ModelSwitchWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val service = service<ModelSwitchService>()
    private var statusBar: StatusBar? = null
    private val listener = Runnable { statusBar?.updateWidget(WIDGET_ID) }

    override fun ID() = WIDGET_ID

    override fun getPresentation() = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        service.addListener(listener)
        service.refresh()
    }

    override fun dispose() {
        service.removeListener(listener)
        statusBar = null
    }

    override fun getText() = "Claude: ${service.current?.label ?: "Not set"}"

    override fun getAlignment() = 0.5f

    override fun getTooltipText() =
        "Claude Code environment: ${service.current?.label ?: "unknown"}. Click to switch"

    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        showQuickActions(DataManager.getInstance().getDataContext(event.component))
    }
}

// Detect the current preset when the first project opens
class RefreshOnStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ProjectManager.getInstance().openProjects.size <= 1) {
            service<ModelSwitchService>().refresh()
        }
    }
}
