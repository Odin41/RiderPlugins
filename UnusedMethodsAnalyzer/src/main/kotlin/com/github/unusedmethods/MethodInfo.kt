package com.github.unusedmethods

enum class SymbolKind(val label: String) {
    METHOD("Method"),
    PROPERTY("Property"),
    CLASS("Class"),
    INTERFACE("Interface"),
    ENUM("Enum")
}

data class MethodInfo(
    val name: String,
    val signature: String,
    val className: String,
    val filePath: String,
    val lineNumber: Int,        // 1-based
    val isPrivate: Boolean,
    val isOverride: Boolean,
    val isStatic: Boolean,
    val kind: SymbolKind = SymbolKind.METHOD
) {
    val displayName: String get() = if (kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE || kind == SymbolKind.ENUM) name else "$className.$name"
    val fileName: String get() = filePath.substringAfterLast('/').substringAfterLast('\\')
}
