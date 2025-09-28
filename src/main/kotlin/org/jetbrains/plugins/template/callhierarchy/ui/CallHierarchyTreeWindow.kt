package org.jetbrains.plugins.template.callhierarchy.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode
import org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNodeType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 调用链路工具窗口
 * 类似于IDEA原生的Call Hierarchy窗口，专门用于显示调用链路
 */
class CallHierarchyTreeWindow(private val project: Project) {

    private val tree: Tree
    private val rootNode: DefaultMutableTreeNode
    private val treeModel: DefaultTreeModel
    
    init {
        rootNode = DefaultMutableTreeNode("调用链路")
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
     * 显示调用链路
     */
    fun showCallHierarchy(fieldName: String, className: String, hierarchyRoot: CallHierarchyNode) {
        // 清空现有内容
        rootNode.removeAllChildren()
        
        // 设置根节点信息
        rootNode.userObject = "调用链路: $className.$fieldName"
        
        // 构建调用链路树
        buildHierarchyTree(rootNode, hierarchyRoot)
        
        // 刷新树形视图
        treeModel.nodeStructureChanged(rootNode)
        expandAllNodes()
    }
    
    /**
     * 构建调用链路树
     */
    private fun buildHierarchyTree(parentTreeNode: DefaultMutableTreeNode, hierarchyNode: CallHierarchyNode) {
        val treeNode = DefaultMutableTreeNode(CallHierarchyNodeData(hierarchyNode))
        parentTreeNode.add(treeNode)
        
        // 递归构建子节点
        for (child in hierarchyNode.children) {
            buildHierarchyTree(treeNode, child)
        }
    }

    /**
     * 处理节点双击事件
     */
    private fun handleNodeDoubleClick(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val nodeData = node.userObject
        
        when (nodeData) {
            is CallHierarchyNodeData -> {
                // 跳转到调用位置
                val psiElement = nodeData.hierarchyNode.psiElement
                if (psiElement != null && psiElement is Navigatable) {
                    if (psiElement.canNavigate()) {
                        psiElement.navigate(true)
                    }
                }
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
     * 调用链路节点数据
     */
    data class CallHierarchyNodeData(val hierarchyNode: CallHierarchyNode) {
        override fun toString(): String {
            return hierarchyNode.displayName
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
                    is CallHierarchyNodeData -> {
                        // 根据节点类型设置不同的图标
                        when (nodeData.hierarchyNode.nodeType) {
                            CallHierarchyNodeType.CONTROLLER -> {
                                icon = AllIcons.Nodes.Controller
                                text = nodeData.hierarchyNode.getFullDisplayName()
                            }
                            CallHierarchyNodeType.SERVICE -> {
                                icon = AllIcons.Nodes.Services
                                text = nodeData.hierarchyNode.getFullDisplayName()
                            }
                            CallHierarchyNodeType.REPOSITORY -> {
                                icon = AllIcons.Nodes.DataTables
                                text = nodeData.hierarchyNode.getFullDisplayName()
                            }
                            CallHierarchyNodeType.ORIKA_MAPPING -> {
                                icon = AllIcons.Nodes.Related
                                text = nodeData.hierarchyNode.displayName
                            }
                            CallHierarchyNodeType.METHOD_CALL -> {
                                icon = AllIcons.Nodes.Method
                                text = nodeData.hierarchyNode.getFullDisplayName()
                            }
                            CallHierarchyNodeType.CONSTRUCTOR_CALL -> {
                                icon = AllIcons.Nodes.ClassInitializer
                                text = nodeData.hierarchyNode.getFullDisplayName()
                            }
                            CallHierarchyNodeType.ROOT -> {
                                icon = AllIcons.Nodes.Field
                                text = nodeData.hierarchyNode.displayName
                            }
                            CallHierarchyNodeType.GETTER_METHOD -> {
                                icon = AllIcons.Nodes.Property
                                text = "📍 ${nodeData.hierarchyNode.getFullDisplayName()}"
                            }
                            CallHierarchyNodeType.SETTER_METHOD -> {
                                icon = AllIcons.Nodes.Property
                                text = "📍 ${nodeData.hierarchyNode.getFullDisplayName()}"
                            }
                            CallHierarchyNodeType.ORIKA_METHOD -> {
                                icon = AllIcons.Nodes.Related
                                text = nodeData.hierarchyNode.displayName
                            }
                        }
                    }
                    is String -> {
                        icon = AllIcons.Hierarchy.Class
                        text = nodeData
                    }
                }
            }
            
            return this
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "调用链路"
        
        /**
         * 显示或激活工具窗口
         */
        fun showToolWindow(project: Project): CallHierarchyTreeWindow? {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
            
            if (toolWindow != null) {
                toolWindow.activate(null)
                // 返回现有的CallHierarchyTreeWindow实例
                val content = toolWindow.contentManager.contents.firstOrNull()
                return content?.getUserData(CallHierarchyTreeWindowKey)
            }
            
            return null
        }
    }
}

/**
 * 用于存储CallHierarchyTreeWindow实例的Key
 */
private val CallHierarchyTreeWindowKey = com.intellij.openapi.util.Key.create<CallHierarchyTreeWindow>("CallHierarchyTreeWindow")
