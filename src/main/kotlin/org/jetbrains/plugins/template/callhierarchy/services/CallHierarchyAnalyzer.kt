package org.jetbrains.plugins.template.callhierarchy.services

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
import org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNode
import org.jetbrains.plugins.template.callhierarchy.model.CallHierarchyNodeType
import org.jetbrains.plugins.template.services.OrikaMappingAnalyzer
import org.jetbrains.plugins.template.model.MappingCall

/**
 * 调用层级分析器
 * 负责分析方法的调用链路，集成IDEA原生调用层级和Orika映射分析
 */
@Service(Service.Level.PROJECT)
class CallHierarchyAnalyzer(private val project: Project) {

    private val orikaMappingAnalyzer = project.getService(OrikaMappingAnalyzer::class.java)
    
    /**
     * 分析指定字段的完整调用链路
     * 利用IDEA原生Call Hierarchy API，并增强Orika映射处理
     */
    fun analyzeCallHierarchy(field: PsiField): CallHierarchyNode? {
        try {
            // 创建根节点
            val rootNode = CallHierarchyNode(
                className = field.containingClass?.qualifiedName ?: "Unknown",
                methodName = field.name,
                displayName = "字段: ${field.containingClass?.name}.${field.name}",
                location = getElementLocation(field),
                nodeType = CallHierarchyNodeType.ROOT,
                psiElement = field
            )
            
            // 查找包含该字段的方法
            val containingMethods = findMethodsUsingField(field)
            
            for (method in containingMethods) {
                val methodNode = createMethodNode(method)
                if (methodNode != null) {
                    rootNode.addChild(methodNode)
                    // 使用增强的调用链路分析
                    analyzeMethodCallHierarchyWithOrikaSupport(field, method, methodNode, mutableSetOf(), 0, 10)
                }
            }
            
            // 额外：直接查找涉及该字段的Orika映射
            analyzeDirectOrikaRelationsForField(field, rootNode)
            
            return rootNode
            
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 带Orika支持的方法调用链路分析
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
            // 首先检查当前方法是否包含Orika映射
            if (containsOrikaMapping(method)) {
                analyzeOrikaRelatedCallsForField(originalField, method, currentNode)
            }
            
            // 然后继续原生的调用链路分析
            fallbackToSimpleReferenceSearchWithOrikaSupport(originalField, method, currentNode, visitedMethods, depth, maxDepth)
            
        } catch (e: Exception) {
            // 静默处理异常
        }
        
        visitedMethods.remove(methodSignature)
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
            
            for (mappingCall in mappingCalls.take(5)) { // 限制数量
                // 为每个映射调用创建一个节点，并设置正确的PSI元素用于跳转
                val mappingNode = CallHierarchyNode(
                    className = mappingCall.className,
                    methodName = "orika.map",
                    displayName = "Orika映射: ${extractOrikaCallInfo(mappingCall.psiElement ?: continue)}",
                    location = mappingCall.location,
                    nodeType = CallHierarchyNodeType.ORIKA_MAPPING,
                    psiElement = mappingCall.psiElement
                )
                rootNode.addChild(mappingNode)
                
                // 从映射的目标类继续追踪
                if (mappingCall.psiElement is PsiMethodCallExpression) {
                    val args = mappingCall.psiElement.argumentList.expressions
                    if (args.size >= 2) {
                        val sourceType = extractTypeFromExpression(args[0])
                        val targetType = extractTypeFromExpression(args[1])
                        
                        // 确定目标类型（与原字段所在类不同的那个）
                        val relevantTargetType = if (sourceType == fieldClass) targetType else sourceType
                        if (relevantTargetType != null) {
                            val targetField = findFieldInClass(relevantTargetType, fieldName)
                            if (targetField != null) {
                                // 查找使用目标字段的方法，继续追踪
                                val targetMethods = findMethodsUsingField(targetField)
                                for (targetMethod in targetMethods.take(3)) {
                                    val targetMethodNode = createMethodNode(targetMethod)
                                    if (targetMethodNode != null) {
                                        mappingNode.addChild(targetMethodNode)
                                        // 继续向上追踪调用链路
                                        analyzeMethodCallHierarchyWithOrikaSupport(targetField, targetMethod, targetMethodNode, mutableSetOf(), 1, 8)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 额外：从包含映射调用的方法向上追踪
                val mappingMethod = PsiTreeUtil.getParentOfType(mappingCall.psiElement, PsiMethod::class.java)
                if (mappingMethod != null) {
                    val callers = findMethodCallers(mappingMethod)
                    for (caller in callers.take(3)) {
                        val callerNode = createMethodNode(caller)
                        if (callerNode != null) {
                            mappingNode.addChild(callerNode)
                            // 继续向上追踪
                            if (!isControllerMethod(caller)) {
                                analyzeMethodCallHierarchyWithOrikaSupport(field, caller, callerNode, mutableSetOf(), 1, 6)
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
     * 查找涉及指定字段的Orika映射调用（包含PSI元素）
     */
    private fun findOrikaMappingCallsWithPsi(field: PsiField): List<MappingCall> {
        val calls = mutableListOf<MappingCall>()
        val fieldClass = field.containingClass?.qualifiedName ?: return calls
        val fieldName = field.name
        
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
                GlobalSearchScope.projectScope(project)
            )
            
            for (javaFile in javaFiles) {
                javaFile.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
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
            
            for (reference in orikaReferences.take(3)) {
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
                                val targetClass = JavaPsiFacade.getInstance(project).findClass(relevantTargetType, GlobalSearchScope.projectScope(project))
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
            
            for (targetMethod in targetMethods.take(3)) {
                if (targetMethod != orikaContainingMethod) {
                    val targetMethodNode = createMethodNode(targetMethod)
                    if (targetMethodNode != null) {
                        orikaNode.addChild(targetMethodNode)
                        
                        // 继续向上追踪
                        if (!isControllerMethod(targetMethod)) {
                            analyzeMethodCallHierarchyWithOrikaSupport(targetField, targetMethod, targetMethodNode, mutableSetOf(), 1, 6)
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
            // 直接使用优化的引用搜索，避免依赖可能不稳定的API
            fallbackToSimpleReferenceSearch(method, currentNode, visitedMethods, depth, maxDepth)
        } catch (e: Exception) {
            // 静默处理异常
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
            
            // 1. 查找直接的方法引用，并记录调用位置
            val directReferences = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
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
                val interfaceReferences = ReferencesSearch.search(interfaceMethod, GlobalSearchScope.projectScope(project))
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
            
            for (callerMethod in callerMethods.take(5)) { // 限制数量避免性能问题
                val callSites = methodToCallSites[callerMethod] ?: emptyList()
                
                if (callSites.isNotEmpty()) {
                    // 如果一个方法有多个调用点，为每个调用点创建一个节点
                    for (callSite in callSites.take(3)) { // 限制每个方法的调用点数量
                        val callerNode = createMethodNodeWithCallSite(callerMethod, callSite)
                        if (callerNode != null) {
                            currentNode.addChild(callerNode)
                            
                            // 检查是否包含Orika映射
                            if (containsOrikaMapping(callerMethod)) {
                                analyzeOrikaRelatedCallsForField(originalField, callerMethod, callerNode)
                            }
                            
                            if (!isControllerMethod(callerMethod)) {
                                analyzeMethodCallHierarchyWithOrikaSupport(originalField, callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                            }
                        }
                    }
                } else {
                    // 如果没有调用点信息，使用原来的方法
                    val callerNode = createMethodNode(callerMethod)
                    if (callerNode != null) {
                        currentNode.addChild(callerNode)
                        
                        // 检查是否包含Orika映射
                        if (containsOrikaMapping(callerMethod)) {
                            analyzeOrikaRelatedCallsForField(originalField, callerMethod, callerNode)
                        }
                    
                        if (!isControllerMethod(callerMethod)) {
                            analyzeMethodCallHierarchyWithOrikaSupport(originalField, callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
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
            val directReferences = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
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
                val interfaceReferences = ReferencesSearch.search(interfaceMethod, GlobalSearchScope.projectScope(project))
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
            
            for (callerMethod in callerMethods.take(5)) { // 限制数量避免性能问题
                val callSites = methodToCallSites[callerMethod] ?: emptyList()
                
                if (callSites.isNotEmpty()) {
                    // 如果一个方法有多个调用点，为每个调用点创建一个节点
                    for (callSite in callSites.take(3)) { // 限制每个方法的调用点数量
                        val callerNode = createMethodNodeWithCallSite(callerMethod, callSite)
                        if (callerNode != null) {
                            currentNode.addChild(callerNode)
                            
                            // 检查是否包含Orika映射
                            if (containsOrikaMapping(callerMethod)) {
                                analyzeOrikaRelatedCalls(callerMethod, callerNode)
                            }
                            
                            if (!isControllerMethod(callerMethod)) {
                                analyzeMethodCallHierarchyNative(callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                            }
                        }
                    }
                } else {
                    // 如果没有调用点信息，使用原来的方法
                    val callerNode = createMethodNode(callerMethod)
                    if (callerNode != null) {
                        currentNode.addChild(callerNode)
                        
                        // 检查是否包含Orika映射
                        if (containsOrikaMapping(callerMethod)) {
                            analyzeOrikaRelatedCalls(callerMethod, callerNode)
                        }
                        
                        if (!isControllerMethod(callerMethod)) {
                            analyzeMethodCallHierarchyNative(callerMethod, callerNode, visitedMethods, depth + 1, maxDepth)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理异常
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
            
            for (reference in orikaReferences.take(3)) { // 限制处理数量，避免性能问题
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
            for (targetMethod in targetMethods.take(5)) { // 限制方法数量避免性能问题
                val callers = findMethodCallers(targetMethod)
                
                for (caller in callers.take(3)) { // 限制调用者数量
                    // 确保不会回到原始的Orika映射方法
                    val orikaContainingMethod = PsiTreeUtil.getParentOfType(orikaReference, PsiMethod::class.java)
                    if (caller != orikaContainingMethod) {
                        val callerNode = createMethodNode(caller)
                        if (callerNode != null) {
                            orikaNode.addChild(callerNode)
                            
                            // 继续向上追踪调用链路
                            if (!isControllerMethod(caller)) {
                                analyzeMethodCallHierarchyNative(caller, callerNode, mutableSetOf(), 1, 8)
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
            val references = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
            
            for (reference in references.take(10)) { // 限制引用数量
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
            val references = ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
            
            val orikaContainingMethod = PsiTreeUtil.getParentOfType(orikaReference, PsiMethod::class.java)
            
            for (reference in references.take(8)) { // 限制引用数量
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
                            
                            // 继续向上追踪
                            if (!isControllerMethod(containingMethod)) {
                                analyzeMethodCallHierarchyNative(containingMethod, usageNode, mutableSetOf(), 1, 6)
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
            // 限制搜索范围到相关文件，提高性能
            val containingClass = field.containingClass
            val searchScope = if (containingClass != null) {
                GlobalSearchScope.filesScope(project, listOfNotNull(
                    containingClass.containingFile?.virtualFile
                ))
            } else {
                GlobalSearchScope.projectScope(project)
            }
            
            // 查找字段的引用，限制结果数量
            val references = ReferencesSearch.search(field, searchScope)
            
            var count = 0
            for (reference in references) {
                if (count >= 10) break // 限制处理数量
                
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
            val implementations_search = ClassInheritorsSearch.search(interfaceClass, GlobalSearchScope.projectScope(project), false)
            
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
        
        return implementations.take(3) // 限制数量避免性能问题
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
     * 获取方法签名
     */
    private fun getMethodSignature(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: "Unknown"
        return "$className.${method.name}"
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
