package com.github.wangfan1314.orikahelper.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ide.highlighter.JavaFileType
import com.github.wangfan1314.orikahelper.model.MappingRelation
import com.github.wangfan1314.orikahelper.model.MappingCall

/**
 * 简化的映射分析器，专门用于调试递归映射问题
 */
@Service(Service.Level.PROJECT)
class SimpleMappingAnalyzer(private val project: Project) {
    
    // 父类关系缓存 - 提高性能
    private val parentClassCache = mutableMapOf<String, List<ParentFieldInfo>>()
    private val parentClassSearchLimit = 10  // 限制搜索数量，避免性能问题

    /**
     * 查找所有相关的映射关系（简化版，增加字段级别验证，支持子对象映射追踪）
     */
    fun findAllMappings(selectedField: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        val fieldName = selectedField.name
        val fieldClass = selectedField.containingClass?.qualifiedName ?: return relations
        
        // 获取项目中所有的map调用
        val allMapCalls = findAllMapCalls()
        
        // 1. 查找直接映射：当前类的字段在源类和目标类中都存在
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查源类和目标类是否都存在指定字段
                val sourceField = findFieldInClass(sourceClass, fieldName)
                val targetField = findFieldInClass(targetClass, fieldName)
                
                // 只有当源类和目标类都存在该字段时才建立映射关系
                if (sourceField != null && targetField != null) {
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
        
        // 2. 查找子对象映射：找到包含当前字段类的主类之间的映射
        val parentClassMappings = findParentClassMappings(fieldClass, allMapCalls)
        relations.addAll(parentClassMappings)
        
        return relations.distinct()
    }
    
    /**
     * 查找包含指定类作为字段的主类之间的映射关系
     * 例如：当分析AddressEntity时，找到UserEntity -> UserDTO的映射
     */
    private fun findParentClassMappings(childClass: String, allMapCalls: List<MapCallInfo>): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        
        // 1. 找到所有包含childClass作为字段的类
        val parentClasses = findClassesContainingFieldType(childClass)
        
        // 2. 对于每个映射调用，检查是否涉及包含childClass的父类
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查源类或目标类是否包含childClass类型的字段
                val sourceParents = parentClasses.filter { it.className == sourceClass }
                val targetParents = parentClasses.filter { it.className == targetClass }
                
                // 情况1: 源类包含childClass，需要检查目标类的对应字段
                for (sourceParent in sourceParents) {
                    val targetFieldInfo = findFieldTypeInClass(targetClass, sourceParent.fieldName)
                    if (targetFieldInfo != null) {
                        // 检查目标类的对应字段类型中是否也包含相同的子字段
                        if (hasMatchingSubField(childClass, targetFieldInfo)) {
                            relations.add(MappingRelation(
                                sourceClass = sourceClass,
                                sourceField = sourceParent.fieldName,
                                targetClass = targetClass,
                                targetField = sourceParent.fieldName,
                                mappingType = "ORIKA_MAP_CALL_PARENT"
                            ))
                        }
                    }
                }
                
                // 情况2: 目标类包含childClass，需要检查源类的对应字段
                for (targetParent in targetParents) {
                    val sourceFieldInfo = findFieldTypeInClass(sourceClass, targetParent.fieldName)
                    if (sourceFieldInfo != null) {
                        // 检查源类的对应字段类型中是否也包含相同的子字段
                        if (hasMatchingSubField(childClass, sourceFieldInfo)) {
                            relations.add(MappingRelation(
                                sourceClass = sourceClass,
                                sourceField = targetParent.fieldName,
                                targetClass = targetClass,
                                targetField = targetParent.fieldName,
                                mappingType = "ORIKA_MAP_CALL_PARENT"
                            ))
                        }
                    }
                }
            }
        }
        
        return relations.distinct()
    }
    
    /**
     * 在指定类中查找指定字段名的字段类型
     */
    private fun findFieldTypeInClass(className: String, fieldName: String): String? {
        try {
            val classes = JavaPsiFacade.getInstance(project).findClasses(className, GlobalSearchScope.projectScope(project))
            for (clazz in classes) {
                val field = clazz.findFieldByName(fieldName, true)
                if (field != null) {
                    return getSimpleTypeName(field.type)
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        return null
    }
    
    /**
     * 检查两个类型是否具有匹配的子字段
     * 即：检查childClass1和childClass2是否具有相同结构的字段
     */
    private fun hasMatchingSubField(childClass1: String, childClass2: String): Boolean {
        try {
            // 获取两个类的所有字段
            val class1Fields = getClassFields(childClass1)
            val class2Fields = getClassFields(childClass2)
            
            // 如果两个类至少有一个公共字段名，则认为它们匹配
            val commonFields = class1Fields.intersect(class2Fields)
            return commonFields.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 获取类的所有字段名
     */
    private fun getClassFields(className: String): Set<String> {
        val fieldNames = mutableSetOf<String>()
        try {
            val classes = JavaPsiFacade.getInstance(project).findClasses(className, GlobalSearchScope.projectScope(project))
            for (clazz in classes) {
                clazz.allFields.forEach { field ->
                    fieldNames.add(field.name)
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        return fieldNames
    }
    
    /**
     * 查找所有包含指定类型作为字段的类（高性能版本）
     * 返回：父类名称和字段名称的映射
     * 
     * 性能优化策略：
     * 1. 使用缓存避免重复搜索
     * 2. 使用类引用搜索而不是遍历所有文件
     * 3. 限制搜索数量
     */
    private fun findClassesContainingFieldType(fieldType: String): List<ParentFieldInfo> {
        // 1. 检查缓存
        parentClassCache[fieldType]?.let { return it }
        
        val result = mutableListOf<ParentFieldInfo>()
        
        try {
            // 2. 找到目标类
            val targetClass = JavaPsiFacade.getInstance(project).findClass(fieldType, GlobalSearchScope.allScope(project))
            if (targetClass == null) {
                parentClassCache[fieldType] = emptyList()
                return emptyList()
            }
            
            // 3. 找到所有引用该类的位置
            val classReferences = ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
            
            // 4. 只处理字段声明中的引用，并限制数量
            var count = 0
            for (reference in classReferences) {
                if (count >= parentClassSearchLimit) break
                
                val element = reference.element
                
                // 检查是否是字段声明中的引用
                val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
                if (field != null) {
                    // 验证这个字段确实是目标类型
                    val fieldTypeName = getSimpleTypeName(field.type)
                    if (fieldTypeName == fieldType) {
                        val containingClass = field.containingClass?.qualifiedName
                        if (containingClass != null && containingClass != fieldType) {
                            result.add(ParentFieldInfo(
                                className = containingClass,
                                fieldName = field.name,
                                fieldType = fieldTypeName
                            ))
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        
        // 5. 缓存结果
        parentClassCache[fieldType] = result
        return result
    }
    
    /**
     * 获取类型的简单名称（去掉泛型参数）
     */
    private fun getSimpleTypeName(type: PsiType): String {
        var typeName = type.canonicalText
        if (typeName.contains('<')) {
            typeName = typeName.substringBefore('<')
        }
        return typeName
    }
    
    /**
     * 父类字段信息
     */
    private data class ParentFieldInfo(
        val className: String,
        val fieldName: String,
        val fieldType: String
    )

    /**
     * 查找所有相关的映射调用（增加字段级别验证，支持子对象映射追踪）
     */
    fun findAllMappingCalls(selectedField: PsiField): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        val fieldName = selectedField.name
        val fieldClass = selectedField.containingClass?.qualifiedName ?: return calls
        
        // 获取项目中所有的map调用
        val allMapCalls = findAllMapCalls()
        
        // 1. 查找直接映射调用：源类和目标类都存在该字段
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查源类和目标类是否都存在指定字段
                val sourceField = findFieldInClass(sourceClass, fieldName)
                val targetField = findFieldInClass(targetClass, fieldName)
                
                // 只有当源类和目标类都存在该字段时才认为相关
                if (sourceField != null && targetField != null) {
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
        
        // 2. 查找子对象映射调用：找到包含当前字段类的主类之间的映射调用
        val parentClassCalls = findParentClassMappingCalls(fieldClass, allMapCalls)
        calls.addAll(parentClassCalls)
        
        return calls.distinct()
    }
    
    /**
     * 查找包含指定类作为字段的主类之间的映射调用
     */
    private fun findParentClassMappingCalls(childClass: String, allMapCalls: List<MapCallInfo>): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        
        // 1. 找到所有包含childClass作为字段的类
        val parentClasses = findClassesContainingFieldType(childClass)
        
        // 2. 对于每个映射调用，检查是否涉及包含childClass的父类
        for (mapCall in allMapCalls) {
            val sourceClass = mapCall.sourceType
            val targetClass = mapCall.targetType
            
            if (sourceClass != null && targetClass != null) {
                // 检查源类或目标类是否包含childClass类型的字段
                val sourceParents = parentClasses.filter { it.className == sourceClass }
                val targetParents = parentClasses.filter { it.className == targetClass }
                
                var shouldAddCall = false
                
                // 情况1: 源类包含childClass，需要检查目标类的对应字段
                for (sourceParent in sourceParents) {
                    val targetFieldInfo = findFieldTypeInClass(targetClass, sourceParent.fieldName)
                    if (targetFieldInfo != null && hasMatchingSubField(childClass, targetFieldInfo)) {
                        shouldAddCall = true
                        break
                    }
                }
                
                // 情况2: 目标类包含childClass，需要检查源类的对应字段
                if (!shouldAddCall) {
                    for (targetParent in targetParents) {
                        val sourceFieldInfo = findFieldTypeInClass(sourceClass, targetParent.fieldName)
                        if (sourceFieldInfo != null && hasMatchingSubField(childClass, sourceFieldInfo)) {
                            shouldAddCall = true
                            break
                        }
                    }
                }
                
                if (shouldAddCall) {
                    val containingMethod = PsiTreeUtil.getParentOfType(mapCall.psiCall, PsiMethod::class.java)
                    if (containingMethod != null) {
                        calls.add(MappingCall(
                            methodName = containingMethod.name,
                            className = containingMethod.containingClass?.qualifiedName ?: "Unknown",
                            location = "${containingMethod.containingFile?.name}:${getLineNumber(mapCall.psiCall)}",
                            callType = "ORIKA_MAPPING_PARENT",
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
                // 对于 XXX.class 这种表达式，直接获取类的完全限定名
                // expression.operand 是 PsiTypeElement，其 type 是我们需要的类型
                val operandType = expression.operand.type
                // 获取类型的规范文本并去除可能的泛型参数
                var type = operandType.canonicalText
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
