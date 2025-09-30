package org.jetbrains.plugins.template.callhierarchy.model

import com.intellij.psi.PsiElement

/**
 * 调用层级节点数据类
 * 用于表示调用链路中的一个节点
 */
data class CallHierarchyNode(
    val className: String,
    val methodName: String,
    val displayName: String, // 显示名称，格式为"类名.方法名"
    val location: String, // 文件名:行号
    val nodeType: CallHierarchyNodeType,
    val psiElement: PsiElement? = null, // PSI元素引用，用于代码跳转
    val children: MutableList<CallHierarchyNode> = mutableListOf(), // 子调用节点
    val parent: CallHierarchyNode? = null // 父调用节点
) {
    /**
     * 获取完整的显示名称
     */
    fun getFullDisplayName(): String {
        return "$className.$methodName"
    }
    
    /**
     * 添加子节点
     */
    fun addChild(child: CallHierarchyNode) {
        children.add(child)
    }
    
    /**
     * 获取调用深度
     */
    fun getDepth(): Int {
        var depth = 0
        var current = parent
        while (current != null) {
            depth++
            current = current.parent
        }
        return depth
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallHierarchyNode) return false
        return className == other.className && 
               methodName == other.methodName && 
               location == other.location
    }

    override fun hashCode(): Int {
        return 31 * (31 * className.hashCode() + methodName.hashCode()) + location.hashCode()
    }
}

/**
 * 调用层级节点类型
 */
enum class CallHierarchyNodeType {
    /**
     * Controller层调用
     */
    CONTROLLER,
    
    /**
     * Service层调用
     */
    SERVICE,
    
    /**
     * Repository/DAO层调用
     */
    REPOSITORY,
    
    /**
     * 普通方法调用
     */
    METHOD_CALL,
    
    /**
     * Orika映射调用
     */
    ORIKA_MAPPING,
    
    /**
     * 构造函数调用
     */
    CONSTRUCTOR_CALL,
    
    /**
     * 根节点
     */
    ROOT,
    
    /**
     * Getter方法调用
     */
    GETTER_METHOD,
    
    /**
     * Setter方法调用
     */
    SETTER_METHOD,
    
    /**
     * 包含Orika映射的方法调用
     */
    ORIKA_METHOD
}
