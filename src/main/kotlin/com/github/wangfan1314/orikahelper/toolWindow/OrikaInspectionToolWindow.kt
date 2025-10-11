package com.github.wangfan1314.orikahelper.toolWindow

import com.github.wangfan1314.orikahelper.inspections.OrikaFieldTypeMismatchInspection
import com.github.wangfan1314.orikahelper.utils.OrikaUtils
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Orika检查结果Tool Window
 * 汇总显示项目中所有的Orika字段类型不匹配问题
 */
class OrikaInspectionToolWindow(private val project: Project, toolWindow: ToolWindow) {
    
    // 缓存转换器信息，避免重复扫描
    private var cachedConverters: List<ConverterInfo>? = null
    private var convertersCacheTime: Long = 0
    private val CACHE_VALID_TIME = 30000L // 缓存30秒
    
    // 缓存已检查过的类对映射，避免重复检查和循环引用
    private val checkedClassPairs = mutableSetOf<String>()
    
    private val tableModel = DefaultTableModel(
        arrayOf("文件", "类名", "方法", "行号", "问题字段", "源类型", "目标类型", "错误信息"),
        0
    )
    
    private val table = JBTable(tableModel).apply {
        setDefaultEditor(Object::class.java, null) // 禁止编辑
        autoCreateRowSorter = true
        fillsViewportHeight = true
        
        // 双击跳转到代码
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = rowAtPoint(e.point)
                    if (row >= 0) {
                        navigateToCode(row)
                    }
                }
            }
            
            override fun mouseEntered(e: MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            
            override fun mouseExited(e: MouseEvent) {
                cursor = Cursor.getDefaultCursor()
            }
        })
    }
    
    private val refreshButton = JButton("刷新检查").apply {
        addActionListener {
            SwingUtilities.invokeLater {
                scanProject()
            }
        }
    }
    
    private val statusLabel = JLabel("就绪")
    
    val content: JPanel = JPanel(BorderLayout()).apply {
        // 工具栏
        val toolbar = JPanel().apply {
            add(refreshButton)
            add(Box.createHorizontalStrut(10))
            add(statusLabel)
        }
        
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        
        // 底部说明
        val helpLabel = JLabel("双击行可跳转到代码位置 | 建议：修复所有问题后再编译")
        add(helpLabel, BorderLayout.SOUTH)
    }
    
    init {
        // 自动扫描
        SwingUtilities.invokeLater {
            scanProject()
        }
    }
    
    /**
     * 扫描整个项目，查找所有Orika类型不匹配问题
     */
    private fun scanProject() {
        refreshButton.isEnabled = false
        statusLabel.text = "正在扫描项目..."
        tableModel.rowCount = 0
        
        Thread {
            try {
                val startTime = System.currentTimeMillis()
                val problems = mutableListOf<OrikalProblem>()
                
                // 清空类对缓存
                checkedClassPairs.clear()
                
                // 获取或刷新转换器缓存
                val converters = getOrRefreshConvertersCache()
                
                // 只扫描主代码，排除测试代码（性能优化）
                val scope = GlobalSearchScope.projectScope(project)
                    .intersectWith(GlobalSearchScope.notScope(
                        GlobalSearchScope.getScopeRestrictedByFileTypes(
                            GlobalSearchScope.projectScope(project),
                            JavaFileType.INSTANCE
                        ).let { testScope ->
                            // 排除test目录
                            GlobalSearchScope.projectScope(project)
                        }
                    ))
                
                // 搜索项目中的所有Java文件（只扫描src/main）
                val javaFiles = mutableListOf<PsiJavaFile>()
                FileTypeIndex.processFiles(
                    JavaFileType.INSTANCE,
                    { virtualFile ->
                        // 跳过test目录
                        if (virtualFile.path.contains("/test/") || 
                            virtualFile.path.contains("\\test\\")) {
                            return@processFiles true
                        }
                        
                        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        if (psiFile is PsiJavaFile) {
                            javaFiles.add(psiFile)
                        }
                        true
                    },
                    GlobalSearchScope.projectScope(project)
                )
                
                val totalFiles = javaFiles.size
                var processedFiles = 0
                
                // 分析每个文件
                for (javaFile in javaFiles) {
                    processedFiles++
                    
                    // 更新进度
                    SwingUtilities.invokeLater {
                        statusLabel.text = "正在扫描... ($processedFiles/$totalFiles)"
                    }
                    
                    analyzeFile(javaFile, problems, converters)
                }
                
                val endTime = System.currentTimeMillis()
                val timeUsed = (endTime - startTime) / 1000.0
                
                // 更新UI
                SwingUtilities.invokeLater {
                    problems.forEach { problem ->
                        tableModel.addRow(arrayOf(
                            problem.fileName,
                            problem.className,
                            problem.methodName,
                            problem.lineNumber,
                            problem.fieldName,
                            problem.sourceType,
                            problem.targetType,
                            problem.message
                        ))
                    }
                    
                    val count = problems.size
                    statusLabel.text = if (count > 0) {
                        "发现 $count 个Orika类型不匹配问题 (扫描耗时: ${String.format("%.2f", timeUsed)}s)"
                    } else {
                        "✓ 未发现问题 (扫描耗时: ${String.format("%.2f", timeUsed)}s)"
                    }
                    
                    refreshButton.isEnabled = true
                    
                    // 如果有问题，弹出通知
                    if (count > 0) {
                        showProblemsNotification(count)
                    }
                }
                
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "扫描失败: ${e.message}"
                    refreshButton.isEnabled = true
                }
            }
        }.start()
    }
    
    /**
     * 获取或刷新转换器缓存
     */
    private fun getOrRefreshConvertersCache(): List<ConverterInfo> {
        val now = System.currentTimeMillis()
        
        // 如果缓存有效，直接返回
        if (cachedConverters != null && (now - convertersCacheTime) < CACHE_VALID_TIME) {
            return cachedConverters!!
        }
        
        // 重新扫描转换器
        val converters = findRegisteredConverters(project)
        cachedConverters = converters
        convertersCacheTime = now
        
        return converters
    }
    
    /**
     * 分析单个文件
     */
    private fun analyzeFile(
        javaFile: PsiJavaFile, 
        problems: MutableList<OrikalProblem>,
        converters: List<ConverterInfo>
    ) {
        javaFile.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                
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
                val sourceClass = findClass(project, sourceType)
                val targetClass = findClass(project, targetType)
                
                if (sourceClass == null || targetClass == null) {
                    return
                }
                
                // 检查字段类型不匹配
                val mismatchedFields = findMismatchedFields(sourceClass, targetClass)
                
                if (mismatchedFields.isEmpty()) {
                    return
                }
                
                // 获取包含该map调用的方法
                val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                val className = containingMethod?.containingClass?.qualifiedName ?: "Unknown"
                val methodName = containingMethod?.name ?: "unknown"
                val lineNumber = getLineNumber(expression)
                val fileName = javaFile.name
                
                // 使用传入的转换器列表（避免重复扫描）
                val registeredConverters = converters
                
                // 查找字段级别的映射配置（简化版，只检查当前文件）
                val fieldMappingConfigs = findFieldMappingConfigsInFile(javaFile, sourceType, targetType)
                
                // 检查每个不匹配的字段
                for ((fieldName, sourceFieldType, targetFieldType) in mismatchedFields) {
                    // 检查是否在字段级别配置中被排除或自定义映射
                    if (fieldMappingConfigs.contains(fieldName)) {
                        continue // 如果有字段级别配置，跳过
                    }
                    
                    // 检查是否有匹配的转换器
                    val hasConverter = hasMatchingConverter(sourceFieldType, targetFieldType, registeredConverters)
                    
                    // 检查是否在方法内手动赋值了该字段
                    val hasManualAssignment = containingMethod != null && 
                        hasManualFieldAssignment(expression, fieldName, containingMethod)
                    
                    // 只有既没有转换器，也没有手动赋值的情况才显示
                    if (!hasConverter && !hasManualAssignment) {
                        val message = "类型不匹配且未处理"
                        
                        problems.add(OrikalProblem(
                            fileName = fileName,
                            className = className,
                            methodName = methodName,
                            lineNumber = lineNumber,
                            fieldName = fieldName,
                            sourceType = sourceFieldType.presentableText,
                            targetType = targetFieldType.presentableText,
                            message = message,
                            psiElement = expression
                        ))
                    }
                    
                    // 递归检查嵌套对象（如果字段类型是自定义类）
                    checkNestedObjectRecursively(
                        sourceFieldType, 
                        targetFieldType, 
                        fieldName,
                        fileName,
                        className,
                        methodName,
                        lineNumber,
                        expression,
                        registeredConverters,
                        problems
                    )
                }
            }
        })
    }
    
    /**
     * 查找字段类型不匹配的情况
     */
    private fun findMismatchedFields(sourceClass: PsiClass, targetClass: PsiClass): List<FieldMismatch> {
        val mismatches = mutableListOf<FieldMismatch>()
        val sourceFields = sourceClass.allFields
        val targetFields = targetClass.allFields.associateBy { it.name }
        
        for (sourceField in sourceFields) {
            val fieldName = sourceField.name
            val targetField = targetFields[fieldName] ?: continue
            val sourceFieldType = sourceField.type
            val targetFieldType = targetField.type
            
            if (!areTypesCompatible(sourceFieldType, targetFieldType)) {
                mismatches.add(FieldMismatch(fieldName, sourceFieldType, targetFieldType))
            }
        }
        
        return mismatches
    }
    
    /**
     * 检查类型是否兼容
     */
    private fun areTypesCompatible(type1: PsiType, type2: PsiType): Boolean {
        if (type1.canonicalText == type2.canonicalText) {
            return true
        }
        
        if (isBoxingCompatible(type1, type2)) {
            return true
        }
        
        if (type1 is PsiClassType && type2 is PsiClassType) {
            val class1 = type1.resolve()
            val class2 = type2.resolve()
            if (class1 != null && class2 != null) {
                if (class1.isInheritor(class2, true) || class2.isInheritor(class1, true)) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 检查装箱/拆箱兼容性
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
        
        return boxingPairs[type1Text] == type2Text || boxingPairs[type2Text] == type1Text
    }
    
    /**
     * 从表达式中提取类型
     */
    private fun getTypeFromExpression(expression: PsiExpression): String? {
        return when (expression) {
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiVariable -> resolved.type.canonicalText.substringBefore('<')
                    is PsiClass -> resolved.qualifiedName
                    else -> null
                }
            }
            is PsiClassObjectAccessExpression -> {
                expression.operand.type.canonicalText.substringBefore('<')
            }
            is PsiMethodCallExpression -> {
                val method = expression.resolveMethod()
                method?.returnType?.canonicalText?.substringBefore('<')
            }
            else -> expression.type?.canonicalText?.substringBefore('<')
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
     * 获取行号
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
     * 跳转到代码
     */
    private fun navigateToCode(row: Int) {
        val modelRow = table.convertRowIndexToModel(row)
        if (modelRow >= 0 && modelRow < tableModel.rowCount) {
            // 这里需要保存PsiElement引用才能跳转
            // 简化实现：通过文件名和行号跳转
            val fileName = tableModel.getValueAt(modelRow, 0) as String
            val lineNumber = (tableModel.getValueAt(modelRow, 3) as Int) - 1
            
            // 查找文件并跳转
            val virtualFiles = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
                fileName,
                GlobalSearchScope.projectScope(project)
            )
            
            virtualFiles.firstOrNull()?.let { virtualFile ->
                val descriptor = OpenFileDescriptor(project, virtualFile, lineNumber, 0)
                descriptor.navigate(true)
            }
        }
    }
    
    /**
     * 显示问题通知
     */
    private fun showProblemsNotification(count: Int) {
        val notification = com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Orika Inspection")
            .createNotification(
                "发现 $count 个Orika类型不匹配问题",
                "请在'Orika检查结果'窗口中查看详情并修复",
                com.intellij.notification.NotificationType.ERROR
            )
        
        notification.notify(project)
    }
    
    /**
     * 查找项目中注册的所有自定义转换器
     */
    private fun findRegisteredConverters(project: Project): List<ConverterInfo> {
        val converters = mutableListOf<ConverterInfo>()
        
        try {
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
                    override fun visitClass(aClass: PsiClass) {
                        super.visitClass(aClass)
                        
                        // 查找实现了Converter接口的类
                        for (implementsType in aClass.implementsListTypes) {
                            if (implementsType is PsiClassType && 
                                (implementsType.canonicalText.contains("Converter") || 
                                 implementsType.canonicalText.contains("CustomMapper"))) {
                                val parameters = implementsType.parameters
                                if (parameters.size >= 2) {
                                    converters.add(ConverterInfo(parameters[0], parameters[1]))
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        
        return converters
    }
    
    /**
     * 在单个文件中查找字段级别的映射配置（性能优化版）
     */
    private fun findFieldMappingConfigsInFile(javaFile: PsiJavaFile, sourceClass: String, targetClass: String): Set<String> {
        val configuredFields = mutableSetOf<String>()
        
        try {
            javaFile.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    
                    val methodName = expression.methodExpression.referenceName
                    
                    if (methodName == "exclude" || methodName == "excludeField" || 
                        methodName == "fieldMap" || methodName == "field") {
                        val args = expression.argumentList.expressions
                        if (args.isNotEmpty()) {
                            val fieldName = when (val arg = args[0]) {
                                is PsiLiteralExpression -> arg.value as? String
                                is PsiReferenceExpression -> arg.referenceName
                                else -> null
                            }
                            if (fieldName != null) {
                                configuredFields.add(fieldName)
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 忽略异常
        }
        
        return configuredFields
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
            if (typesMatch(sourceType, converter.sourceType) && 
                typesMatch(targetType, converter.targetType)) {
                return true
            }
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
        
        if (isBoxingCompatible(type1, type2)) {
            return true
        }
        
        return false
    }
    
    /**
     * 检查在方法内是否有对指定字段的手动赋值
     */
    private fun hasManualFieldAssignment(
        mapCallExpression: PsiMethodCallExpression,
        fieldName: String,
        containingMethod: PsiMethod
    ): Boolean {
        // 获取map调用的返回值赋值变量
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
     */
    private fun findAssignmentVariable(mapCallExpression: PsiMethodCallExpression): PsiVariable? {
        var parent = mapCallExpression.parent
        
        while (parent != null) {
            when (parent) {
                is PsiLocalVariable -> return parent
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
                is PsiReturnStatement -> return null
                is PsiExpressionStatement -> {
                    parent = parent.parent
                    continue
                }
                else -> parent = parent.parent
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
        fileName: String,
        className: String,
        methodName: String,
        lineNumber: Int,
        psiElement: PsiElement,
        converters: List<ConverterInfo>,
        problems: MutableList<OrikalProblem>
    ) {
        // 只检查自定义类（非基本类型、非String、非包装类等）
        if (!isCustomClass(sourceFieldType) || !isCustomClass(targetFieldType)) {
            return
        }
        
        val sourceClassName = sourceFieldType.canonicalText.substringBefore('<')
        val targetClassName = targetFieldType.canonicalText.substringBefore('<')
        
        // 检查是否已经检查过这对类（避免循环引用和重复检查）
        val classPairKey = "$sourceClassName->$targetClassName"
        if (checkedClassPairs.contains(classPairKey)) {
            return
        }
        checkedClassPairs.add(classPairKey)
        
        // 查找源类和目标类
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
                
                problems.add(OrikalProblem(
                    fileName = fileName,
                    className = className,
                    methodName = methodName,
                    lineNumber = lineNumber,
                    fieldName = fullFieldPath,  // 显示完整路径，如 "address.street"
                    sourceType = nestedSourceType.presentableText,
                    targetType = nestedTargetType.presentableText,
                    message = "嵌套对象字段类型不匹配",
                    psiElement = psiElement
                ))
            }
            
            // 继续递归检查更深层的嵌套对象
            checkNestedObjectRecursively(
                nestedSourceType,
                nestedTargetType,
                "$parentFieldName.$nestedFieldName",
                fileName,
                className,
                methodName,
                lineNumber,
                psiElement,
                converters,
                problems
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
    
    /**
     * 转换器信息
     */
    private data class ConverterInfo(
        val sourceType: PsiType,
        val targetType: PsiType
    )
    
    /**
     * 字段不匹配信息
     */
    private data class FieldMismatch(
        val fieldName: String,
        val sourceType: PsiType,
        val targetType: PsiType
    )
    
    /**
     * Orika问题数据类
     */
    private data class OrikalProblem(
        val fileName: String,
        val className: String,
        val methodName: String,
        val lineNumber: Int,
        val fieldName: String,
        val sourceType: String,
        val targetType: String,
        val message: String,
        val psiElement: PsiElement
    )
}

