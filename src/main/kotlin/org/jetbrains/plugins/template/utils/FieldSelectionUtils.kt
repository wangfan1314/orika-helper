package org.jetbrains.plugins.template.utils

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilBase

/**
 * 字段选择工具类
 * 用于检测用户选中的字段并提供相关分析功能
 */
object FieldSelectionUtils {

    /**
     * 从编辑器中获取当前选中或光标所在的字段
     */
    fun getSelectedField(editor: Editor, project: Project): PsiField? {
        return try {
            val psiFile = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return null
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset) ?: return null
            
            // 1. 检查是否直接选中字段声明
            val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
            if (field != null) {
                return field
            }
            
            // 2. 检查是否在字段引用处
            val reference = element.reference
            if (reference != null) {
                val resolved = reference.resolve()
                if (resolved is PsiField) {
                    return resolved
                }
            }
            
            // 3. 检查是否在方法调用中（简化版本）
            val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
            if (methodCall != null) {
                // 尝试从方法名推断字段
                return findFieldFromMethodName(methodCall, project)
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从方法名推断字段（简化实现）
     */
    private fun findFieldFromMethodName(methodCall: PsiMethodCallExpression, project: Project): PsiField? {
        return try {
            val methodExpression = methodCall.methodExpression
            val methodName = methodExpression.referenceName ?: return null
            
            // 检查是否是getter/setter方法
            if (methodName.startsWith("get") || methodName.startsWith("set")) {
                val fieldName = methodName.substring(3).replaceFirstChar { it.lowercase() }
                
                // 尝试找到对应的字段
                val containingClass = PsiTreeUtil.getParentOfType(methodCall, PsiClass::class.java)
                return containingClass?.findFieldByName(fieldName, true)
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查字段是否可能参与Orika映射
     */
    fun isFieldMappingCandidate(field: PsiField): Boolean {
        return try {
            // 检查字段修饰符
            if (field.hasModifierProperty(PsiModifier.STATIC) || 
                field.hasModifierProperty(PsiModifier.FINAL)) {
                return false
            }
            
            // 基本类型和常见类型都认为是候选
            true
        } catch (e: Exception) {
            true // 默认认为是候选字段
        }
    }

    /**
     * 获取字段的详细信息
     */
    fun getFieldInfo(field: PsiField): FieldInfo {
        return try {
            FieldInfo(
                name = field.name,
                type = field.type.canonicalText,
                className = field.containingClass?.qualifiedName ?: "",
                isPrivate = field.hasModifierProperty(PsiModifier.PRIVATE),
                isPublic = field.hasModifierProperty(PsiModifier.PUBLIC),
                isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                isFinal = field.hasModifierProperty(PsiModifier.FINAL),
                annotations = field.annotations.mapNotNull { it.qualifiedName }
            )
        } catch (e: Exception) {
            FieldInfo(
                name = field.name,
                type = "Unknown",
                className = "Unknown",
                isPrivate = false,
                isPublic = true,
                isStatic = false,
                isFinal = false,
                annotations = emptyList()
            )
        }
    }

    /**
     * 字段信息数据类
     */
    data class FieldInfo(
        val name: String,
        val type: String,
        val className: String,
        val isPrivate: Boolean,
        val isPublic: Boolean,
        val isStatic: Boolean,
        val isFinal: Boolean,
        val annotations: List<String>
    )
}