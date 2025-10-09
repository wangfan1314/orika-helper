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
        if (methodName != "map") {
            return false
        }
        
        // 检查方法调用的上下文，确保是Orika的map方法
        val method = expression.resolveMethod()
        if (method != null) {
            val containingClass = method.containingClass
            if (containingClass != null) {
                val className = containingClass.qualifiedName
                // 检查是否是Orika相关的类
                if (className?.contains("orika") == true || 
                    className?.contains("Mapper") == true ||
                    className?.contains("Mapping") == true) {
                    return true
                }
            }
        }
        
        // 检查调用链，如 mapperFactory.getMapperFacade().map()
        val methodCall = expression.methodExpression
        if (methodCall is PsiReferenceExpression) {
            val qualifier = methodCall.qualifierExpression
            if (qualifier is PsiMethodCallExpression) {
                val qualifierMethodName = qualifier.methodExpression.referenceName
                if (qualifierMethodName == "getMapperFacade") {
                    // 进一步检查getMapperFacade的调用者
                    val qualifierQualifier = qualifier.methodExpression.qualifierExpression
                    if (qualifierQualifier is PsiReferenceExpression) {
                        val qualifierQualifierName = qualifierQualifier.referenceName
                        if (qualifierQualifierName?.contains("mapperFactory") == true ||
                            qualifierQualifierName?.contains("MapperFactory") == true) {
                            return true
                        }
                    }
                }
            }
        }
        
        return false
    }
}
