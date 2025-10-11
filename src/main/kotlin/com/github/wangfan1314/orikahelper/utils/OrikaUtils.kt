package com.github.wangfan1314.orikahelper.utils

import com.intellij.psi.*

/**
 * Orika相关的工具类
 * 提供Orika映射检测和分析的公共方法
 */
object OrikaUtils {

    /**
     * 检查是否是Orika的map方法调用
     * 增强判断，不仅检查方法名，还检查调用上下文
     */
    fun isOrikaMapCall(expression: PsiMethodCallExpression): Boolean {
        val methodName = expression.methodExpression.referenceName
        if (methodName != "map" && methodName != "mapAsList" && methodName != "mapAsSet" && methodName != "mapAsArray") {
            return false
        }
        
        // 检查方法调用的上下文，确保是Orika的map方法
        val method = expression.resolveMethod()
        if (method != null) {
            val containingClass = method.containingClass
            if (containingClass != null) {
                val className = containingClass.qualifiedName ?: ""
                // 检查是否是Orika相关的类（不区分大小写）
                val lowerClassName = className.lowercase()
                if (lowerClassName.contains("orika") || 
                    lowerClassName.contains("mapperfacade") ||
                    className == "ma.glasnost.orika.MapperFacade" ||
                    className == "ma.glasnost.orika.MapperFactory") {
                    return true
                }
            }
        }
        
        // 检查调用链，如 mapperFactory.getMapperFacade().map()
        val methodCall = expression.methodExpression
        if (methodCall is PsiReferenceExpression) {
            val qualifier = methodCall.qualifierExpression
            
            // 情况1: xxx.getMapperFacade().map()
            if (qualifier is PsiMethodCallExpression) {
                val qualifierMethodName = qualifier.methodExpression.referenceName
                if (qualifierMethodName == "getMapperFacade") {
                    return true
                }
                
                // 检查是否返回 MapperFacade 类型
                val resolvedMethod = qualifier.resolveMethod()
                val returnType = resolvedMethod?.returnType?.canonicalText
                if (returnType?.contains("MapperFacade") == true || returnType?.contains("orika") == true) {
                    return true
                }
            }
            
            // 情况2: mapperFacade.map() - 直接检查变量类型
            if (qualifier is PsiReferenceExpression) {
                val resolved = qualifier.resolve()
                if (resolved is PsiVariable) {
                    val type = resolved.type.canonicalText
                    if (type.contains("MapperFacade") || type.contains("orika")) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
}
