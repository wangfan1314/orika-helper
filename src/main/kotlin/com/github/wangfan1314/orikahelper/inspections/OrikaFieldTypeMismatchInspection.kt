package com.github.wangfan1314.orikahelper.inspections

import com.github.wangfan1314.orikahelper.utils.OrikaUtils
import com.intellij.codeInspection.*
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Orika字段类型不匹配检查
 * 检查当两个类进行Orika映射时，如果同名字段类型不一致，是否注册了相应的自定义转换器
 */
class OrikaFieldTypeMismatchInspection : AbstractBaseJavaLocalInspectionTool() {
    
    companion object {
        // 转换器缓存，避免重复扫描（使用弱引用避免内存泄漏）
        private val converterCache = mutableMapOf<String, List<ConverterInfo>>()
        
        // 字段配置缓存
        private val fieldConfigCache = mutableMapOf<String, Set<String>>()
    }
    
    // 缓存已检查过的类对映射，避免循环引用
    private val checkedClassPairs = ThreadLocal.withInitial { mutableSetOf<String>() }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                
                // 清空线程本地的缓存
                checkedClassPairs.get().clear()
                
                // 检查是否是Orika的map调用
                if (!OrikaUtils.isOrikaMapCall(expression)) {
                    return
                }
                
                val args = expression.argumentList.expressions
                if (args.size < 2) {
                    return
                }
                
                // 获取源类型和目标类型
                val sourceType = getTypeFromExpression(args[0])
                val targetType = getTypeFromExpression(args[1])
                
                if (sourceType == null || targetType == null) {
                    return
                }
                
                // 查找源类和目标类
                val project = expression.project
                val sourceClass = findClass(project, sourceType)
                val targetClass = findClass(project, targetType)
                
                if (sourceClass == null || targetClass == null) {
                    return
                }
                
                // 检查字段类型不匹配的情况
                val mismatchedFields = findMismatchedFields(sourceClass, targetClass)
                
                if (mismatchedFields.isEmpty()) {
                    return
                }
                
                // 查找项目中注册的所有自定义转换器（使用缓存）
                val projectKey = project.basePath ?: project.name
                val registeredConverters = converterCache.getOrPut(projectKey) {
                    findRegisteredConverters(project)
                }
                
                // 查找字段级别的映射配置（exclude、fieldMap等）（使用缓存）
                val cacheKey = "$sourceType->$targetType"
                val fieldMappingConfigs = fieldConfigCache.getOrPut(cacheKey) {
                    findFieldMappingConfigs(project, sourceType, targetType)
                }
                
                // 获取包含该map调用的方法
                val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                
                // 对每个不匹配的字段，检查是否有对应的转换器或字段级别配置
                for ((fieldName, sourceFieldType, targetFieldType) in mismatchedFields) {
                    // 检查是否在字段级别配置中被排除或自定义映射
                    if (fieldMappingConfigs.contains(fieldName)) {
                        continue // 如果有字段级别配置，跳过检查
                    }
                    
                    // 检查是否有匹配的转换器
                    val hasConverter = hasMatchingConverter(sourceFieldType, targetFieldType, registeredConverters)
                    
                    // 检查是否在方法内手动赋值了该字段
                    val hasManualAssignment = containingMethod != null && 
                        hasManualFieldAssignment(expression, fieldName, containingMethod)
                    
                    // 如果既没有转换器，也没有手动赋值，则报错
                    if (!hasConverter && !hasManualAssignment) {
                        val message = "字段 '$fieldName' 类型不匹配：" +
                                "源类型为 '${sourceFieldType.presentableText}'，" +
                                "目标类型为 '${targetFieldType.presentableText}'，" +
                                "且未找到匹配的自定义类型转换器，也未在方法内手动赋值。建议：\n" +
                                "1. 注册自定义转换器 Converter<${sourceFieldType.presentableText}, ${targetFieldType.presentableText}>\n" +
                                "2. 或使用 fieldMap 配置字段级别的转换\n" +
                                "3. 或在方法内手动调用 set${fieldName.replaceFirstChar { it.uppercase() }}(...)\n" +
                                "4. 或使用 exclude 排除该字段"
                        
                        holder.registerProblem(
                            expression,
                            message,
                            ProblemHighlightType.ERROR  // ERROR级别，在IDE中显示为红色错误
                        )
                    }
                    
                    // 递归检查嵌套对象
                    checkNestedObjectRecursively(
                        sourceFieldType,
                        targetFieldType,
                        fieldName,
                        expression,
                        holder,
                        registeredConverters
                    )
                }
            }
        }
    }
    
    /**
     * 从表达式中提取类型名称
     */
    private fun getTypeFromExpression(expression: PsiExpression): String? {
        return when (expression) {
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiVariable -> {
                        val type = resolved.type.canonicalText
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
                val method = expression.resolveMethod()
                method?.returnType?.canonicalText?.substringBefore('<')
            }
            else -> {
                val type = expression.type?.canonicalText
                type?.substringBefore('<')
            }
        }
    }
    
    /**
     * 查找类
     */
    private fun findClass(project: Project, className: String): PsiClass? {
        val classes = JavaPsiFacade.getInstance(project)
            .findClasses(className, GlobalSearchScope.allScope(project))
        return classes.firstOrNull()
    }
    
    /**
     * 查找源类和目标类之间字段类型不匹配的情况
     */
    private fun findMismatchedFields(
        sourceClass: PsiClass,
        targetClass: PsiClass
    ): List<FieldMismatch> {
        val mismatches = mutableListOf<FieldMismatch>()
        
        // 获取源类的所有字段（包括继承的）
        val sourceFields = sourceClass.allFields
        val targetFields = targetClass.allFields.associateBy { it.name }
        
        for (sourceField in sourceFields) {
            val fieldName = sourceField.name
            val targetField = targetFields[fieldName] ?: continue
            
            // 获取字段类型
            val sourceFieldType = sourceField.type
            val targetFieldType = targetField.type
            
            // 检查类型是否不同
            if (!areTypesCompatible(sourceFieldType, targetFieldType)) {
                mismatches.add(FieldMismatch(fieldName, sourceFieldType, targetFieldType))
            }
        }
        
        return mismatches
    }
    
    /**
     * 检查两个类型是否兼容
     * 兼容的情况包括：类型相同、继承关系、基本类型与包装类型等
     */
    private fun areTypesCompatible(type1: PsiType, type2: PsiType): Boolean {
        // 类型完全相同
        if (type1.canonicalText == type2.canonicalText) {
            return true
        }
        
        // 检查是否是基本类型与包装类型的关系
        if (isBoxingCompatible(type1, type2)) {
            return true
        }
        
        // 检查继承关系
        if (type1 is PsiClassType && type2 is PsiClassType) {
            val class1 = type1.resolve()
            val class2 = type2.resolve()
            
            if (class1 != null && class2 != null) {
                // 检查是否有继承关系
                if (class1.isInheritor(class2, true) || class2.isInheritor(class1, true)) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 检查是否是基本类型与包装类型的装箱/拆箱关系
     */
    private fun isBoxingCompatible(type1: PsiType, type2: PsiType): Boolean {
        val boxingPairs = mapOf(
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "double" to "java.lang.Double",
            "float" to "java.lang.Float",
            "boolean" to "java.lang.Boolean",
            "byte" to "java.lang.Byte",
            "char" to "java.lang.Character",
            "short" to "java.lang.Short"
        )
        
        val type1Text = type1.canonicalText
        val type2Text = type2.canonicalText
        
        // 检查 type1 是基本类型，type2 是包装类型
        if (boxingPairs[type1Text] == type2Text) {
            return true
        }
        
        // 检查 type2 是基本类型，type1 是包装类型
        if (boxingPairs[type2Text] == type1Text) {
            return true
        }
        
        return false
    }
    
    /**
     * 查找项目中注册的所有自定义转换器
     */
    private fun findRegisteredConverters(project: Project): List<ConverterInfo> {
        val converters = mutableListOf<ConverterInfo>()
        
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
                    
                    // 查找 registerConverter 或 register 调用
                    if (methodName == "registerConverter" || methodName == "register") {
                        val converterInfo = extractConverterInfo(expression)
                        if (converterInfo != null) {
                            converters.add(converterInfo)
                        }
                    }
                    
                    // 查找 getConverterFactory().registerConverter() 调用
                    if (methodName == "getConverterFactory") {
                        // 查找调用链中的 registerConverter
                        val parent = expression.parent
                        if (parent is PsiReferenceExpression) {
                            val grandParent = parent.parent
                            if (grandParent is PsiMethodCallExpression) {
                                val grandMethodName = grandParent.methodExpression.referenceName
                                if (grandMethodName == "registerConverter" || grandMethodName == "register") {
                                    val converterInfo = extractConverterInfo(grandParent)
                                    if (converterInfo != null) {
                                        converters.add(converterInfo)
                                    }
                                }
                            }
                        }
                    }
                    
                    // 查找 customize 方法中的 converter 调用
                    if (methodName == "converter" || methodName == "customize") {
                        val converterInfo = extractConverterInfoFromCustomize(expression)
                        if (converterInfo != null) {
                            converters.add(converterInfo)
                        }
                    }
                }
                
                override fun visitClass(aClass: PsiClass) {
                    super.visitClass(aClass)
                    
                    // 查找实现了Converter接口的类
                    val converterInfo = extractConverterFromClass(aClass)
                    if (converterInfo != null) {
                        converters.add(converterInfo)
                    }
                }
            })
        }
        
        return converters
    }
    
    /**
     * 从registerConverter调用中提取转换器信息
     */
    private fun extractConverterInfo(expression: PsiMethodCallExpression): ConverterInfo? {
        val args = expression.argumentList.expressions
        if (args.isEmpty()) {
            return null
        }
        
        // registerConverter 可能接收一个Converter实例或者Converter类
        val converterArg = args[0]
        
        // 如果是 new Converter<A, B>() 的形式
        if (converterArg is PsiNewExpression) {
            return extractConverterFromNewExpression(converterArg)
        }
        
        // 如果是类引用的形式
        if (converterArg is PsiClassObjectAccessExpression) {
            val psiClass = (converterArg.operand.type as? PsiClassType)?.resolve()
            if (psiClass != null) {
                return extractConverterFromClass(psiClass)
            }
        }
        
        // 如果是变量引用
        if (converterArg is PsiReferenceExpression) {
            val resolved = converterArg.resolve()
            if (resolved is PsiVariable) {
                val initializer = (resolved as? PsiLocalVariable)?.initializer
                    ?: (resolved as? PsiField)?.initializer
                
                if (initializer is PsiNewExpression) {
                    return extractConverterFromNewExpression(initializer)
                }
            }
        }
        
        return null
    }
    
    /**
     * 从new表达式中提取转换器信息
     */
    private fun extractConverterFromNewExpression(newExpression: PsiNewExpression): ConverterInfo? {
        val classRef = newExpression.classReference ?: return null
        val psiClass = classRef.resolve() as? PsiClass ?: return null
        
        return extractConverterFromClass(psiClass)
    }
    
    /**
     * 从类定义中提取转换器信息
     */
    private fun extractConverterFromClass(psiClass: PsiClass): ConverterInfo? {
        // 查找实现的Converter接口（支持 Converter, BidirectionalConverter, CustomConverter 等）
        for (implementsType in psiClass.implementsListTypes) {
            val typeText = implementsType.canonicalText
            
            // 检查是否实现了Converter相关接口
            if ((typeText.contains("Converter") || typeText.contains("CustomMapper")) 
                && implementsType is PsiClassType) {
                val parameters = implementsType.parameters
                if (parameters.size >= 2) {
                    val sourceType = parameters[0]
                    val targetType = parameters[1]
                    return ConverterInfo(sourceType, targetType)
                }
            }
        }
        
        // 查找继承的Converter类
        val superClass = psiClass.superClass
        if (superClass != null) {
            for (superType in psiClass.superTypes) {
                val typeText = superType.canonicalText
                if ((typeText.contains("Converter") || typeText.contains("CustomMapper")) 
                    && superType is PsiClassType) {
                    val parameters = superType.parameters
                    if (parameters.size >= 2) {
                        val sourceType = parameters[0]
                        val targetType = parameters[1]
                        return ConverterInfo(sourceType, targetType)
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * 从 customize 方法调用中提取转换器信息
     * 例如：classMapBuilder.customize(new CustomConverter<A, B>() {...})
     */
    private fun extractConverterInfoFromCustomize(expression: PsiMethodCallExpression): ConverterInfo? {
        val args = expression.argumentList.expressions
        if (args.isEmpty()) {
            return null
        }
        
        val converterArg = args[0]
        
        // 如果是 new Converter<A, B>() 的形式
        if (converterArg is PsiNewExpression) {
            return extractConverterFromNewExpression(converterArg)
        }
        
        // 如果是 lambda 或匿名类
        if (converterArg is PsiLambdaExpression) {
            val functionalType = converterArg.functionalInterfaceType
            if (functionalType is PsiClassType && functionalType.parameters.size >= 2) {
                return ConverterInfo(functionalType.parameters[0], functionalType.parameters[1])
            }
        }
        
        return null
    }
    
    /**
     * 检查是否有匹配的转换器
     */
    private fun hasMatchingConverter(
        sourceType: PsiType,
        targetType: PsiType,
        converters: List<ConverterInfo>
    ): Boolean {
        for (converter in converters) {
            // 检查是否有从sourceType到targetType的转换器
            if (typesMatch(sourceType, converter.sourceType) && 
                typesMatch(targetType, converter.targetType)) {
                return true
            }
            
            // 也检查反向转换器
            if (typesMatch(sourceType, converter.targetType) && 
                typesMatch(targetType, converter.sourceType)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 检查两个类型是否匹配
     */
    private fun typesMatch(type1: PsiType, type2: PsiType): Boolean {
        val canonicalText1 = type1.canonicalText.substringBefore('<')
        val canonicalText2 = type2.canonicalText.substringBefore('<')
        
        if (canonicalText1 == canonicalText2) {
            return true
        }
        
        // 检查基本类型与包装类型
        if (isBoxingCompatible(type1, type2)) {
            return true
        }
        
        return false
    }
    
    /**
     * 查找字段级别的映射配置
     * 包括 exclude、fieldMap、customize 等
     */
    private fun findFieldMappingConfigs(project: Project, sourceClass: String, targetClass: String): Set<String> {
        val configuredFields = mutableSetOf<String>()
        
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
                    
                    // 检查是否是 classMap 配置
                    if (methodName == "classMap" || methodName == "mapClasses") {
                        val args = expression.argumentList.expressions
                        if (args.size >= 2) {
                            val sourceType = getTypeFromExpression(args[0])
                            val targetType = getTypeFromExpression(args[1])
                            
                            if (sourceType == sourceClass && targetType == targetClass) {
                                // 在这个配置块中查找 exclude 或 fieldMap
                                findFieldConfigsInBlock(expression, configuredFields)
                            }
                        }
                    }
                    
                    // 检查 exclude 方法调用
                    if (methodName == "exclude" || methodName == "excludeField") {
                        val args = expression.argumentList.expressions
                        if (args.isNotEmpty()) {
                            val fieldName = extractFieldNameFromExpression(args[0])
                            if (fieldName != null) {
                                configuredFields.add(fieldName)
                            }
                        }
                    }
                    
                    // 检查 fieldMap 方法调用
                    if (methodName == "fieldMap" || methodName == "field") {
                        val args = expression.argumentList.expressions
                        if (args.isNotEmpty()) {
                            val fieldName = extractFieldNameFromExpression(args[0])
                            if (fieldName != null) {
                                configuredFields.add(fieldName)
                            }
                        }
                    }
                }
            })
        }
        
        return configuredFields
    }
    
    /**
     * 在配置块中查找字段配置
     */
    private fun findFieldConfigsInBlock(expression: PsiMethodCallExpression, configuredFields: MutableSet<String>) {
        // 查找方法调用链
        var current: PsiElement? = expression.parent
        var depth = 0
        
        while (current != null && depth < 20) {
            if (current is PsiMethodCallExpression) {
                val methodName = current.methodExpression.referenceName
                
                if (methodName == "exclude" || methodName == "excludeField" || 
                    methodName == "fieldMap" || methodName == "field") {
                    val args = current.argumentList.expressions
                    if (args.isNotEmpty()) {
                        val fieldName = extractFieldNameFromExpression(args[0])
                        if (fieldName != null) {
                            configuredFields.add(fieldName)
                        }
                    }
                }
            }
            
            current = current.parent
            depth++
        }
    }
    
    /**
     * 从表达式中提取字段名
     */
    private fun extractFieldNameFromExpression(expression: PsiExpression): String? {
        return when (expression) {
            is PsiLiteralExpression -> {
                // 字符串字面量：exclude("fieldName")
                expression.value as? String
            }
            is PsiReferenceExpression -> {
                // 引用表达式：可能是字段引用
                expression.referenceName
            }
            else -> null
        }
    }
    
    /**
     * 检查在包含map调用的方法内，是否有对指定字段的手动赋值
     * 例如：UserEntity entity = mapper.map(dto, UserEntity.class);
     *      entity.setAge(...); // 手动赋值
     */
    private fun hasManualFieldAssignment(
        mapCallExpression: PsiMethodCallExpression,
        fieldName: String,
        containingMethod: PsiMethod
    ): Boolean {
        // 获取map调用的返回值赋值语句
        val assignmentVariable = findAssignmentVariable(mapCallExpression) ?: return false
        
        // 在方法体中查找对该变量的setter调用
        val methodBody = containingMethod.body ?: return false
        
        // setter方法名
        val setterName = "set${fieldName.replaceFirstChar { it.uppercase() }}"
        
        var foundAssignment = false
        
        methodBody.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                
                val methodName = expression.methodExpression.referenceName
                if (methodName == setterName) {
                    // 检查调用者是否是我们的变量
                    val qualifier = expression.methodExpression.qualifierExpression
                    if (qualifier is PsiReferenceExpression) {
                        val resolved = qualifier.resolve()
                        if (resolved == assignmentVariable) {
                            foundAssignment = true
                        }
                    }
                }
            }
        })
        
        return foundAssignment
    }
    
    /**
     * 查找map调用的返回值被赋值给哪个变量
     * 例如：UserEntity entity = mapper.map(...);
     * 返回 entity 变量
     */
    private fun findAssignmentVariable(mapCallExpression: PsiMethodCallExpression): PsiVariable? {
        var parent = mapCallExpression.parent
        
        // 向上查找赋值语句
        while (parent != null) {
            when (parent) {
                // 局部变量声明: UserEntity entity = mapper.map(...);
                is PsiLocalVariable -> {
                    return parent
                }
                // 赋值表达式: entity = mapper.map(...);
                is PsiAssignmentExpression -> {
                    val lExpression = parent.lExpression
                    if (lExpression is PsiReferenceExpression) {
                        val resolved = lExpression.resolve()
                        if (resolved is PsiVariable) {
                            return resolved
                        }
                    }
                    return null
                }
                // 返回语句: return mapper.map(...);
                is PsiReturnStatement -> {
                    return null
                }
                is PsiExpressionStatement -> {
                    parent = parent.parent
                    continue
                }
                else -> {
                    parent = parent.parent
                }
            }
        }
        
        return null
    }
    
    /**
     * 递归检查嵌套对象的字段类型不匹配
     */
    private fun checkNestedObjectRecursively(
        sourceFieldType: PsiType,
        targetFieldType: PsiType,
        parentFieldName: String,
        expression: PsiMethodCallExpression,
        holder: ProblemsHolder,
        converters: List<ConverterInfo>
    ) {
        // 只检查自定义类
        if (!isCustomClass(sourceFieldType) || !isCustomClass(targetFieldType)) {
            return
        }
        
        val sourceClassName = sourceFieldType.canonicalText.substringBefore('<')
        val targetClassName = targetFieldType.canonicalText.substringBefore('<')
        
        // 检查是否已经检查过这对类（避免循环引用）
        val classPairKey = "$sourceClassName->$targetClassName"
        if (checkedClassPairs.get().contains(classPairKey)) {
            return
        }
        checkedClassPairs.get().add(classPairKey)
        
        // 查找源类和目标类
        val project = expression.project
        val sourceClass = findClass(project, sourceClassName)
        val targetClass = findClass(project, targetClassName)
        
        if (sourceClass == null || targetClass == null) {
            return
        }
        
        // 检查嵌套对象的字段
        val nestedMismatchedFields = findMismatchedFields(sourceClass, targetClass)
        
        for ((nestedFieldName, nestedSourceType, nestedTargetType) in nestedMismatchedFields) {
            // 检查是否有转换器
            val hasConverter = hasMatchingConverter(nestedSourceType, nestedTargetType, converters)
            
            if (!hasConverter) {
                // 构造嵌套路径
                val fullFieldPath = "$parentFieldName.$nestedFieldName"
                
                val message = "嵌套字段 '$fullFieldPath' 类型不匹配：" +
                        "源类型为 '${nestedSourceType.presentableText}'，" +
                        "目标类型为 '${nestedTargetType.presentableText}'，" +
                        "且未找到匹配的自定义类型转换器。建议：\n" +
                        "1. 注册自定义转换器 Converter<${nestedSourceType.presentableText}, ${nestedTargetType.presentableText}>\n" +
                        "2. 或使用 fieldMap 配置字段级别的转换"
                
                holder.registerProblem(
                    expression,
                    message,
                    ProblemHighlightType.ERROR
                )
            }
            
            // 继续递归检查更深层的嵌套
            checkNestedObjectRecursively(
                nestedSourceType,
                nestedTargetType,
                "$parentFieldName.$nestedFieldName",
                expression,
                holder,
                converters
            )
        }
    }
    
    /**
     * 判断是否是自定义类（非JDK类、非基本类型）
     */
    private fun isCustomClass(type: PsiType): Boolean {
        if (type !is PsiClassType) {
            return false
        }
        
        val canonicalText = type.canonicalText.substringBefore('<')
        
        // 排除基本类型和常用JDK类
        val excludedTypes = setOf(
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Short",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Date",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.ZonedDateTime",
            "java.time.Instant",
            "java.util.List",
            "java.util.Set",
            "java.util.Map",
            "java.util.Collection"
        )
        
        if (excludedTypes.contains(canonicalText)) {
            return false
        }
        
        // 排除java.*包下的所有类
        if (canonicalText.startsWith("java.") || 
            canonicalText.startsWith("javax.") ||
            canonicalText.startsWith("kotlin.")) {
            return false
        }
        
        return true
    }
    
    override fun getDisplayName(): String {
        return "Orika字段类型不匹配检查"
    }
    
    override fun getShortName(): String {
        return "OrikaFieldTypeMismatch"
    }
    
    override fun getGroupDisplayName(): String {
        return "Orika"
    }
    
    override fun isEnabledByDefault(): Boolean {
        return true
    }
    
    /**
     * 字段类型不匹配信息
     */
    private data class FieldMismatch(
        val fieldName: String,
        val sourceType: PsiType,
        val targetType: PsiType
    )
    
    /**
     * 转换器信息
     */
    private data class ConverterInfo(
        val sourceType: PsiType,
        val targetType: PsiType
    )
}

