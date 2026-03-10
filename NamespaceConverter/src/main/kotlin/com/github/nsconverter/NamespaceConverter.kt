package com.github.nsconverter

/**
 * Pure conversion logic – no IntelliJ dependencies so it's easily unit-testable.
 *
 * Handles a single block-scoped namespace per file (the standard C# case).
 * The algorithm:
 *  1. Find the `namespace <name>` line (no semicolon at the end).
 *  2. Find the matching opening `{` on the same or next non-blank line.
 *  3. Walk the file to find the matching closing `}` (respecting nested braces
 *     inside classes, methods, etc.).
 *  4. Remove the outer braces, dedent the body by one level, and append `;`
 *     to the namespace declaration line.
 */
object NamespaceConverter {

    class ConversionException(message: String) : Exception(message)

    // Matches:  namespace Foo.Bar.Baz   (no semicolon → block-scoped)
    private val NAMESPACE_DECL_REGEX = Regex(
        """^(\s*)namespace\s+([\w.]+)\s*$""",
        RegexOption.MULTILINE
    )

    /** Returns true if the file text contains at least one block-scoped namespace. */
    fun hasBlockScopedNamespace(text: String): Boolean =
        NAMESPACE_DECL_REGEX.containsMatchIn(text)

    /**
     * Converts the first block-scoped namespace found in [text] to file-scoped style.
     * Throws [ConversionException] if the structure is unexpected.
     */
    fun convert(text: String): String {
        val lines = text.lines()

        // 1. Find the namespace declaration line
        val nsDeclIndex = lines.indexOfFirst { NAMESPACE_DECL_REGEX.matches(it.trim().let { t ->
            // rebuild with leading spaces stripped for regex match
            it
        }) && !it.trimEnd().endsWith(";") && NAMESPACE_DECL_REGEX.containsMatchIn(it) }

        if (nsDeclIndex == -1)
            throw ConversionException("No block-scoped namespace declaration found.")

        val nsDeclLine = lines[nsDeclIndex]
        val nsName = NAMESPACE_DECL_REGEX.find(nsDeclLine)?.groupValues?.get(2)
            ?: throw ConversionException("Cannot parse namespace name.")

        // 2. Find the opening brace – on the same line or the next non-blank line
        var openBraceLineIndex = -1
        var openBraceCol = -1

        // Check same line (e.g. "namespace Foo {")
        val sameLineBrace = nsDeclLine.indexOf('{')
        if (sameLineBrace != -1) {
            openBraceLineIndex = nsDeclIndex
            openBraceCol = sameLineBrace
        } else {
            // Look ahead
            for (i in (nsDeclIndex + 1)..minOf(nsDeclIndex + 5, lines.lastIndex)) {
                val trimmed = lines[i].trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("{")) {
                    openBraceLineIndex = i
                    openBraceCol = lines[i].indexOf('{')
                    break
                }
                break // something else before `{` – unexpected
            }
        }

        if (openBraceLineIndex == -1)
            throw ConversionException("Cannot find opening '{' for namespace '$nsName'.")

        // 3. Find matching closing brace by counting brace depth
        var depth = 0
        var closeBraceLineIndex = -1
        var closeBraceCol = -1
        var insideString = false
        var insideChar = false
        var insideLineComment = false
        var insideBlockComment = false
        var prevChar = ' '

        outer@ for (lineIdx in openBraceLineIndex..lines.lastIndex) {
            val line = lines[lineIdx]
            insideLineComment = false

            val startCol = if (lineIdx == openBraceLineIndex) openBraceCol else 0

            var col = startCol
            while (col < line.length) {
                val ch = line[col]
                val next = if (col + 1 < line.length) line[col + 1] else ' '

                when {
                    insideBlockComment -> {
                        if (ch == '*' && next == '/') {
                            insideBlockComment = false
                            col++ // skip '/'
                        }
                    }
                    insideLineComment -> { /* skip rest of line */ col = line.length; continue }
                    insideString -> {
                        when {
                            ch == '\\' -> col++ // skip escaped char
                            ch == '"' -> insideString = false
                        }
                    }
                    insideChar -> {
                        when {
                            ch == '\\' -> col++
                            ch == '\'' -> insideChar = false
                        }
                    }
                    ch == '/' && next == '/' -> insideLineComment = true
                    ch == '/' && next == '*' -> { insideBlockComment = true; col++ }
                    ch == '"' -> insideString = true
                    ch == '\'' -> insideChar = true
                    ch == '{' -> depth++
                    ch == '}' -> {
                        depth--
                        if (depth == 0) {
                            closeBraceLineIndex = lineIdx
                            closeBraceCol = col
                            break@outer
                        }
                    }
                }
                prevChar = ch
                col++
            }
        }

        if (closeBraceLineIndex == -1)
            throw ConversionException("Cannot find closing '}' for namespace '$nsName'.")

        // 4. Reconstruct the file
        val result = StringBuilder()

        // Lines before namespace declaration (using declarations, etc.)
        for (i in 0 until nsDeclIndex) {
            result.appendLine(lines[i])
        }

        // New file-scoped namespace declaration + blank separator line
        result.appendLine("namespace $nsName;")
        result.appendLine()

        // Determine indentation to strip from body lines (one level = 4 spaces or 1 tab)
        val bodyIndentToStrip = detectIndentUnit(lines, openBraceLineIndex + 1, closeBraceLineIndex)

        // Lines between opening and closing brace (the body)
        //  – if opening brace and namespace decl are on same line, body starts next line
        //  – otherwise body starts after the brace line
        val bodyEnd = closeBraceLineIndex - 1

        // Handle possible code on the same line as the opening brace (after '{')
        if (openBraceLineIndex == nsDeclIndex) {
            val afterBrace = nsDeclLine.substring(openBraceCol + 1).trim()
            if (afterBrace.isNotEmpty() && afterBrace != "{") {
                result.appendLine(afterBrace)
            }
        }

        // Skip leading blank lines in body (blank separator already added above)
        var bodyStart = openBraceLineIndex + 1
        while (bodyStart <= bodyEnd && lines[bodyStart].isBlank()) bodyStart++

        for (i in bodyStart..bodyEnd) {
            result.appendLine(stripOneIndentLevel(lines[i], bodyIndentToStrip))
        }

        // Handle possible code on the same line as the closing brace (before '}')
        if (closeBraceCol > 0) {
            val beforeBrace = lines[closeBraceLineIndex].substring(0, closeBraceCol).trim()
            if (beforeBrace.isNotEmpty()) {
                result.appendLine(beforeBrace)
            }
        }

        // Lines after the closing brace
        for (i in (closeBraceLineIndex + 1)..lines.lastIndex) {
            if (i == lines.lastIndex && lines[i].isEmpty()) break // preserve single trailing newline
            result.appendLine(lines[i])
        }

        return result.toString().trimEnd('\n') + "\n"
    }

    // ---------------------------------------------------------------------------
    // Indentation helpers
    // ---------------------------------------------------------------------------

    /**
     * Detects the indentation unit used inside the namespace block.
     * Returns the string to strip from every body line (e.g. "    " or "\t").
     */
    private fun detectIndentUnit(lines: List<String>, from: Int, to: Int): String {
        for (i in from..minOf(to, lines.lastIndex)) {
            val line = lines[i]
            if (line.isBlank()) continue
            val leading = line.takeWhile { it == ' ' || it == '\t' }
            if (leading.isEmpty()) continue
            // Return the detected indentation (one level assumed for namespace body)
            return if (leading.startsWith('\t')) "\t" else "    "
        }
        return "    " // default
    }

    private fun stripOneIndentLevel(line: String, unit: String): String {
        return if (line.startsWith(unit)) line.substring(unit.length) else line.trimStart(' ', '\t').let { line }
            .let { if (line.startsWith(unit)) line.substring(unit.length) else line }
    }
}
