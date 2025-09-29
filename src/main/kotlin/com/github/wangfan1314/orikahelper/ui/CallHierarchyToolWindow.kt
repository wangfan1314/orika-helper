package com.github.wangfan1314.orikahelper.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.pom.Navigatable
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtilBase
import com.github.wangfan1314.orikahelper.model.MappingCall
import com.github.wangfan1314.orikahelper.model.MappingRelation
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

/**
 * 调用层次结构工具窗口
 * 类似于IDEA原生的Call Hierarchy窗口
 */
class CallHierarchyToolWindow(private val project: Project) {

    private val tree: Tree
    private val rootNode: DefaultMutableTreeNode
    private val treeModel: DefaultTreeModel
    
    init {
        rootNode = DefaultMutableTreeNode("Orika映射调用链")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel)
        tree.cellRenderer = CallHierarchyTreeCellRenderer()
        tree.isRootVisible = true
        tree.showsRootHandles = true
        
        // 添加双击事件监听器，支持代码跳转
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y)
                    if (path != null) {
                        handleNodeDoubleClick(path)
                    }
                }
            }
        })
    }

    /**
     * 创建工具窗口内容
     */
    fun createContent(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // 添加工具栏
        val toolbar = createToolBar()
        panel.add(toolbar, BorderLayout.NORTH)
        
        // 添加树形视图
        val scrollPane = JBScrollPane(tree)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }

    /**
     * 创建工具栏
     */
    private fun createToolBar(): JToolBar {
        val toolbar = JToolBar()
        toolbar.isFloatable = false
        
        // 刷新按钮
        val refreshButton = JButton("刷新", AllIcons.Actions.Refresh)
        refreshButton.addActionListener {
            refreshTree()
        }
        toolbar.add(refreshButton)
        
        // 展开全部按钮
        val expandButton = JButton("展开全部", AllIcons.Actions.Expandall)
        expandButton.addActionListener {
            expandAllNodes()
        }
        toolbar.add(expandButton)
        
        // 折叠全部按钮
        val collapseButton = JButton("折叠全部", AllIcons.Actions.Collapseall)
        collapseButton.addActionListener {
            collapseAllNodes()
        }
        toolbar.add(collapseButton)
        
        return toolbar
    }

    /**
     * 显示映射调用链
     */
    fun showCallHierarchy(
        fieldName: String, 
        className: String,
        mappingRelations: List<MappingRelation>,
        callChain: List<MappingCall>
    ) {
        // 清空现有内容
        rootNode.removeAllChildren()
        
        // 设置根节点信息
        rootNode.userObject = "字段: $className.$fieldName"
        
        if (callChain.isNotEmpty()) {
            val mappingNode = DefaultMutableTreeNode("映射关系")
            rootNode.add(mappingNode)
            
            // 构建调用链层次结构
            buildCallHierarchy(mappingNode, callChain)
        }
        
        // 刷新树形视图
        treeModel.nodeStructureChanged(rootNode)
        expandAllNodes()
    }

    /**
     * 处理节点双击事件
     */
    private fun handleNodeDoubleClick(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val nodeData = node.userObject
        
        when (nodeData) {
            is CallNodeData -> {
                // 跳转到调用位置
                val psiElement = nodeData.call.psiElement
                if (psiElement != null && psiElement is Navigatable) {
                    if (psiElement.canNavigate()) {
                        psiElement.navigate(true)
                    }
                }
            }
            is MappingRelationNodeData -> {
                // 可以扩展支持映射关系的跳转
            }
        }
    }

    /**
     * 构建调用链层次结构（智能识别数据流转链路）
     */
    private fun buildCallHierarchy(parentNode: DefaultMutableTreeNode, callChain: List<MappingCall>) {
        // 按行号排序调用链
        val sortedCalls = callChain.sortedBy { call ->
            val lineNumber = call.location.substringAfterLast(':').toIntOrNull() ?: 0
            lineNumber
        }
        
        // 尝试识别数据流转链路
        val mappingChains = identifyMappingChains(sortedCalls)
        
        if (mappingChains.isNotEmpty()) {
            // 如果识别出了映射链路，按链路显示
            for ((chainIndex, chain) in mappingChains.withIndex()) {
                if (chain.size == 1) {
                    // 单个调用直接添加
                    val callNode = DefaultMutableTreeNode(CallNodeData(chain.first()))
                    parentNode.add(callNode)
                } else {
                    // 多个调用形成链路
                    val chainNode = DefaultMutableTreeNode("映射链路 ${chainIndex + 1}")
                    parentNode.add(chainNode)
                    
                    // 构建链式层次结构
                    var currentParent = chainNode
                    for (call in chain) {
                        val callNode = DefaultMutableTreeNode(CallNodeData(call))
                        currentParent.add(callNode)
                        currentParent = callNode
                    }
                }
            }
        } else {
            // 如果没有识别出链路，按方法分组显示
            val callsByMethod = sortedCalls.groupBy { call ->
                "${call.className}.${call.methodName}"
            }
            
            for ((methodSignature, methodCalls) in callsByMethod) {
                if (methodCalls.size == 1) {
                    val callNode = DefaultMutableTreeNode(CallNodeData(methodCalls.first()))
                    parentNode.add(callNode)
                } else {
                    val methodNode = DefaultMutableTreeNode("方法: $methodSignature")
                    parentNode.add(methodNode)
                    
                    for (call in methodCalls) {
                        val callNode = DefaultMutableTreeNode(CallNodeData(call))
                        methodNode.add(callNode)
                    }
                }
            }
        }
    }
    
    /**
     * 识别映射链路（基于类型流转分析）
     */
    private fun identifyMappingChains(calls: List<MappingCall>): List<List<MappingCall>> {
        val chains = mutableListOf<List<MappingCall>>()
        val processedCalls = mutableSetOf<MappingCall>()
        
        for (call in calls) {
            if (call in processedCalls) continue
            
            val chain = mutableListOf<MappingCall>()
            chain.add(call)
            processedCalls.add(call)
            
            // 查找可能的链式调用
            val callTypes = extractTypesFromCall(call)
            if (callTypes != null) {
                val (sourceType, targetType) = callTypes
                
                // 查找以targetType为源类型的后续调用
                var currentTargetType = targetType
                for (nextCall in calls) {
                    if (nextCall in processedCalls) continue
                    
                    val nextCallTypes = extractTypesFromCall(nextCall)
                    if (nextCallTypes != null && nextCallTypes.first == currentTargetType) {
                        chain.add(nextCall)
                        processedCalls.add(nextCall)
                        currentTargetType = nextCallTypes.second
                        break // 只找直接的下一个调用
                    }
                }
            }
            
            chains.add(chain)
        }
        
        return chains
    }
    
    /**
     * 从映射调用中提取源类型和目标类型
     */
    private fun extractTypesFromCall(call: MappingCall): Pair<String, String>? {
        val psiElement = call.psiElement
        if (psiElement is PsiMethodCallExpression) {
            try {
                val args = psiElement.argumentList.expressions
                if (args.size >= 2) {
                    val sourceType = extractTypeFromCall(args[0])
                    val targetType = extractTypeFromCall(args[1])
                    
                    if (sourceType != null && targetType != null) {
                        return Pair(sourceType, targetType)
                    }
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return null
    }

    /**
     * 查找映射关系的所有相关调用
     */
    private fun findAllCallsForMapping(relation: MappingRelation, allCalls: List<MappingCall>): List<MappingCall> {
        return allCalls.filter { call ->
            isCallRelatedToMapping(call, relation)
        }
    }

    /**
     * 查找与映射关系相关的调用链路
     */
    private fun findRelatedCallsForMapping(relation: MappingRelation, allCalls: List<MappingCall>): List<MappingCall> {
        // 简化策略：为每个映射关系至少分配一个调用
        val relatedCalls = allCalls.filter { call ->
            isCallRelatedToMapping(call, relation)
        }
        
        // 如果没有匹配的调用，尝试基于索引分配（临时方案）
        if (relatedCalls.isEmpty() && allCalls.isNotEmpty()) {
            // 获取映射关系的索引，为每个映射关系分配对应的调用
            val relationIndex = getRootNode().children().toList().indexOf(
                DefaultMutableTreeNode("映射关系").also { mappingNode ->
                    // 这里需要找到映射关系的索引
                }
            )
            
            // 简单的分配策略：按顺序分配调用
            val availableCalls = allCalls.toMutableList()
            if (availableCalls.isNotEmpty()) {
                // 移除第一个调用并返回
                return listOf(availableCalls.removeFirstOrNull() ?: return emptyList())
            }
        }
        
        return relatedCalls
    }
    
    private fun getRootNode(): DefaultMutableTreeNode = rootNode

    /**
     * 判断调用是否与映射关系相关
     */
    private fun isCallRelatedToMapping(call: MappingCall, relation: MappingRelation): Boolean {
        // 检查PSI元素是否与映射关系相关
        val psiElement = call.psiElement
        if (psiElement is PsiMethodCallExpression) {
            try {
                val args = psiElement.argumentList.expressions
                if (args.size >= 2) {
                    val sourceType = extractTypeFromCall(args[0])
                    val targetType = extractTypeFromCall(args[1])
                    
                    // 检查是否匹配当前映射关系（精确匹配）
                    return (sourceType == relation.sourceClass && targetType == relation.targetClass)
                }
            } catch (e: Exception) {
                // 如果PSI分析失败，返回false
            }
        }
        
        return false
    }
    
    /**
     * 从调用参数中提取类型
     */
    private fun extractTypeFromCall(expression: PsiExpression): String? {
        return when (expression) {
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiVariable -> {
                        var type = resolved.type.canonicalText
                        if (type.contains('<')) {
                            type = type.substringBefore('<')
                        }
                        type
                    }
                    is PsiClass -> resolved.qualifiedName
                    else -> null
                }
            }
            is PsiClassObjectAccessExpression -> {
                var type = expression.operand.type.canonicalText
                if (type.contains('<')) {
                    type = type.substringBefore('<')
                }
                type
            }
            else -> {
                var type = expression.type?.canonicalText
                if (type?.contains('<') == true) {
                    type = type.substringBefore('<')
                }
                type
            }
        }
    }

    /**
     * 获取未匹配的调用链路
     */
    private fun getUnmatchedCalls(mappingRelations: List<MappingRelation>, allCalls: List<MappingCall>): List<MappingCall> {
        val matchedCalls = mutableSetOf<MappingCall>()
        
        // 收集所有已匹配的调用
        for (relation in mappingRelations) {
            val relatedCalls = findRelatedCallsForMapping(relation, allCalls)
            matchedCalls.addAll(relatedCalls)
        }
        
        // 返回未匹配的调用
        return allCalls.filter { it !in matchedCalls }
    }

    /**
     * 按调用层次分组调用
     */
    private fun groupCallsByHierarchy(calls: List<MappingCall>): Map<String, List<MappingCall>> {
        return calls.groupBy { "${it.className}.${it.methodName}" }
    }

    /**
     * 添加调用节点
     */
    private fun addCallNodes(parentNode: DefaultMutableTreeNode, groupedCalls: Map<String, List<MappingCall>>) {
        for ((methodSignature, calls) in groupedCalls) {
            val methodNode = DefaultMutableTreeNode(methodSignature)
            parentNode.add(methodNode)
            
            for (call in calls) {
                val callNode = DefaultMutableTreeNode(CallNodeData(call))
                methodNode.add(callNode)
            }
        }
    }

    /**
     * 刷新树形视图
     */
    private fun refreshTree() {
        treeModel.reload()
    }

    /**
     * 展开所有节点
     */
    private fun expandAllNodes() {
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    /**
     * 折叠所有节点
     */
    private fun collapseAllNodes() {
        for (i in tree.rowCount - 1 downTo 0) {
            tree.collapseRow(i)
        }
    }

    /**
     * 映射关系节点数据
     */
    data class MappingRelationNodeData(val relation: MappingRelation) {
        override fun toString(): String {
            return "${relation.sourceClass}.${relation.sourceField} → ${relation.targetClass}.${relation.targetField} (${relation.mappingType})"
        }
    }

    /**
     * 调用节点数据
     */
    data class CallNodeData(val call: MappingCall) {
        override fun toString(): String {
            return "${call.className}.${call.methodName} (${call.location}) [${call.callType}]"
        }
    }

    /**
     * 自定义树形单元格渲染器
     */
    private class CallHierarchyTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            
            if (value is DefaultMutableTreeNode) {
                when (val nodeData = value.userObject) {
                    is MappingRelationNodeData -> {
                        icon = AllIcons.Nodes.Related
                        text = nodeData.toString()
                    }
                    is CallNodeData -> {
                        when (nodeData.call.callType) {
                            "ORIKA_MAPPING" -> icon = AllIcons.Nodes.Method
                            "METHOD_CALL" -> icon = AllIcons.Nodes.Function
                            else -> icon = AllIcons.Nodes.Method
                        }
                        text = nodeData.toString()
                    }
                    is String -> {
                        when (nodeData) {
                            "映射关系" -> icon = AllIcons.Nodes.Interface
                            "调用链路" -> icon = AllIcons.Hierarchy.Class
                            else -> icon = AllIcons.Nodes.Folder
                        }
                    }
                }
            }
            
            return this
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Orika映射调用链"
        
        /**
         * 显示或激活工具窗口
         */
        fun showToolWindow(project: Project): CallHierarchyToolWindow? {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
            
            if (toolWindow != null) {
                toolWindow.activate(null)
                // 返回现有的CallHierarchyToolWindow实例
                val content = toolWindow.contentManager.contents.firstOrNull()
                return content?.getUserData(CallHierarchyToolWindowKey)
            }
            
            return null
        }
    }
}

/**
 * 用于存储CallHierarchyToolWindow实例的Key
 */
private val CallHierarchyToolWindowKey = com.intellij.openapi.util.Key.create<CallHierarchyToolWindow>("CallHierarchyToolWindow")
