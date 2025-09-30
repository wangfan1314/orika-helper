package com.github.wangfan1314.orikahelper.callhierarchy.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 调用链路工具窗口工厂
 */
class CallHierarchyTreeWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val callHierarchyTreeWindow = CallHierarchyTreeWindow(project)
        val content = ContentFactory.getInstance().createContent(
            callHierarchyTreeWindow.createContent(),
            "调用链路",
            false
        )
        
        // 将CallHierarchyTreeWindow实例存储在content中，以便后续访问
        content.putUserData(CALL_HIERARCHY_TREE_WINDOW_KEY, callHierarchyTreeWindow)
        
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        val CALL_HIERARCHY_TREE_WINDOW_KEY: Key<CallHierarchyTreeWindow> = Key.create("CallHierarchyTreeWindow")
    }
}
