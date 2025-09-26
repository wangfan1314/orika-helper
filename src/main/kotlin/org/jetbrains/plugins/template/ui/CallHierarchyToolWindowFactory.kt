package org.jetbrains.plugins.template.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 调用层次结构工具窗口工厂
 */
class CallHierarchyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val callHierarchyToolWindow = CallHierarchyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(
            callHierarchyToolWindow.createContent(),
            "Orika映射调用链",
            false
        )
        
        // 存储CallHierarchyToolWindow实例以便后续访问
        content.putUserData(CALL_HIERARCHY_TOOL_WINDOW_KEY, callHierarchyToolWindow)
        
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        val CALL_HIERARCHY_TOOL_WINDOW_KEY = Key.create<CallHierarchyToolWindow>("CallHierarchyToolWindow")
    }
}
