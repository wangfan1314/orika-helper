package com.github.wangfan1314.orikahelper.callhierarchy.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.FileTypeIndex
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.hierarchy.call.CallerMethodsTreeStructure
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.call.CallHierarchyNodeDescriptor
import com.github.wangfan1314.orikahelper.callhierarchy.model.CallHierarchyNode
import com.github.wangfan1314.orikahelper.callhierarchy.model.CallHierarchyNodeType
import com.github.wangfan1314.orikahelper.services.OrikaMappingAnalyzer
import com.github.wangfan1314.orikahelper.model.MappingCall

/**
 * 调用层级分析器
 * 负责分析方法的调用链路，集成IDEA原生调用层级和Orika映射分析
 */
@Service(Service.Level.PROJECT)
class CallHierarchyAnalyzer(private val project: Project) {

    private val orikaMappingAnalyzer = project.getService(OrikaMappingAnalyzer::class.java)
    
    private val takeSize = 300  // 通用限制，用于引用、调用等
    private val methodTakeSize = 100  // 方法调用点限制，增加以支持多模块
    private val methodSize = 50  // Orika方法数量限制
    
    // 添加缓存以提高性能
    private val methodCallCache = mutableMapOf<String, List<PsiElement>>()
    private val methodReferenceCache = mutableMapOf<String, Collection<PsiReference>>()
    
    /**
     * 清除缓存（在每次分析开始时调用）
     */
    private fun clearCache() {
        methodCallCache.clear()
        methodReferenceCache.clear()
    }
    
    /**
     * 分析指定字段的完整调用链路
     * 利用IDEA原生Call Hierarchy API，并增强Orika映射处理
     */
    fun analyzeCallHierarchy(field: PsiField): CallHierarchyNode? {
        try {
            // 清除缓存，开始新的分析
            clearCache()
            
            // 创建根节点（字段节点）
            val rootNode = CallHierarchyNode(
                className = field.containingClass?.qualifiedName ?: "Unknown",
                methodName = field.name,
                displayName = "字段: ${field.containingClass?.name}.${field.name}",
                location = getElementLocation(field),
                nodeType = CallHierarchyNodeType.ROOT,
                psiElement = field
            )
            
            // 1. 直接查找涉及该字段的Orika映射
            analyzeDirectOrikaRelationsForField(field, rootNode)
            
            // 2. 查找字段的getter/setter方法并追踪调用链路
            analyzeFieldGetterSetterMethods(field, rootNode)
            
            // 3. 查找包含该字段的其他方法（保留原有功能）
            val containingMethods = findMethodsUsingField(field)
            
            for (method in containingMethods) {
                // 跳过getter/setter方法，避免重复
                if (!isGetterSetterMethod(method, field.name)) {
                    val methodNode = createMethodNode(method)
                    if (methodNode != null) {
                        rootNode.addChild(methodNode)
                        // 使用增强的调用链路分析（增加最大深度以追溯到Controller层）
                        analyzeMethodCallHierarchyWithOrikaSupport(field, method, methodNode, mutableSetOf(), 0, 15)
                    }
                }
            }
            
            return rootNode
            
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 带Orika支持的方法调用链路分析（使用IDEA原生Call Hierarchy API）
     */
    private fun analyzeMethodCallHierarchyWithOrikaSupport(
        originalField: PsiField,
        method: PsiMethod, 
        currentNode: CallHierarchyNode, 
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        
        val methodSignature = getMethodSignature(method)
        if (methodSignature in visitedMethods) {
            return // 避免循环引用
        }
        visitedMethods.add(methodSignature)
        
        try {
            // 检查当前方法是否包含Orika映射（但如果当前节点已经是ORIKA_METHOD类型就跳过，避免重复）
            if (containsOrikaMapping(method) && currentNode.nodeType != CallHierarchyNodeType.ORIKA_METHOD) {
                analyzeOrikaRelatedCallsForField(originalField, method, currentNode)
            }
            
            // 优先使用IDEA原生Call Hierarchy API（速度更快）
            useNativeCallHierarchyWithOrikaSupport(originalField, method, currentNode, visitedMethods, depth, maxDepth)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        visitedMethods.remove(methodSignature)
    }
    
    /**
     * 分析字段的getter/setter方法并追踪调用链路
     * 强制为每个字段创建虚拟的getter/setter节点，确保链路追踪的完整性
     */
    private fun analyzeFieldGetterSetterMethods(field: PsiField, rootNode: CallHierarchyNode) {
        try {
            val containingClass = field.containingClass ?: return
            val fieldName = field.name
            
            // 强制创建虚拟的getter/setter节点（不管是否有显式声明或Lombok注解）
            analyzeVirtualGetterSetterMethods(field, rootNode)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 强制为字段创建虚拟的getter/setter方法节点
     * 不管是否有显式声明或Lombok注解，都创建虚拟节点以确保链路追踪的完整性
     */
    private fun analyzeVirtualGetterSetterMethods(field: PsiField, rootNode: CallHierarchyNode) {
        try {
            val containingClass = field.containingClass ?: return
            val className = containingClass.qualifiedName ?: return
            val fieldName = field.name
            
            // 标准getter/setter方法名
            val getterName = "get${fieldName.replaceFirstChar { it.uppercase() }}"
            val setterName = "set${fieldName.replaceFirstChar { it.uppercase() }}"
            val booleanGetterName = "is${fieldName.replaceFirstChar { it.uppercase() }}"
            
            // 强制创建getter节点（不检查是否已有显式声明）
            // 检查字段类型来决定使用哪种getter
            // 注意：只有基本类型 boolean 才使用 isXxx()，包装类型 Boolean 使用 getXxx()
            val fieldType = field.type.canonicalText
            val shouldUseBooleanGetter = fieldType == "boolean"  // 只有基本类型才用isXxx
            
            if (shouldUseBooleanGetter) {
                // 创建 boolean 基本类型的 isXxx() getter节点
                val getterNode = CallHierarchyNode(
                    className = className,
                    methodName = booleanGetterName,
                    displayName = "$className.$booleanGetterName",
                    location = getElementLocation(field),
                    nodeType = CallHierarchyNodeType.GETTER_METHOD,
                    psiElement = field
                )
                rootNode.addChild(getterNode)
                
                // 查找调用并添加子节点
                val getterCalls = findMethodCallsInProject(className, booleanGetterName)
                addCallSiteNodes(getterCalls, getterNode, field)
            } else {
                // 创建普通 getXxx() getter节点（包括 Boolean 包装类型）
                val getterNode = CallHierarchyNode(
                    className = className,
                    methodName = getterName,
                    displayName = "$className.$getterName",
                    location = getElementLocation(field),
                    nodeType = CallHierarchyNodeType.GETTER_METHOD,
                    psiElement = field
                )
                rootNode.addChild(getterNode)
                
                // 查找调用并添加子节点
                val getterCalls = findMethodCallsInProject(className, getterName)
                addCallSiteNodes(getterCalls, getterNode, field)
            }
            
            // 强制创建setter节点（不检查是否已有显式声明）
            val setterNode = CallHierarchyNode(
                className = className,
                methodName = setterName,
                displayName = "$className.$setterName",
                location = getElementLocation(field),
                nodeType = CallHierarchyNodeType.SETTER_METHOD,
                psiElement = field
            )
            rootNode.addChild(setterNode)
            
            // 查找调用并添加子节点
            val setterCalls = findMethodCallsInProject(className, setterName)
            addCallSiteNodes(setterCalls, setterNode, field)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 为调用点创建子节点
     */
    private fun addCallSiteNodes(calls: List<PsiElement>, parentNode: CallHierarchyNode, field: PsiField) {
        val addedNodes = mutableSetOf<String>() // 用于去重的集合，格式为 "方法签名:行号"
        
        for (call in calls.take(takeSize)) { // 增加数量以支持多模块
            val callerMethod = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java)
            if (callerMethod != null) {
                // 计算调用点的行号
                val lineNumber = getLineNumber(call)
                val methodSignature = "${callerMethod.containingClass?.qualifiedName}.${callerMethod.name}"
                val nodeKey = "$methodSignature:$lineNumber"
                
                // 检查是否已经添加过相同的方法调用（相同方法+相同行号）
                if (!addedNodes.contains(nodeKey)) {
                    val callerNode = createMethodNodeWithCallSiteAndLineNumber(callerMethod, call, lineNumber)
                    if (callerNode != null) {
                        parentNode.addChild(callerNode)
                        addedNodes.add(nodeKey)
                        
                        // 继续追踪调用链路（使用合理深度平衡性能和完整性）
                        analyzeMethodCallHierarchyWithOrikaSupport(field, callerMethod, callerNode, mutableSetOf(), 1, 10)
                    }
                }
            }
        }
    }
    
    /**
     * 在项目中查找对指定方法的调用（使用原生API优化）
     */
    private fun findMethodCallsInProject(className: String, methodName: String): List<PsiElement> {
        // 使用缓存提高性能
        val cacheKey = "$className.$methodName"
        methodCallCache[cacheKey]?.let { return it }
        
        val calls = mutableListOf<PsiElement>()
        
        try {
            // 首先找到目标类
            val targetClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
            
            if (targetClass != null) {
                // 查找类中的方法（可能是Lombok生成的虚拟方法，也可能不存在）
                val methods = targetClass.findMethodsByName(methodName, false)
                
                if (methods.isNotEmpty()) {
                    // 方法存在（显式或Lombok生成），直接使用ReferencesSearch
                    for (method in methods.take(5)) {  // 处理更多重载方法
                        val references = ReferencesSearch.search(method, GlobalSearchScope.allScope(project))
                        for (ref in references.take(takeSize)) {  // 增加引用数以支持多模块
                            calls.add(ref.element)
                        }
                        if (calls.size >= takeSize) break
                    }
                } else {
                    // 方法不存在（可能是虚拟方法），使用文本搜索
                    // 但限制搜索范围，只搜索可能调用该类的文件
                    searchMethodCallsByText(className, methodName, calls)
                }
            }
            
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        // 保存到缓存
        methodCallCache[cacheKey] = calls
        return calls
    }
    
    /**
     * 通过文本搜索方法调用（快速版本）
     */
    private fun searchMethodCallsByText(className: String, methodName: String, calls: MutableList<PsiElement>) {
        try {
            // 使用更精确的搜索：直接搜索方法名的引用
            // 这比遍历所有文件快得多
            val facade = JavaPsiFacade.getInstance(project)
            val targetClass = facade.findClass(className, GlobalSearchScope.allScope(project)) ?: return
            
            // 直接搜索类的使用位置
            val classUsages = ReferencesSearch.search(targetClass, GlobalSearchScope.allScope(project))
            
            for (usage in classUsages.take(200)) {
                val element = usage.element
                val file = element.containingFile
                
                // 在使用该类的文件中快速查找方法调用
                file?.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        if (calls.size >= takeSize) return  // 达到限制就停止
                        
                        super.visitMethodCallExpression(expression)
                        
                        if (expression.methodExpression.referenceName == methodName) {
                            val qualifier = expression.methodExpression.qualifierExpression
                            if (qualifier != null) {
                                val qualifierType = qualifier.type?.canonicalText?.substringBefore('<')
                                if (qualifierType == className) {
                                    calls.add(expression)
                                }
                            }
                        }
                    }
                })
                
                if (calls.size >= takeSize) break
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 通过方法名直接搜索方法调用（备用方法）
     */
    private fun searchMethodCallsByName(className: String, methodName: String, calls: MutableList<PsiElement>) {
        try {
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
                GlobalSearchScope.allScope(project)
            )
            
            // 在每个文件中查找方法调用
            for (javaFile in javaFiles.take(200)) { // 增加数量以支持多模块
                javaFile.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(expression)
                        
                        // 检查方法名是否匹配
                        if (expression.methodExpression.referenceName == methodName) {
                            // 检查调用的对象类型是否匹配
                            val qualifierExpression = expression.methodExpression.qualifierExpression
                            if (qualifierExpression != null) {
                                val qualifierType = qualifierExpression.type?.canonicalText?.substringBefore('<')
                                if (qualifierType == className) {
                                    calls.add(expression)
                                }
                            } else {
                                // 检查是否是隐式this调用
                                val containingClass = PsiTreeUtil.getParentOfType(expression, PsiClass::class.java)
                                if (containingClass?.qualifiedName == className) {
                                    calls.add(expression)
                                }
                            }
                        }
                    }
                })
                
                if (calls.size >= 200) break // 增加数量以支持多模块
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    
    /**
     * 检查方法是否是指定字段的getter/setter方法
     */
    private fun isGetterSetterMethod(method: PsiMethod, fieldName: String): Boolean {
        val methodName = method.name
        val getterName = "get${fieldName.replaceFirstChar { it.uppercase() }}"
        val setterName = "set${fieldName.replaceFirstChar { it.uppercase() }}"
        val booleanGetterName = "is${fieldName.replaceFirstChar { it.uppercase() }}"
        
        return methodName == getterName || methodName == setterName || methodName == booleanGetterName
    }
    
    /**
     * 针对特定字段分析直接的Orika映射关系
     */
    private fun analyzeDirectOrikaRelationsForField(field: PsiField, rootNode: CallHierarchyNode) {
        try {
            val fieldClass = field.containingClass?.qualifiedName ?: return
            val fieldName = field.name
            
            // 查找所有涉及该字段所在类的Orika映射调用（包含PSI元素）
            val mappingCalls: List<MappingCall> = findOrikaMappingCallsWithPsi(field)
            
            for (mappingCall in mappingCalls.take(takeSize)) { // 增加数量限制以支持多模块
                // 获取包含映射的方法
                val containingMethod = PsiTreeUtil.getParentOfType(mappingCall.psiElement, PsiMethod::class.java)
                if (containingMethod != null) {
                    // 创建包含映射信息的方法节点
                    val orikaInfo = extractOrikaCallInfo(mappingCall.psiElement ?: continue)
                    val methodNode = CallHierarchyNode(
                        className = mappingCall.className,
                        methodName = containingMethod.name,
                        displayName = "🔗 ${mappingCall.className}.${containingMethod.name}(Orika映射: $orikaInfo)",
                        location = mappingCall.location, // 使用映射调用的位置，便于跳转到映射行
                        nodeType = CallHierarchyNodeType.ORIKA_METHOD,
                        psiElement = mappingCall.psiElement // 使用映射调用的PSI元素，便于跳转
                    )
                    rootNode.addChild(methodNode)
                    
                    // 从包含映射的方法继续追踪调用链路（查找谁调用了这个方法）
                    // 使用合理的深度平衡性能和完整性
                    analyzeMethodCallHierarchyWithOrikaSupport(field, containingMethod, methodNode, mutableSetOf(), 1, 10)
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 查找涉及指定字段的Orika映射调用（优化版：只搜索包含类引用的文件）
     */
    private fun findOrikaMappingCallsWithPsi(field: PsiField): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        val fieldClass = field.containingClass?.qualifiedName ?: return calls
        val fieldName = field.name
        
        try {
            // 优化：只搜索使用了该类的文件，而不是所有Java文件
            val targetClass = JavaPsiFacade.getInstance(project).findClass(fieldClass, GlobalSearchScope.allScope(project))
            
            if (targetClass != null) {
                val classReferences = ReferencesSearch.search(targetClass, GlobalSearchScope.allScope(project))
                val processedFiles = mutableSetOf<PsiFile>()
                
                // 只处理引用了目标类的文件（这些文件更可能包含Orika映射）
                for (ref in classReferences.take(takeSize)) {  // 增加数量以支持多模块
                    val file = ref.element.containingFile
                    if (file is PsiJavaFile && file !in processedFiles) {
                        processedFiles.add(file)
                        
                        file.accept(object : JavaRecursiveElementVisitor() {
                            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                                if (calls.size >= methodSize) return  // 找到足够多就停止
                                
                                super.visitMethodCallExpression(expression)
                                
                                if (isOrikaMapCall(expression)) {
                                    val args = expression.argumentList.expressions
                                    if (args.size >= 2) {
                                        val sourceType = extractTypeFromExpression(args[0])
                                        val targetType = extractTypeFromExpression(args[1])
                                        
                                        // 检查这个映射调用是否与我们关注的字段相关
                                        val isFieldRelated = (sourceType == fieldClass && targetType != null && findFieldInClass(targetType, fieldName) != null) ||
                                                           (targetType == fieldClass && sourceType != null && findFieldInClass(sourceType, fieldName) != null)
                                        
                                        if (isFieldRelated) {
                                            val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                                            if (containingMethod != null) {
                                                calls.add(MappingCall(
                                                    methodName = containingMethod.name,
                                                    className = containingMethod.containingClass?.qualifiedName ?: "Unknown",
                                                    location = getElementLocation(expression),
                                                    callType = "ORIKA_MAPPING",
                                                    psiElement = expression
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        })
                        
                        if (calls.size >= methodSize) break  // 找到足够多就停止
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return calls
    }
    
    /**
     * 针对特定字段分析Orika相关的调用
     */
    private fun analyzeOrikaRelatedCallsForField(originalField: PsiField, method: PsiMethod, currentNode: CallHierarchyNode) {
        try {
            val fieldName = originalField.name
            val fieldClass = originalField.containingClass?.qualifiedName ?: return
            
            // 查找方法中的Orika映射调用
            val orikaReferences = findOrikaReferencesInMethod(method)
            
            for (reference in orikaReferences.take(methodSize)) {  // 增加数量以支持多模块
                if (reference is PsiMethodCallExpression) {
                    val args = reference.argumentList.expressions
                    if (args.size >= 2) {
                        val sourceType = extractTypeFromExpression(args[0])
                        val targetType = extractTypeFromExpression(args[1])
                        
                        // 检查这个映射调用是否与我们关注的字段相关
                        val isFieldRelated = (sourceType == fieldClass && targetType != null && findFieldInClass(targetType, fieldName) != null) ||
                                           (targetType == fieldClass && sourceType != null && findFieldInClass(sourceType, fieldName) != null)
                        
                        if (isFieldRelated) {
                            val containingClass = PsiTreeUtil.getParentOfType(reference, PsiClass::class.java)
                            val orikaNode = CallHierarchyNode(
                                className = containingClass?.qualifiedName ?: "Unknown",
                                methodName = "orika.map",
                                displayName = "Orika映射: ${extractOrikaCallInfo(reference)}",
                                location = getElementLocation(reference),
                                nodeType = CallHierarchyNodeType.ORIKA_MAPPING,
                                psiElement = reference
                            )
                            currentNode.addChild(orikaNode)
                            
                            // 从映射的目标类型继续追踪
                            val relevantTargetType = if (sourceType == fieldClass) targetType else sourceType
                            if (relevantTargetType != null) {
                                val targetClass = JavaPsiFacade.getInstance(project).findClass(relevantTargetType, GlobalSearchScope.allScope(project))
                                if (targetClass != null) {
                                    val targetField = targetClass.findFieldByName(fieldName, true)
                                    if (targetField != null) {
                                        continueCallHierarchyFromOrikaTargetField(targetField, orikaNode, reference)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 从Orika映射的目标字段继续追踪调用链路
     */
    private fun continueCallHierarchyFromOrikaTargetField(
        targetField: PsiField, 
        orikaNode: CallHierarchyNode, 
        orikaReference: PsiElement
    ) {
        try {
            // 查找使用目标字段的方法
            val targetMethods = findMethodsUsingField(targetField)
            val orikaContainingMethod = PsiTreeUtil.getParentOfType(orikaReference, PsiMethod::class.java)
            
            for (targetMethod in targetMethods.take(methodSize)) {  // 增加数量以支持多模块
                if (targetMethod != orikaContainingMethod) {
                    val targetMethodNode = createMethodNode(targetMethod)
                    if (targetMethodNode != null) {
                        orikaNode.addChild(targetMethodNode)
                        
                        // 继续向上追踪（使用合理深度平衡性能和完整性）
                        if (!isControllerMethod(targetMethod)) {
                            analyzeMethodCallHierarchyWithOrikaSupport(targetField, targetMethod, targetMethodNode, mutableSetOf(), 1, 10)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 在指定类中查找字段
     */
    private fun findFieldInClass(className: String, fieldName: String): PsiField? {
        try {
            val classes = JavaPsiFacade.getInstance(project).findClasses(className, GlobalSearchScope.allScope(project))
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
     * 使用IDEA原生Call Hierarchy API分析方法调用链路
     */
    private fun analyzeMethodCallHierarchyNative(
        method: PsiMethod, 
        currentNode: CallHierarchyNode, 
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        
        val methodSignature = getMethodSignature(method)
        if (methodSignature in visitedMethods) {
            return // 避免循环引用
        }
        visitedMethods.add(methodSignature)
        
        try {
            // 使用IDEA原生的Call Hierarchy API（性能最优）
            val treeStructure = CallerMethodsTreeStructure(project, method, "com.intellij.ide.hierarchy.JavaHierarchyUtil.getScopeProject")
            val baseDescriptor = treeStructure.baseDescriptor as? CallHierarchyNodeDescriptor
            
            if (baseDescriptor != null) {
                val childElements = treeStructure.getChildElements(baseDescriptor)
                
                for (element in childElements.take(takeSize)) {
                    if (element is CallHierarchyNodeDescriptor) {
                        val callerMethod = element.enclosingElement as? PsiMethod
                        if (callerMethod != null && callerMethod != method) {
                            val callerSig = getMethodSignature(callerMethod)
                            if (callerSig !in visitedMethods) {
                                val psiReference = element.psiElement
                                val lineNumber = if (psiReference != null) getLineNumber(psiReference) else 0
                                
                                val callerNode = createMethodNodeWithCallSiteAndLineNumber(
                                    callerMethod,
                                    psiReference ?: callerMethod,
                                    lineNumber
                                )
                                
                                if (callerNode != null) {
                                    currentNode.addChild(callerNode)
                                    
                                    if (!isControllerMethod(callerMethod)) {
                                        analyzeMethodCallHierarchyNative(callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 如果原生API失败，降级到传统搜索
            fallbackToSimpleReferenceSearch(method, currentNode, visitedMethods, depth, maxDepth)
        }
        
        visitedMethods.remove(methodSignature)
    }
    
    /**
     * 使用IDEA原生Call Hierarchy API快速获取调用者（带Orika支持）
     */
    private fun useNativeCallHierarchyWithOrikaSupport(
        originalField: PsiField,
        method: PsiMethod,
        currentNode: CallHierarchyNode,
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        
        val methodSignature = getMethodSignature(method)
        if (methodSignature in visitedMethods) return
        visitedMethods.add(methodSignature)
        
        try {
            // 使用IDEA原生的Call Hierarchy API获取调用者
            val treeStructure = CallerMethodsTreeStructure(project, method, "com.intellij.ide.hierarchy.JavaHierarchyUtil.getScopeProject")
            val baseDescriptor = treeStructure.baseDescriptor as? CallHierarchyNodeDescriptor
            
            if (baseDescriptor != null) {
                val childElements = treeStructure.getChildElements(baseDescriptor)
                
                // 处理每个调用者（限制数量以保持性能）
                for (element in childElements.take(takeSize)) {
                    if (element is CallHierarchyNodeDescriptor) {
                        val callerMethod = element.enclosingElement as? PsiMethod
                        if (callerMethod != null && callerMethod != method) {
                            val callerSig = getMethodSignature(callerMethod)
                            if (callerSig !in visitedMethods) {
                                // 创建节点
                                val psiReference = element.psiElement
                                val lineNumber = if (psiReference != null) getLineNumber(psiReference) else 0
                                
                                val callerNode = createMethodNodeWithCallSiteAndLineNumber(
                                    callerMethod, 
                                    psiReference ?: callerMethod,
                                    lineNumber
                                )
                                
                                if (callerNode != null) {
                                    currentNode.addChild(callerNode)
                                    
                                    // 检查是否包含Orika映射
                                    if (containsOrikaMapping(callerMethod)) {
                                        analyzeOrikaRelatedCallsForField(originalField, callerMethod, callerNode)
                                    }
                                    
                                    // 继续递归追踪（除非已经是Controller）
                                    if (!isControllerMethod(callerMethod)) {
                                        useNativeCallHierarchyWithOrikaSupport(
                                            originalField,
                                            callerMethod,
                                            callerNode,
                                            visitedMethods,
                                            depth + 1,
                                            maxDepth
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 如果原生API失败，降级到传统搜索
            fallbackToSimpleReferenceSearchWithOrikaSupport(originalField, method, currentNode, visitedMethods, depth, maxDepth)
        }
        
        visitedMethods.remove(methodSignature)
    }
    
    /**
     * 降级到简单的引用搜索（带Orika支持）
     */
    private fun fallbackToSimpleReferenceSearchWithOrikaSupport(
        originalField: PsiField,
        method: PsiMethod, 
        currentNode: CallHierarchyNode, 
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        
        try {
            val callerMethods = mutableSetOf<PsiMethod>()
            
            // 1. 查找直接的方法引用，并记录调用位置（使用缓存）
            val methodSig = getMethodSignature(method)
            val directReferences = methodReferenceCache.getOrPut(methodSig) {
                ReferencesSearch.search(method, GlobalSearchScope.allScope(project)).findAll()
            }
            val methodToCallSites = mutableMapOf<PsiMethod, MutableList<PsiElement>>()
            
            for (reference in directReferences) {
                val element = reference.element
                val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (callerMethod != null && callerMethod != method) {
                    methodToCallSites.computeIfAbsent(callerMethod) { mutableListOf() }.add(element)
                    callerMethods.add(callerMethod)
                }
            }
            
            // 2. 如果当前方法实现了接口方法，也要查找接口方法的调用
            val interfaceMethods = findInterfaceMethodsForImplementation(method)
            for (interfaceMethod in interfaceMethods) {
                val interfaceReferences = ReferencesSearch.search(interfaceMethod, GlobalSearchScope.allScope(project))
                for (reference in interfaceReferences) {
                    val element = reference.element
                    val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    if (callerMethod != null && callerMethod != method && callerMethod != interfaceMethod) {
                        methodToCallSites.computeIfAbsent(callerMethod) { mutableListOf() }.add(element)
                        callerMethods.add(callerMethod)
                    }
                }
            }
            
            // 3. 如果当前方法是接口方法，查找其实现类的调用
            if (method.containingClass?.isInterface == true) {
                val implementations = findImplementationMethods(method)
                for (impl in implementations) {
                    // 递归查找实现方法的调用者
                    fallbackToSimpleReferenceSearchWithOrikaSupport(originalField, impl, currentNode, visitedMethods, depth, maxDepth)
                }
            }
            
            // 使用去重逻辑处理调用者（增加数量以支持多模块）
            addCallerNodesWithDeduplication(callerMethods.take(takeSize), methodToCallSites, currentNode, originalField, visitedMethods, depth, maxDepth)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 添加调用者节点并进行去重
     */
    private fun addCallerNodesWithDeduplication(
        callerMethods: List<PsiMethod>,
        methodToCallSites: Map<PsiMethod, List<PsiElement>>,
        currentNode: CallHierarchyNode,
        originalField: PsiField,
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        val addedNodes = mutableSetOf<String>() // 用于去重的集合，格式为 "方法签名:行号"
        
        for (callerMethod in callerMethods) {
            val callSites = methodToCallSites[callerMethod] ?: emptyList()
            
            if (callSites.isNotEmpty()) {
                // 如果一个方法有多个调用点，为每个调用点创建一个节点
                for (callSite in callSites.take(methodTakeSize)) { // 增加每个方法的调用点数量以支持多模块
                    val lineNumber = getLineNumber(callSite)
                    val methodSignature = "${callerMethod.containingClass?.qualifiedName}.${callerMethod.name}"
                    val nodeKey = "$methodSignature:$lineNumber"
                    
                    // 检查是否已经添加过相同的方法调用（相同方法+相同行号）
                    if (!addedNodes.contains(nodeKey)) {
                        val callerNode = createMethodNodeWithCallSiteAndLineNumber(callerMethod, callSite, lineNumber)
                        if (callerNode != null) {
                            currentNode.addChild(callerNode)
                            addedNodes.add(nodeKey)
                            
                            // 检查是否包含Orika映射（避免在ORIKA_METHOD节点下重复添加）
                            if (containsOrikaMapping(callerMethod) && callerNode.nodeType != CallHierarchyNodeType.ORIKA_METHOD) {
                                analyzeOrikaRelatedCallsForField(originalField, callerMethod, callerNode)
                            }
                            
                            if (!isControllerMethod(callerMethod)) {
                                analyzeMethodCallHierarchyWithOrikaSupport(originalField, callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                            }
                        }
                    }
                }
            } else {
                // 如果没有调用点信息，使用原来的方法
                val methodSignature = "${callerMethod.containingClass?.qualifiedName}.${callerMethod.name}"
                val nodeKey = "$methodSignature:0" // 使用0作为未知行号的标识
                
                if (!addedNodes.contains(nodeKey)) {
                    val callerNode = createMethodNode(callerMethod)
                    if (callerNode != null) {
                        currentNode.addChild(callerNode)
                        addedNodes.add(nodeKey)
                        
                        // 检查是否包含Orika映射（避免在ORIKA_METHOD节点下重复添加）
                        if (containsOrikaMapping(callerMethod) && callerNode.nodeType != CallHierarchyNodeType.ORIKA_METHOD) {
                            analyzeOrikaRelatedCallsForField(originalField, callerMethod, callerNode)
                        }
                    
                        if (!isControllerMethod(callerMethod)) {
                            analyzeMethodCallHierarchyWithOrikaSupport(originalField, callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 降级到简单的引用搜索（当原生API失败时）
     */
    private fun fallbackToSimpleReferenceSearch(
        method: PsiMethod, 
        currentNode: CallHierarchyNode, 
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        
        try {
            val callerMethods = mutableSetOf<PsiMethod>()
            
            // 1. 查找直接的方法引用，并记录调用位置
            val directReferences = ReferencesSearch.search(method, GlobalSearchScope.allScope(project))
            val methodToCallSites = mutableMapOf<PsiMethod, MutableList<PsiElement>>()
            
            for (reference in directReferences) {
                val element = reference.element
                val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (callerMethod != null && callerMethod != method) {
                    methodToCallSites.computeIfAbsent(callerMethod) { mutableListOf() }.add(element)
                    callerMethods.add(callerMethod)
                }
            }
            
            // 2. 如果当前方法实现了接口方法，也要查找接口方法的调用
            val interfaceMethods = findInterfaceMethodsForImplementation(method)
            for (interfaceMethod in interfaceMethods) {
                val interfaceReferences = ReferencesSearch.search(interfaceMethod, GlobalSearchScope.allScope(project))
                for (reference in interfaceReferences) {
                    val element = reference.element
                    val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    if (callerMethod != null && callerMethod != method && callerMethod != interfaceMethod) {
                        methodToCallSites.computeIfAbsent(callerMethod) { mutableListOf() }.add(element)
                        callerMethods.add(callerMethod)
                    }
                }
            }
            
            // 3. 如果当前方法是接口方法，查找其实现类的调用
            if (method.containingClass?.isInterface == true) {
                val implementations = findImplementationMethods(method)
                for (impl in implementations) {
                    // 递归查找实现方法的调用者
                    fallbackToSimpleReferenceSearch(impl, currentNode, visitedMethods, depth, maxDepth)
                }
            }
            
            // 使用去重逻辑处理调用者（增加数量以支持多模块）
            addSimpleCallerNodesWithDeduplication(callerMethods.take(takeSize), methodToCallSites, currentNode, visitedMethods, depth, maxDepth)
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 添加简单调用者节点并进行去重（用于fallbackToSimpleReferenceSearch）
     */
    private fun addSimpleCallerNodesWithDeduplication(
        callerMethods: List<PsiMethod>,
        methodToCallSites: Map<PsiMethod, List<PsiElement>>,
        currentNode: CallHierarchyNode,
        visitedMethods: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        val addedNodes = mutableSetOf<String>() // 用于去重的集合，格式为 "方法签名:行号"
        
        for (callerMethod in callerMethods) {
            val callSites = methodToCallSites[callerMethod] ?: emptyList()
            
            if (callSites.isNotEmpty()) {
                // 如果一个方法有多个调用点，为每个调用点创建一个节点
                for (callSite in callSites.take(methodTakeSize)) { // 增加每个方法的调用点数量以支持多模块
                    val lineNumber = getLineNumber(callSite)
                    val methodSignature = "${callerMethod.containingClass?.qualifiedName}.${callerMethod.name}"
                    val nodeKey = "$methodSignature:$lineNumber"
                    
                    // 检查是否已经添加过相同的方法调用（相同方法+相同行号）
                    if (!addedNodes.contains(nodeKey)) {
                        val callerNode = createMethodNodeWithCallSiteAndLineNumber(callerMethod, callSite, lineNumber)
                        if (callerNode != null) {
                            currentNode.addChild(callerNode)
                            addedNodes.add(nodeKey)
                            
                            // 检查是否包含Orika映射（避免在ORIKA_METHOD节点下重复添加）
                            if (containsOrikaMapping(callerMethod) && callerNode.nodeType != CallHierarchyNodeType.ORIKA_METHOD) {
                                analyzeOrikaRelatedCalls(callerMethod, callerNode)
                            }
                            
                            if (!isControllerMethod(callerMethod)) {
                                analyzeMethodCallHierarchyNative(callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                            }
                        }
                    }
                }
            } else {
                // 如果没有调用点信息，使用原来的方法
                val methodSignature = "${callerMethod.containingClass?.qualifiedName}.${callerMethod.name}"
                val nodeKey = "$methodSignature:0" // 使用0作为未知行号的标识
                
                if (!addedNodes.contains(nodeKey)) {
                    val callerNode = createMethodNode(callerMethod)
                    if (callerNode != null) {
                        currentNode.addChild(callerNode)
                        addedNodes.add(nodeKey)
                        
                        // 检查是否包含Orika映射（避免在ORIKA_METHOD节点下重复添加）
                        if (containsOrikaMapping(callerMethod) && callerNode.nodeType != CallHierarchyNodeType.ORIKA_METHOD) {
                            analyzeOrikaRelatedCalls(callerMethod, callerNode)
                        }
                        
                        if (!isControllerMethod(callerMethod)) {
                            analyzeMethodCallHierarchyNative(callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 检查方法是否包含Orika映射调用
     */
    private fun containsOrikaMapping(method: PsiMethod): Boolean {
        try {
            var hasOrikaMapping = false
            method.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    if (isOrikaMapCall(expression)) {
                        hasOrikaMapping = true
                    }
                }
            })
            return hasOrikaMapping
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 分析Orika相关的调用（增强版本）
     */
    private fun analyzeOrikaRelatedCalls(method: PsiMethod, currentNode: CallHierarchyNode) {
        try {
            // 快速检查方法中是否包含Orika映射调用
            val orikaReferences = findOrikaReferencesInMethod(method)
            
            for (reference in orikaReferences.take(methodSize)) {  // 增加数量以支持多模块
                val containingClass = PsiTreeUtil.getParentOfType(reference, PsiClass::class.java)
                val orikaNode = CallHierarchyNode(
                    className = containingClass?.qualifiedName ?: "Unknown",
                    methodName = "orika.map",
                    displayName = "Orika映射: ${extractOrikaCallInfo(reference)}",
                    location = getElementLocation(reference),
                    nodeType = CallHierarchyNodeType.ORIKA_MAPPING,
                    psiElement = reference
                )
                currentNode.addChild(orikaNode)
                
                // 增强的Orika映射目标类型分析 - 继续追踪映射后的调用链路
                analyzeOrikaTargetTypeUsageSimplified(reference, orikaNode)
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 提取Orika调用信息
     */
    private fun extractOrikaCallInfo(orikaReference: PsiElement): String {
        if (orikaReference is PsiMethodCallExpression) {
            val args = orikaReference.argumentList.expressions
            if (args.size >= 2) {
                val sourceType = extractSimpleTypeName(args[0])
                val targetType = extractSimpleTypeName(args[1])
                return "$sourceType → $targetType"
            }
        }
        return "映射调用"
    }
    
    /**
     * 提取简单类型名称
     */
    private fun extractSimpleTypeName(expression: PsiExpression): String? {
        return when (expression) {
            is PsiClassObjectAccessExpression -> {
                expression.operand.type.presentableText
            }
            is PsiReferenceExpression -> {
                expression.type?.presentableText
            }
            else -> expression.type?.presentableText
        }
    }
    
    /**
     * 增强的Orika映射目标类型分析 - 继续追踪Orika映射后的调用链路
     */
    private fun analyzeOrikaTargetTypeUsageSimplified(orikaReference: PsiElement, orikaNode: CallHierarchyNode) {
        try {
            // 获取Orika映射的目标类型
            val targetClass = extractOrikaTargetType(orikaReference)
            if (targetClass != null) {
                // 继续从目标类型开始追踪调用链路
                continueCallHierarchyFromOrikaTarget(targetClass, orikaNode, orikaReference)
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 从Orika映射的目标类型继续追踪调用链路
     */
    private fun continueCallHierarchyFromOrikaTarget(
        targetClass: PsiClass, 
        orikaNode: CallHierarchyNode, 
        orikaReference: PsiElement
    ) {
        try {
            // 获取目标类的所有方法，重点关注 getter/setter 方法
            val targetMethods = targetClass.methods.filter { method ->
                val methodName = method.name
                methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")
            }
            
            // 对每个目标方法，查找它们的调用者
            for (targetMethod in targetMethods.take(methodSize)) { // 增加方法数量以支持多模块
                val callers = findMethodCallers(targetMethod)
                
                for (caller in callers.take(methodTakeSize)) { // 增加调用者数量以支持多模块
                    // 确保不会回到原始的Orika映射方法
                    val orikaContainingMethod = PsiTreeUtil.getParentOfType(orikaReference, PsiMethod::class.java)
                    if (caller != orikaContainingMethod) {
                        val callerNode = createMethodNode(caller)
                        if (callerNode != null) {
                            orikaNode.addChild(callerNode)
                            
                            // 继续向上追踪调用链路（使用合理深度平衡性能和完整性）
                            if (!isControllerMethod(caller)) {
                                analyzeMethodCallHierarchyNative(caller, callerNode, mutableSetOf(), 1, 10)
                            }
                        }
                    }
                }
            }
            
            // 另外，还要查找直接使用目标类型的代码
            findDirectUsagesOfTargetType(targetClass, orikaNode, orikaReference)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 查找方法的调用者
     */
    private fun findMethodCallers(method: PsiMethod): List<PsiMethod> {
        val callers = mutableListOf<PsiMethod>()
        try {
            val references = ReferencesSearch.search(method, GlobalSearchScope.allScope(project))
            
            for (reference in references.take(takeSize)) { // 增加数量以支持多模块
                val element = reference.element
                val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (callerMethod != null && callerMethod != method && callerMethod !in callers) {
                    callers.add(callerMethod)
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        return callers
    }
    
    /**
     * 查找直接使用目标类型的代码
     */
    private fun findDirectUsagesOfTargetType(
        targetClass: PsiClass, 
        orikaNode: CallHierarchyNode, 
        orikaReference: PsiElement
    ) {
        try {
            // 查找目标类型被使用的地方（变量声明、方法参数等）
            val references = ReferencesSearch.search(targetClass, GlobalSearchScope.allScope(project))
            
            val orikaContainingMethod = PsiTreeUtil.getParentOfType(orikaReference, PsiMethod::class.java)
            
            for (reference in references.take(takeSize)) { // 增加数量以支持多模块
                val element = reference.element
                val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                
                if (containingMethod != null && 
                    containingMethod != orikaContainingMethod && 
                    !isSimpleGetterSetter(containingMethod)) {
                    
                    // 检查这个方法是否确实在处理目标类型的实例
                    if (isMethodProcessingTargetType(containingMethod, targetClass)) {
                        val usageNode = createMethodNode(containingMethod)
                        if (usageNode != null) {
                            orikaNode.addChild(usageNode)
                            
                            // 继续向上追踪（使用合理深度平衡性能和完整性）
                            if (!isControllerMethod(containingMethod)) {
                                analyzeMethodCallHierarchyNative(containingMethod, usageNode, mutableSetOf(), 1, 10)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
    }
    
    /**
     * 检查方法是否是简单的getter/setter
     */
    private fun isSimpleGetterSetter(method: PsiMethod): Boolean {
        val methodName = method.name
        val body = method.body
        
        // 简单检查：方法体非常短且是getter/setter命名模式
        return (methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")) &&
               (body == null || body.statements.size <= 2)
    }
    
    /**
     * 检查方法是否在处理目标类型
     */
    private fun isMethodProcessingTargetType(method: PsiMethod, targetClass: PsiClass): Boolean {
        try {
            val targetClassName = targetClass.qualifiedName ?: return false
            
            // 检查方法参数中是否包含目标类型
            for (parameter in method.parameterList.parameters) {
                val paramType = parameter.type.canonicalText.substringBefore('<')
                if (paramType == targetClassName) {
                    return true
                }
            }
            
            // 检查方法返回类型
            val returnType = method.returnType?.canonicalText?.substringBefore('<')
            if (returnType == targetClassName) {
                return true
            }
            
            // 检查方法体中的变量声明
            method.body?.accept(object : JavaRecursiveElementVisitor() {
                override fun visitLocalVariable(variable: PsiLocalVariable) {
                    super.visitLocalVariable(variable)
                    val varType = variable.type.canonicalText.substringBefore('<')
                    if (varType == targetClassName) {
                        return // 找到了使用目标类型的地方
                    }
                }
            })
            
            return false
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 在指定文件中查找类型使用
     */
    private fun findTypeUsagesInFile(psiClass: PsiClass, file: PsiFile): List<PsiElement> {
        val usages = mutableListOf<PsiElement>()
        try {
            file.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    val resolved = expression.resolve()
                    if (resolved == psiClass) {
                        usages.add(expression)
                    }
                }
            })
        } catch (e: Exception) {
            // 静默处理异常
        }
        return usages
    }
    
    /**
     * 查找使用指定字段的方法（优化版本）
     */
    private fun findMethodsUsingField(field: PsiField): List<PsiMethod> {
        val methods = mutableListOf<PsiMethod>()
        
        try {
            // 使用 allScope 以支持多模块项目的跨模块搜索
            // 在多模块项目中，字段可能被其他模块使用
            val searchScope = GlobalSearchScope.allScope(project)
            
            // 查找字段的引用，限制结果数量
            val references = ReferencesSearch.search(field, searchScope)
            
            var count = 0
            for (reference in references) {
                if (count >= takeSize) break // 增加数量以支持多模块
                
                val element = reference.element
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (method != null && method !in methods) {
                    methods.add(method)
                    count++
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return methods
    }
    
    
    /**
     * 在方法中查找Orika引用（优化版本）
     */
    private fun findOrikaReferencesInMethod(method: PsiMethod): List<PsiElement> {
        val references = mutableListOf<PsiElement>()
        
        try {
            var count = 0
            method.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    if (count >= 5) return // 限制查找数量
                    super.visitMethodCallExpression(expression)
                    
                    // 检查是否是Orika映射调用
                    if (isOrikaMapCall(expression)) {
                        references.add(expression)
                        count++
                    }
                }
            })
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return references
    }
    
    /**
     * 检查是否是Orika映射调用
     */
    private fun isOrikaMapCall(expression: PsiMethodCallExpression): Boolean {
        val methodExpression = expression.methodExpression
        val methodName = methodExpression.referenceName
        
        if (methodName != "map") {
            return false
        }
        
        // 检查调用链是否包含典型的Orika模式
        val qualifierText = methodExpression.qualifierExpression?.text?.lowercase()
        
        return qualifierText != null && (
            qualifierText.contains("mapper") ||
            qualifierText.contains("orika") ||
            qualifierText.contains("getmapperfacade") ||
            qualifierText.contains("mapperfactory")
        )
    }
    
    /**
     * 提取Orika映射的目标类型
     */
    private fun extractOrikaTargetType(orikaReference: PsiElement): PsiClass? {
        if (orikaReference is PsiMethodCallExpression) {
            val args = orikaReference.argumentList.expressions
            if (args.size >= 2) {
                // 第二个参数通常是目标类型
                val targetTypeArg = args[1]
                if (targetTypeArg is PsiClassObjectAccessExpression) {
                    val operand = targetTypeArg.operand
                    if (operand.type is PsiClassType) {
                        return (operand.type as PsiClassType).resolve()
                    }
                }
            }
        }
        return null
    }
    
    
    /**
     * 创建方法节点
     */
    private fun createMethodNode(method: PsiMethod): CallHierarchyNode? {
        try {
            val className = method.containingClass?.qualifiedName ?: "Unknown"
            val methodName = method.name
            val nodeType = determineNodeType(method)
            
            return CallHierarchyNode(
                className = className,
                methodName = methodName,
                displayName = "$className.$methodName",
                location = getElementLocation(method),
                nodeType = nodeType,
                psiElement = method
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 创建带有调用点信息的方法节点
     */
    private fun createMethodNodeWithCallSite(method: PsiMethod, callSite: PsiElement): CallHierarchyNode? {
        try {
            val className = method.containingClass?.qualifiedName ?: "Unknown"
            val methodName = method.name
            val nodeType = determineNodeType(method)
            
            return CallHierarchyNode(
                className = className,
                methodName = methodName,
                displayName = "$className.$methodName",
                location = getElementLocation(callSite), // 使用调用点的位置而不是方法定义的位置
                nodeType = nodeType,
                psiElement = callSite // 使用调用点的PSI元素用于跳转
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 创建带有调用点信息和行号的方法节点
     */
    private fun createMethodNodeWithCallSiteAndLineNumber(method: PsiMethod, callSite: PsiElement, lineNumber: Int): CallHierarchyNode? {
        try {
            val className = method.containingClass?.qualifiedName ?: "Unknown"
            val methodName = method.name
            val nodeType = determineNodeType(method)
            
            return CallHierarchyNode(
                className = className,
                methodName = methodName,
                displayName = "$className.$methodName:$lineNumber", // 在显示名称中包含行号
                location = getElementLocation(callSite), // 使用调用点的位置而不是方法定义的位置
                nodeType = nodeType,
                psiElement = callSite // 使用调用点的PSI元素用于跳转
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 获取PSI元素的行号
     */
    private fun getLineNumber(element: PsiElement): Int {
        return try {
            val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            if (document != null) {
                document.getLineNumber(element.textOffset) + 1
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    
    /**
     * 确定节点类型
     */
    private fun determineNodeType(method: PsiMethod): CallHierarchyNodeType {
        val className = method.containingClass?.qualifiedName ?: ""
        val methodName = method.name
        
        return when {
            isControllerMethod(method) -> CallHierarchyNodeType.CONTROLLER
            className.lowercase().contains("service") -> CallHierarchyNodeType.SERVICE
            className.lowercase().contains("repository") || 
            className.lowercase().contains("dao") -> CallHierarchyNodeType.REPOSITORY
            methodName == "<init>" -> CallHierarchyNodeType.CONSTRUCTOR_CALL
            methodName.startsWith("get") || methodName.startsWith("is") -> CallHierarchyNodeType.GETTER_METHOD
            methodName.startsWith("set") -> CallHierarchyNodeType.SETTER_METHOD
            else -> CallHierarchyNodeType.METHOD_CALL
        }
    }
    
    /**
     * 判断是否是Controller方法
     */
    private fun isControllerMethod(method: PsiMethod): Boolean {
        val containingClass = method.containingClass
        if (containingClass != null) {
            // 检查类名是否包含Controller
            val className = containingClass.qualifiedName ?: ""
            if (className.lowercase().contains("controller")) {
                return true
            }
            
            // 检查类注解
            for (annotation in containingClass.annotations) {
                val annotationName = annotation.qualifiedName
                if (annotationName?.endsWith("Controller") == true || 
                    annotationName?.endsWith("RestController") == true) {
                    return true
                }
            }
            
            // 检查方法注解
            for (annotation in method.annotations) {
                val annotationName = annotation.qualifiedName
                if (annotationName?.contains("Mapping") == true || 
                    annotationName?.contains("Get") == true ||
                    annotationName?.contains("Post") == true ||
                    annotationName?.contains("Put") == true ||
                    annotationName?.contains("Delete") == true) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 获取元素位置信息
     */
    private fun getElementLocation(element: PsiElement): String {
        val containingFile = element.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        
        return if (document != null) {
            val lineNumber = document.getLineNumber(element.textOffset) + 1
            "${containingFile.name}:$lineNumber"
        } else {
            containingFile.name
        }
    }
    
    /**
     * 查找实现类方法对应的接口方法
     */
    private fun findInterfaceMethodsForImplementation(method: PsiMethod): List<PsiMethod> {
        val interfaceMethods = mutableListOf<PsiMethod>()
        
        try {
            val containingClass = method.containingClass ?: return interfaceMethods
            
            // 获取类实现的所有接口
            val interfaces = containingClass.interfaces
            for (interfaceClass in interfaces) {
                // 在接口中查找同名同参数的方法
                val interfaceMethod = findMatchingMethodInInterface(method, interfaceClass)
                if (interfaceMethod != null) {
                    interfaceMethods.add(interfaceMethod)
                }
            }
            
            // 递归查找父类的接口
            val superClass = containingClass.superClass
            if (superClass != null && superClass.qualifiedName != "java.lang.Object") {
                val superInterfaces = findInterfaceMethodsForImplementation(
                    findMatchingMethodInClass(method, superClass) ?: return interfaceMethods
                )
                interfaceMethods.addAll(superInterfaces)
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return interfaceMethods
    }
    
    /**
     * 查找接口方法的所有实现
     */
    private fun findImplementationMethods(interfaceMethod: PsiMethod): List<PsiMethod> {
        val implementations = mutableListOf<PsiMethod>()
        
        try {
            val interfaceClass = interfaceMethod.containingClass ?: return implementations
            
            // 搜索接口的所有实现类
            val implementations_search = ClassInheritorsSearch.search(interfaceClass, GlobalSearchScope.allScope(project), false)
            
            for (implementationClass in implementations_search) {
                if (!implementationClass.isInterface) {
                    val implMethod = findMatchingMethodInClass(interfaceMethod, implementationClass)
                    if (implMethod != null) {
                        implementations.add(implMethod)
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return implementations.take(methodSize) // 增加数量以支持多模块项目
    }
    
    /**
     * 在接口中查找匹配的方法
     */
    private fun findMatchingMethodInInterface(method: PsiMethod, interfaceClass: PsiClass): PsiMethod? {
        return findMatchingMethodInClass(method, interfaceClass)
    }
    
    /**
     * 在类中查找匹配的方法（按方法名和参数类型匹配）
     */
    private fun findMatchingMethodInClass(method: PsiMethod, targetClass: PsiClass): PsiMethod? {
        try {
            val methodName = method.name
            val parameterTypes = method.parameterList.parameters.map { it.type.canonicalText }
            
            for (candidateMethod in targetClass.methods) {
                if (candidateMethod.name == methodName) {
                    val candidateParameterTypes = candidateMethod.parameterList.parameters.map { it.type.canonicalText }
                    if (parameterTypes == candidateParameterTypes) {
                        return candidateMethod
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        return null
    }

    /**
     * 获取方法签名（包含参数类型，用于区分重载方法）
     */
    private fun getMethodSignature(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: "Unknown"
        val methodName = method.name
        
        // 获取参数类型列表，用于区分重载方法
        val paramTypes = method.parameterList.parameters.joinToString(", ") { param ->
            param.type.canonicalText
        }
        
        // 返回格式：ClassName.methodName(paramType1, paramType2, ...)
        return "$className.$methodName($paramTypes)"
    }
    
    /**
     * 从表达式中提取类型信息
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
            is PsiMethodCallExpression -> {
                // 处理方法调用的返回类型
                val method = expression.resolveMethod()
                var returnType = method?.returnType?.canonicalText
                if (returnType?.contains('<') == true) {
                    returnType = returnType.substringBefore('<')
                }
                returnType
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
}
