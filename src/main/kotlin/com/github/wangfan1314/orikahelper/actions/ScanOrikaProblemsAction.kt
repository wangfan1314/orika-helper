package com.github.wangfan1314.orikahelper.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 扫描Orika问题的Action
 * 打开Orika检查结果Tool Window
 */
class ScanOrikaProblemsAction : AnAction("扫描Orika类型问题") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 打开Tool Window
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Orika检查结果")
        
        if (toolWindow != null) {
            toolWindow.show()
        }
    }
}

