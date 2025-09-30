package org.jetbrains.plugins.template.callhierarchy.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiField
import org.jetbrains.plugins.template.callhierarchy.services.CallHierarchyAnalyzer
import org.jetbrains.plugins.template.callhierarchy.ui.CallHierarchyTreeWindowFactory
import org.jetbrains.plugins.template.utils.FieldSelectionUtils

/**
 * 显示调用链路Action
 * 处理快捷键触发的调用链路分析
 */
class ShowCallHierarchyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // 获取选中的字段
        val selectedField = FieldSelectionUtils.getSelectedField(editor, project)
        
        if (selectedField == null) {
            showNotification(project, "请将光标放置在字段上或选中字段", NotificationType.WARNING)
            return
        }
        
        // 检查字段是否适合进行映射分析
        if (!FieldSelectionUtils.isFieldMappingCandidate(selectedField)) {
            showNotification(project, "所选字段不适合进行调用链路分析", NotificationType.WARNING)
            return
        }
        
        // 在后台线程中进行分析
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "分析调用链路...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "正在分析字段调用链路..."
                    indicator.fraction = 0.2
                    
                    val analyzer = project.getService(CallHierarchyAnalyzer::class.java)
                    
                    // 在ReadAction中进行PSI访问
                    val callHierarchy = ApplicationManager.getApplication().runReadAction<org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode?> {
                        indicator.text = "正在查找调用关系..."
                        indicator.fraction = 0.6
                        analyzer.analyzeCallHierarchy(selectedField)
                    }
                    
                    indicator.text = "正在显示结果..."
                    indicator.fraction = 0.9
                    
                    // 在EDT线程中更新UI
                    ApplicationManager.getApplication().invokeLater {
                        if (callHierarchy != null) {
                            showResults(project, selectedField, callHierarchy)
                        } else {
                            showNotification(project, "未找到相关的调用链路", NotificationType.INFORMATION)
                        }
                    }
                    
                    indicator.fraction = 1.0
                    
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification(project, "分析过程中发生错误: ${ex.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        // 只有在Java项目中且有编辑器时才启用action
        e.presentation.isEnabled = project != null && editor != null
        
        if (project != null && editor != null) {
            val selectedField = FieldSelectionUtils.getSelectedField(editor, project)
            e.presentation.isVisible = selectedField != null
        }
    }

    /**
     * 显示分析结果
     */
    private fun showResults(
        project: Project, 
        field: PsiField, 
        callHierarchy: org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode
    ) {
        // 获取或创建工具窗口
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("调用链路")
        
        if (toolWindow != null) {
            // 激活工具窗口
            toolWindow.activate(null)
            
            // 获取CallHierarchyTreeWindow实例
            val content = toolWindow.contentManager.contents.firstOrNull()
            val callHierarchyTreeWindow = content?.getUserData(CallHierarchyTreeWindowFactory.CALL_HIERARCHY_TREE_WINDOW_KEY)
            
            if (callHierarchyTreeWindow != null) {
                // 显示分析结果
                val fieldInfo = FieldSelectionUtils.getFieldInfo(field)
                callHierarchyTreeWindow.showCallHierarchy(
                    fieldInfo.name,
                    fieldInfo.className,
                    callHierarchy
                )
                
                // 显示分析摘要
                val summary = buildAnalysisSummary(callHierarchy)
                showNotification(project, summary, NotificationType.INFORMATION)
            }
        } else {
            showNotification(project, "无法打开调用链路分析窗口", NotificationType.ERROR)
        }
    }

    /**
     * 构建分析摘要
     */
    private fun buildAnalysisSummary(callHierarchy: org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode): String {
        val totalNodes = countTotalNodes(callHierarchy)
        val maxDepth = calculateMaxDepth(callHierarchy)
        
        val sb = StringBuilder()
        sb.append("调用链路分析完成!\n")
        sb.append("找到 $totalNodes 个调用节点\n")
        sb.append("最大调用深度: $maxDepth 层")
        
        return sb.toString()
    }
    
    /**
     * 计算总节点数
     */
    private fun countTotalNodes(node: org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode): Int {
        var count = 1
        for (child in node.children) {
            count += countTotalNodes(child)
        }
        return count
    }
    
    /**
     * 计算最大深度
     */
    private fun calculateMaxDepth(node: org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode): Int {
        if (node.children.isEmpty()) {
            return 1
        }
        
        var maxChildDepth = 0
        for (child in node.children) {
            val childDepth = calculateMaxDepth(child)
            if (childDepth > maxChildDepth) {
                maxChildDepth = childDepth
            }
        }
        
        return 1 + maxChildDepth
    }

    /**
     * 显示通知
     */
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        val notification = Notification(
            "Orika Call Hierarchy",
            "调用链路追踪",
            message,
            type
        )
        Notifications.Bus.notify(notification, project)
    }
}
