package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.plugins.template.model.MappingRelation
import org.jetbrains.plugins.template.model.MappingCall

/**
 * 简化的映射分析器，专门用于调试递归映射问题
 */
@Service(Service.Level.PROJECT)
class SimpleMappingAnalyzer(private val project: Project) {

    /**
     * 查找所有相关的映射关系（简化版）
     */
    fun findAllMappings(selectedField: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        val fieldName = selectedField.name
        
        // 获取项目中所有的map调用
        val allMapCalls = findAllMapCalls()
        
        // 分析每个map调用，看是否涉及包含指定字段的类
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查源类是否有指定字段
                val sourceField = findFieldInClass(sourceClass, fieldName)
                val targetField = findFieldInClass(targetClass, fieldName)
                
                if (sourceField != null || targetField != null) {
                    relations.add(MappingRelation(
                        sourceClass = sourceClass,
                        sourceField = fieldName,
                        targetClass = targetClass,
                        targetField = fieldName,
                        mappingType = "ORIKA_MAP_CALL"
                    ))
                }
            }
        }
        
        return relations.distinct()
    }

    /**
     * 查找所有相关的映射调用
     */
    fun findAllMappingCalls(selectedField: PsiField): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        val fieldName = selectedField.name
        
        // 获取项目中所有的map调用
        val allMapCalls = findAllMapCalls()
        
        // 分析每个map调用，看是否涉及包含指定字段的类
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查是否涉及包含指定字段的类
                val sourceField = findFieldInClass(sourceClass, fieldName)
                val targetField = findFieldInClass(targetClass, fieldName)
                
                if (sourceField != null || targetField != null) {
                    val containingMethod = PsiTreeUtil.getParentOfType(mapCall.psiCall, PsiMethod::class.java)
                    if (containingMethod != null) {
                        calls.add(MappingCall(
                            methodName = containingMethod.name,
                            className = containingMethod.containingClass?.qualifiedName ?: "Unknown",
                            location = "${containingMethod.containingFile?.name}:${getLineNumber(mapCall.psiCall)}",
                            callType = "ORIKA_MAPPING",
                            psiElement = mapCall.psiCall
                        ))
                    }
                }
            }
        }
        
        return calls.distinct()
    }

    /**
     * 查找项目中所有的map方法调用
     */
    private fun findAllMapCalls(): List<MapCallInfo> {
        val mapCalls = mutableListOf<MapCallInfo>()
        
        // 搜索项目中的所有Java文件
        val javaFiles = mutableListOf<PsiJavaFile>()
        FileTypeIndex.processFiles(
            JavaFileType.INSTANCE,
            { virtualFile ->
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is PsiJavaFile) {
                    javaFiles.add(psiFile)
                }
                true
            },
            GlobalSearchScope.projectScope(project)
        )
        
        for (javaFile in javaFiles) {
            javaFile.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    
                    val methodName = expression.methodExpression.referenceName
                    if (methodName == "map") {
                        val args = expression.argumentList.expressions
                        if (args.size >= 2) {
                            val sourceType = extractTypeFromExpression(args[0])
                            val targetType = extractTypeFromExpression(args[1])
                            
                            mapCalls.add(MapCallInfo(
                                psiCall = expression,
                                sourceType = sourceType,
                                targetType = targetType
                            ))
                        }
                    }
                }
            })
        }
        
        return mapCalls
    }

    /**
     * 提取表达式的类型信息
     */
    private fun extractTypeFromExpression(expression: PsiExpression): String? {
        return when (expression) {
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiVariable -> {
                        var type = resolved.type.canonicalText
                        // 去掉泛型参数
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
     * 在指定类中查找字段
     */
    private fun findFieldInClass(className: String, fieldName: String): PsiField? {
        try {
            val classes = JavaPsiFacade.getInstance(project).findClasses(className, GlobalSearchScope.projectScope(project))
            for (clazz in classes) {
                val field = clazz.findFieldByName(fieldName, true)
                if (field != null) {
                    return field
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        return null
    }

    /**
     * 获取PSI元素的行号
     */
    private fun getLineNumber(element: PsiElement): Int {
        return try {
            val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            document?.getLineNumber(element.textOffset)?.plus(1) ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Map调用信息数据类
     */
    private data class MapCallInfo(
        val psiCall: PsiMethodCallExpression,
        val sourceType: String?,
        val targetType: String?
    )
}
