package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.plugins.template.model.MappingCall
import org.jetbrains.plugins.template.model.MappingRelation

/**
 * Orika映射分析服务
 * 负责分析Orika映射配置和调用链路
 */
@Service(Service.Level.PROJECT)
class OrikaMappingAnalyzer(private val project: Project) {

    /**
     * 分析指定字段的映射关系
     */
    fun analyzeMappingRelations(field: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        val processedClasses = mutableSetOf<String>()
        
        try {
            // 递归查找所有相关的映射关系
            findAllRelatedMappings(field, relations, processedClasses)
        } catch (e: Exception) {
            // 静默处理异常，返回已找到的关系
        }
        
        return relations.distinct()
    }

    /**
     * 递归查找所有相关的映射关系
     */
    private fun findAllRelatedMappings(
        field: PsiField, 
        relations: MutableList<MappingRelation>, 
        processedClasses: MutableSet<String>
    ) {
        val fieldClass = field.containingClass?.qualifiedName ?: return
        val fieldName = field.name
        
        // 避免循环处理
        if (processedClasses.contains(fieldClass)) {
            return
        }
        processedClasses.add(fieldClass)
        
        // 1. 查找直接映射关系
        val directMappings = findActualMappingCalls(field)
        relations.addAll(directMappings)
        
        // 2. 查找该类作为源类型的所有映射
        val outgoingMappings = findMappingsFromClass(fieldClass, fieldName)
        relations.addAll(outgoingMappings)
        
        // 3. 查找该类作为目标类型的所有映射
        val incomingMappings = findMappingsToClass(fieldClass, fieldName)
        relations.addAll(incomingMappings)
        
        // 4. 查找项目中所有涉及当前类的映射调用
        val allClassMappings = findAllMappingRelationsForClass(fieldClass, fieldName)
        relations.addAll(allClassMappings)
        
        // 5. 递归处理相关的类
        val relatedClasses = mutableSetOf<String>()
        directMappings.forEach { mapping ->
            relatedClasses.add(mapping.sourceClass)
            relatedClasses.add(mapping.targetClass)
        }
        outgoingMappings.forEach { mapping ->
            relatedClasses.add(mapping.targetClass)
        }
        incomingMappings.forEach { mapping ->
            relatedClasses.add(mapping.sourceClass)
        }
        allClassMappings.forEach { mapping ->
            relatedClasses.add(mapping.sourceClass)
            relatedClasses.add(mapping.targetClass)
        }
        
        // 递归处理相关类中的同名字段
        for (relatedClass in relatedClasses) {
            if (!processedClasses.contains(relatedClass) && relatedClass != fieldClass) {
                val relatedField = findFieldInClass(relatedClass, fieldName)
                if (relatedField != null) {
                    findAllRelatedMappings(relatedField, relations, processedClasses)
                }
            }
        }
    }

    /**
     * 查找字段的完整调用链路
     */
    fun findCallHierarchy(field: PsiField): List<MappingCall> {
        val callChain = mutableListOf<MappingCall>()
        val processedClasses = mutableSetOf<String>()
        
        try {
            // 递归查找所有相关的映射调用
            findAllRelatedMappingCalls(field, callChain, processedClasses)
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return callChain.distinct()
    }

    /**
     * 递归查找所有相关的映射调用
     */
    private fun findAllRelatedMappingCalls(
        field: PsiField, 
        callChain: MutableList<MappingCall>, 
        processedClasses: MutableSet<String>
    ) {
        val fieldClass = field.containingClass?.qualifiedName ?: return
        
        // 避免循环处理
        if (processedClasses.contains(fieldClass)) {
            return
        }
        processedClasses.add(fieldClass)
        
        // 1. 查找直接的Orika映射调用
        val directCalls = findOrikaMappingCalls(field)
        callChain.addAll(directCalls)
        
        // 2. 查找涉及该类的所有映射调用
        val allClassMappings = findAllMappingCallsForClass(fieldClass)
        callChain.addAll(allClassMappings)
        
        // 3. 递归处理相关的类
        val relatedClasses = mutableSetOf<String>()
        allClassMappings.forEach { call ->
            // 从映射调用中提取相关类
            val methodCall = call.psiElement
            if (methodCall is PsiMethodCallExpression) {
                val args = methodCall.argumentList.expressions
                if (args.size >= 2) {
                    val sourceType = getTypeFromExpression(args[0])
                    val targetType = getTypeFromExpression(args[1])
                    sourceType?.let { relatedClasses.add(it) }
                    targetType?.let { relatedClasses.add(it) }
                }
            }
        }
        
        // 递归处理相关类中的同名字段
        for (relatedClass in relatedClasses) {
            if (!processedClasses.contains(relatedClass)) {
                val relatedField = findFieldInClass(relatedClass, field.name)
                if (relatedField != null) {
                    findAllRelatedMappingCalls(relatedField, callChain, processedClasses)
                }
            }
        }
    }

    /**
     * 查找从指定类出发的所有映射
     */
    private fun findMappingsFromClass(sourceClass: String, fieldName: String): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        
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
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (sourceType == sourceClass && targetType != null) {
                                val targetField = findCorrespondingField(targetType, fieldName)
                                relations.add(MappingRelation(
                                    sourceClass = sourceClass,
                                    sourceField = fieldName,
                                    targetClass = targetType,
                                    targetField = targetField ?: fieldName,
                                    mappingType = "ORIKA_MAP_CALL"
                                ))
                            }
                        }
                    }
                }
            })
        }
        
        return relations
    }

    /**
     * 查找到指定类的所有映射
     */
    private fun findMappingsToClass(targetClass: String, fieldName: String): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        
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
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (targetType == targetClass && sourceType != null) {
                                val sourceField = findCorrespondingField(sourceType, fieldName)
                                relations.add(MappingRelation(
                                    sourceClass = sourceType,
                                    sourceField = sourceField ?: fieldName,
                                    targetClass = targetClass,
                                    targetField = fieldName,
                                    mappingType = "ORIKA_MAP_CALL"
                                ))
                            }
                        }
                    }
                }
            })
        }
        
        return relations
    }

    /**
     * 查找项目中所有涉及指定类的映射关系
     */
    private fun findAllMappingRelationsForClass(className: String, fieldName: String): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        
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
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            // 检查是否涉及当前类
                            when {
                                sourceType == className && targetType != null -> {
                                    val targetField = findCorrespondingField(targetType, fieldName)
                                    relations.add(MappingRelation(
                                        sourceClass = className,
                                        sourceField = fieldName,
                                        targetClass = targetType,
                                        targetField = targetField ?: fieldName,
                                        mappingType = "ORIKA_MAP_CALL"
                                    ))
                                }
                                targetType == className && sourceType != null -> {
                                    val sourceField = findCorrespondingField(sourceType, fieldName)
                                    relations.add(MappingRelation(
                                        sourceClass = sourceType,
                                        sourceField = sourceField ?: fieldName,
                                        targetClass = className,
                                        targetField = fieldName,
                                        mappingType = "ORIKA_MAP_CALL"
                                    ))
                                }
                            }
                        }
                    }
                }
            })
        }
        
        return relations
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
     * 查找实际的映射调用
     */
    private fun findActualMappingCalls(field: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        val fieldName = field.name
        val sourceClass = field.containingClass?.qualifiedName ?: return relations
        
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
                    
                    // 检查是否是Orika的map方法调用
                    val methodName = expression.methodExpression.referenceName
                    if (methodName == "map") {
                        val args = expression.argumentList.expressions
                        if (args.size >= 2) {
                            // 尝试解析源类型和目标类型
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (sourceType == sourceClass || targetType == sourceClass) {
                                // 找到包含目标字段的映射
                                val targetClassName = if (sourceType == sourceClass) targetType else sourceType
                                val targetField = findCorrespondingField(targetClassName, fieldName)
                                
                                relations.add(MappingRelation(
                                    sourceClass = sourceClass,
                                    sourceField = fieldName,
                                    targetClass = targetClassName ?: "Unknown",
                                    targetField = targetField ?: fieldName,
                                    mappingType = "ORIKA_MAP_CALL"
                                ))
                            }
                        }
                    }
                }
            })
        }
        
        return relations
    }

    /**
     * 查找涉及指定类的所有映射调用
     */
    private fun findAllMappingCallsForClass(className: String): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        
        // 搜索包含map方法调用的地方
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
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (sourceType == className || targetType == className) {
                                val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                                if (containingMethod != null) {
                                    calls.add(MappingCall(
                                        methodName = containingMethod.name,
                                        className = containingMethod.containingClass?.qualifiedName ?: "Unknown",
                                        location = "${containingMethod.containingFile?.name}:${getLineNumber(expression)}",
                                        callType = "ORIKA_MAPPING",
                                        psiElement = expression
                                    ))
                                }
                            }
                        }
                    }
                }
            })
        }
        
        return calls
    }

    /**
     * 查找Orika映射调用
     */
    private fun findOrikaMappingCalls(field: PsiField): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        val fieldClass = field.containingClass?.qualifiedName ?: return calls
        
        // 搜索包含map方法调用的地方
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
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (sourceType == fieldClass || targetType == fieldClass) {
                                val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                                if (containingMethod != null) {
                                    calls.add(MappingCall(
                                        methodName = containingMethod.name,
                                        className = containingMethod.containingClass?.qualifiedName ?: "Unknown",
                                        location = "${containingMethod.containingFile?.name}:${getLineNumber(expression)}",
                                        callType = "ORIKA_MAPPING",
                                        psiElement = expression // 添加PSI元素引用用于跳转
                                    ))
                                }
                            }
                        }
                    }
                }
            })
        }
        
        return calls
    }

    /**
     * 从表达式中获取类型信息
     */
    private fun getTypeFromExpression(expression: PsiExpression): String? {
        return when (expression) {
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiVariable -> {
                        val type = resolved.type.canonicalText
                        // 去掉泛型参数，如List<String> -> List
                        type.substringBefore('<')
                    }
                    is PsiClass -> resolved.qualifiedName
                    else -> null
                }
            }
            is PsiClassObjectAccessExpression -> {
                val type = expression.operand.type.canonicalText
                type.substringBefore('<')
            }
            is PsiMethodCallExpression -> {
                // 处理方法调用的返回类型
                val method = expression.resolveMethod()
                method?.returnType?.canonicalText?.substringBefore('<')
            }
            else -> {
                // 尝试从类型中提取
                val type = expression.type?.canonicalText
                type?.substringBefore('<')
            }
        }
    }

    /**
     * 查找对应的字段
     */
    private fun findCorrespondingField(className: String?, fieldName: String): String? {
        if (className == null) return null
        
        try {
            val classes = JavaPsiFacade.getInstance(project).findClasses(className, GlobalSearchScope.projectScope(project))
            for (clazz in classes) {
                // 首先查找同名字段
                val field = clazz.findFieldByName(fieldName, true)
                if (field != null) {
                    return fieldName
                }
                
                // 查找可能的映射字段（基于命名约定）
                val allFields = clazz.allFields
                for (f in allFields) {
                    if (f.name.contains(fieldName, ignoreCase = true) || 
                        fieldName.contains(f.name, ignoreCase = true)) {
                        return f.name
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        
        return fieldName
    }

    /**
     * 查找Orika API配置方式的映射
     */
    private fun findOrikaMappingConfigs(field: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        // 这里可以扩展更复杂的配置分析
        return relations
    }

    /**
     * 查找注解方式的映射配置
     */
    private fun findAnnotationMappings(field: PsiField): List<MappingRelation> {
        val relations = mutableListOf<MappingRelation>()
        
        // 检查字段注解
        field.annotations.forEach { annotation ->
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName?.contains("Mapping") == true) {
                relations.add(MappingRelation(
                    sourceClass = field.containingClass?.qualifiedName ?: "",
                    sourceField = field.name,
                    targetClass = "AnnotationTarget",
                    targetField = field.name,
                    mappingType = "ANNOTATION"
                ))
            }
        }
        
        return relations
    }

    /**
     * 分析方法调用
     */
    private fun analyzeMethodCall(method: PsiMethod): MappingCall? {
        return try {
            // 检查方法名是否包含映射相关关键词
            val methodName = method.name
            if (methodName.contains("map") || methodName.contains("convert") || methodName.contains("transform")) {
                MappingCall(
                    methodName = methodName,
                    className = method.containingClass?.qualifiedName ?: "Unknown",
                    location = "${method.containingFile?.name ?: "Unknown"}:${getLineNumber(method)}",
                    callType = "POTENTIAL_MAPPING",
                    psiElement = method
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取方法的行号
     */
    private fun getLineNumber(element: PsiElement): Int {
        return try {
            val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            document?.getLineNumber(element.textOffset)?.plus(1) ?: 1
        } catch (e: Exception) {
            1
        }
    }
}