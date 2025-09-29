package com.github.wangfan1314.orikahelper.actions

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
import com.github.wangfan1314.orikahelper.services.OrikaMappingAnalyzer
import com.github.wangfan1314.orikahelper.ui.CallHierarchyToolWindow
import com.github.wangfan1314.orikahelper.ui.CallHierarchyToolWindowFactory
import com.github.wangfan1314.orikahelper.utils.FieldSelectionUtils

/**
 * Orika映射追踪Action
 * 处理快捷键触发的映射调用链分析
 */
class OrikaMappingTracerAction : AnAction() {

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
            showNotification(project, "所选字段不适合进行映射分析", NotificationType.WARNING)
            return
        }
        
        // 在后台线程中进行分析
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "分析Orika映射调用链...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "正在分析字段映射关系..."
                    indicator.fraction = 0.2
                    
                    val analyzer = project.getService(OrikaMappingAnalyzer::class.java)
                    val simpleAnalyzer = project.getService(com.github.wangfan1314.orikahelper.services.SimpleMappingAnalyzer::class.java)
                    
                    // 在ReadAction中进行PSI访问 - 使用简化分析器进行调试
                    val mappingRelations = ApplicationManager.getApplication().runReadAction<List<com.github.wangfan1314.orikahelper.model.MappingRelation>> {
                        indicator.text = "正在查找映射配置..."
                        indicator.fraction = 0.4
                        // 先尝试简化版本
                        val simpleRelations = simpleAnalyzer.findAllMappings(selectedField)
                        if (simpleRelations.isNotEmpty()) {
                            simpleRelations
                        } else {
                            // 如果简化版本没找到，使用原版本
                            analyzer.analyzeMappingRelations(selectedField)
                        }
                    }
                    
                    val callChain = ApplicationManager.getApplication().runReadAction<List<com.github.wangfan1314.orikahelper.model.MappingCall>> {
                        indicator.text = "正在构建调用链路..."
                        indicator.fraction = 0.7
                        // 先尝试简化版本
                        val simpleCalls = simpleAnalyzer.findAllMappingCalls(selectedField)
                        if (simpleCalls.isNotEmpty()) {
                            simpleCalls
                        } else {
                            // 如果简化版本没找到，使用原版本
                            analyzer.findCallHierarchy(selectedField)
                        }
                    }
                    
                    indicator.text = "正在显示结果..."
                    indicator.fraction = 0.9
                    
                    // 在EDT线程中更新UI
                    ApplicationManager.getApplication().invokeLater {
                        showResults(project, selectedField, mappingRelations, callChain)
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
        mappingRelations: List<com.github.wangfan1314.orikahelper.model.MappingRelation>,
        callChain: List<com.github.wangfan1314.orikahelper.model.MappingCall>
    ) {
        // 获取或创建工具窗口
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Orika映射调用链")
        
        if (toolWindow != null) {
            // 激活工具窗口
            toolWindow.activate(null)
            
            // 获取CallHierarchyToolWindow实例
            val content = toolWindow.contentManager.contents.firstOrNull()
            val callHierarchyToolWindow = content?.getUserData(CallHierarchyToolWindowFactory.CALL_HIERARCHY_TOOL_WINDOW_KEY)
            
            if (callHierarchyToolWindow != null) {
                // 显示分析结果
                val fieldInfo = FieldSelectionUtils.getFieldInfo(field)
                callHierarchyToolWindow.showCallHierarchy(
                    fieldInfo.name,
                    fieldInfo.className,
                    mappingRelations,
                    callChain
                )
                
                // 显示分析摘要
                val summary = buildAnalysisSummary(mappingRelations, callChain)
                showNotification(project, summary, NotificationType.INFORMATION)
            }
        } else {
            showNotification(project, "无法打开调用链分析窗口", NotificationType.ERROR)
        }
    }

    /**
     * 构建分析摘要
     */
    private fun buildAnalysisSummary(
        mappingRelations: List<com.github.wangfan1314.orikahelper.model.MappingRelation>,
        callChain: List<com.github.wangfan1314.orikahelper.model.MappingCall>
    ): String {
        val sb = StringBuilder()
        sb.append("映射分析完成!\n")
        sb.append("找到 ${mappingRelations.size} 个映射关系\n")
        sb.append("找到 ${callChain.size} 个调用节点")
        
        if (mappingRelations.isEmpty() && callChain.isEmpty()) {
            sb.append("\n未发现相关的Orika映射配置")
        }
        
        return sb.toString()
    }

    /**
     * 显示通知
     */
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        val notification = Notification(
            "Orika Mapping Tracer",
            "Orika映射追踪",
            message,
            type
        )
        Notifications.Bus.notify(notification, project)
    }
}
