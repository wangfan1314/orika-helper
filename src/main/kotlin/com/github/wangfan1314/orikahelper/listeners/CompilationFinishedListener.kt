package com.github.wangfan1314.orikahelper.listeners

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

/**
 * 编译完成后的监听器
 * 编译成功后自动打开Orika检查结果Tool Window
 */
class CompilationFinishedListener(private val project: Project) : CompilationStatusListener {
    
    override fun compilationFinished(
        aborted: Boolean,
        errors: Int,
        warnings: Int,
        context: CompileContext
    ) {
        // 如果编译成功（没有错误且没有被中止）
        if (!aborted && errors == 0) {
            SwingUtilities.invokeLater {
                // 打开Orika检查结果Tool Window
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow("Orika检查结果")
                
                toolWindow?.show()
            }
        }
    }
}

