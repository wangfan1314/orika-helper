package com.github.wangfan1314.orikahelper.model

import com.intellij.psi.PsiElement

/**
 * 映射调用数据类
 * 表示调用链路中的一个节点
 */
data class MappingCall(
    val methodName: String,
    val className: String,
    val location: String, // 文件名:行号
    val callType: String, // "ORIKA_MAPPING", "METHOD_CALL", "CONSTRUCTOR_CALL"
    val psiElement: PsiElement? = null // PSI元素引用，用于代码跳转
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingCall) return false
        return methodName == other.methodName && 
               className == other.className && 
               location == other.location
    }

    override fun hashCode(): Int {
        return 31 * (31 * methodName.hashCode() + className.hashCode()) + location.hashCode()
    }
}
