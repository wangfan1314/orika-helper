package com.github.wangfan1314.orikahelper.model

/**
 * 映射关系数据类
 * 表示两个字段之间的映射关系
 */
data class MappingRelation(
    val sourceClass: String,
    val sourceField: String,
    val targetClass: String,
    val targetField: String,
    val mappingType: String // "API", "ANNOTATION", "AUTO"
)
