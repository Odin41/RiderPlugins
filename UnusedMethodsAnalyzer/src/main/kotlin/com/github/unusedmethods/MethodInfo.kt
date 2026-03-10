package com.github.unusedmethods

data class MethodInfo(
    val name: String,
    val signature: String,
    val className: String,
    val filePath: String,
    val lineNumber: Int,        // 1-based
    val isPrivate: Boolean,
    val isOverride: Boolean,
    val isStatic: Boolean
) {
    val displayName: String get() = "$className.$name"
    val fileName: String get() = filePath.substringAfterLast('/').substringAfterLast('\\')
}
