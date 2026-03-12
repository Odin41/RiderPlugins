package com.github.unusedmethods

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object CSharpAnalyzer {

    private val LOG = Logger.getInstance(CSharpAnalyzer::class.java)

    private val SKIP_DIRS = setOf(
        "bin", "obj", ".git", ".vs", "node_modules", ".idea", "packages", "Migrations"
    )

    private val METHOD_NAME_REGEX = Regex(
        """^[\s]*(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|extern|unsafe|partial|new)\s+)+""" +
        """(?:[\w<>\[\],.\s?]+?\s+)""" +
        """(\w+)\s*[(<]"""
    )

    private val METHOD_NAME_FALLBACK = Regex("""(\w+)\s*[(<]""")

    private val IMPLICIT_USE_ATTRIBUTES = setOf(
        "UsedImplicitly", "ApiExplorerSettings", "HttpGet", "HttpPost", "HttpPut",
        "HttpDelete", "HttpPatch", "Route", "Authorize", "AllowAnonymous",
        "JSInvokable", "Parameter", "Inject", "OneWay", "TwoWay", "CascadingParameter"
    )

    // ── PSI type sets ─────────────────────────────────────────────────────────

    private val METHOD_PSI_TYPES = setOf(
        "CSharpMethodDeclaration",
        "CSharpMethodDeclarationImpl",
        "CSharpConstructorDeclaration",
        "CSharpConstructorDeclarationImpl",
        "CSharpDestructorDeclaration",
        "CSharpDestructorDeclarationImpl"
    )

    private val PROPERTY_PSI_TYPES = setOf(
        "CSharpPropertyDeclaration",
        "CSharpPropertyDeclarationImpl",
        "CSharpFieldDeclaration",
        "CSharpFieldDeclarationImpl"
    )

    private val CLASS_PSI_TYPES = setOf(
        "CSharpClassDeclaration",
        "CSharpClassDeclarationImpl"
    )

    private val INTERFACE_PSI_TYPES = setOf(
        "CSharpInterfaceDeclaration",
        "CSharpInterfaceDeclarationImpl"
    )

    private val ENUM_PSI_TYPES = setOf(
        "CSharpEnumDeclaration",
        "CSharpEnumDeclarationImpl"
    )

    private fun symbolKindFor(typeName: String): SymbolKind? = when {
        typeName in METHOD_PSI_TYPES    -> SymbolKind.METHOD
        typeName in PROPERTY_PSI_TYPES  -> SymbolKind.PROPERTY
        typeName in CLASS_PSI_TYPES     -> SymbolKind.CLASS
        typeName in INTERFACE_PSI_TYPES -> SymbolKind.INTERFACE
        typeName in ENUM_PSI_TYPES      -> SymbolKind.ENUM
        else                            -> null
    }

    private fun isSymbolLike(typeName: String) = symbolKindFor(typeName) != null

    // ── Public entry points ───────────────────────────────────────────────────

    fun analyze(project: Project, indicator: ProgressIndicator): AnalysisResult {
        val scope = GlobalSearchScope.projectScope(project)
        val files = collectCsFiles(project, null)
        val markupFiles = collectMarkupFiles(project, null)
        return analyzeFiles(project, files, markupFiles, files, scope, indicator)
    }

    fun analyzeDirectory(
        project: Project,
        directory: VirtualFile,
        indicator: ProgressIndicator
    ): AnalysisResult {
        val files = collectCsFiles(project, directory)
        val markupFiles = collectMarkupFiles(project, directory)
        val allCsFiles = collectCsFiles(project, null)
        return analyzeFiles(project, files, markupFiles, allCsFiles, GlobalSearchScope.projectScope(project), indicator, directory)
    }

    data class AnalysisResult(
        val methods: List<MethodInfo>,
        val message: String,
        val scannedFiles: Int = 0,
        val scannedMethods: Int = 0,
        val debugInfo: String = ""
    )

    // ── Core pipeline ─────────────────────────────────────────────────────────

    private fun analyzeFiles(
        project: Project,
        files: List<VirtualFile>,
        markupFiles: List<VirtualFile>,
        allCsFiles: List<VirtualFile>,
        searchScope: SearchScope,
        indicator: ProgressIndicator,
        limitToDir: VirtualFile? = null
    ): AnalysisResult {

        val where = limitToDir?.name ?: "project"
        val debug = StringBuilder()

        if (files.isEmpty()) {
            val msg = "No .cs files found in $where"
            LOG.warn("UnusedMethods: $msg")
            return AnalysisResult(emptyList(), msg, debugInfo = msg)
        }

        debug.appendLine("Files found: ${files.size}, markup files: ${markupFiles.size}")
        LOG.info("UnusedMethods: ${files.size} .cs files, ${markupFiles.size} markup files")

        indicator.text = "Loading markup files..."
        val markupContent = buildMarkupIndex(markupFiles)

        indicator.text = "Building C# text index..."
        val csTextIndex = buildCsTextIndex(allCsFiles, files)

        val settings = UnusedMethodsSettings.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val candidates = CopyOnWriteArrayList<CsSymbolElement>()

        // ── Pass 1: collect PSI declarations ─────────────────────────────────
        indicator.text = "Reading PSI declarations..."
        indicator.isIndeterminate = false
        var diagnosticDone = false

        files.forEachIndexed { i, vf ->
            indicator.checkCanceled()
            indicator.fraction = 0.05 + 0.35 * i / files.size
            indicator.text2 = vf.name

            ReadAction.run<Exception> {
                val psiFile = psiManager.findFile(vf) ?: return@run
                if (!diagnosticDone && looksLikeMethodFile(psiFile)) {
                    diagnosticDone = true
                    dumpPsiTree(psiFile, debug)
                }
                collectSymbolElements(psiFile, settings, candidates)
            }
        }

        val psiTypesFound = candidates.map { it.psiTypeName }.toSet()
        debug.appendLine("PSI types matched: $psiTypesFound")
        debug.appendLine("Candidates after Pass 1: ${candidates.size}")
        LOG.info("UnusedMethods: Pass1 done — ${candidates.size} candidates")

        if (candidates.isEmpty()) {
            val msg = "0 declarations found. See Help > Show Log for PSI dump."
            return AnalysisResult(emptyList(), msg, scannedFiles = files.size, debugInfo = debug.toString())
        }

        // ── Pass 2: check references ──────────────────────────────────────────
        indicator.text = "Waiting for indices..."
        DumbService.getInstance(project).waitForSmartMode()

        indicator.text = "Checking references (${candidates.size} symbols)..."
        indicator.fraction = 0.40

        val unusedSymbols = CopyOnWriteArrayList<MethodInfo>()
        val totalSymbols  = candidates.size
        val doneSymbols   = AtomicInteger(0)

        val executor = Executors.newSingleThreadExecutor()

        candidates.forEach { candidate ->
            executor.submit {
                if (indicator.isCanceled) return@submit
                val done = doneSymbols.incrementAndGet()
                if (done % 20 == 0) {
                    indicator.fraction = 0.40 + 0.55 * done / totalSymbols
                    indicator.text2 = "${candidate.className}.${candidate.name} ($done/$totalSymbols)"
                }

                // ── Step A: PSI ReferencesSearch ──────────────────────────────
                val psiUsageCount = try {
                    ReadAction.compute<Int, Exception> {
                        if (!candidate.element.isValid) return@compute -1
                        ReferencesSearch.search(
                            ReferencesSearch.SearchParameters(
                                candidate.element,
                                searchScope,
                                /* ignoreAccessScope = */ true
                            )
                        ).findAll().size
                    }
                } catch (e: Exception) {
                    LOG.warn("UnusedMethods: ReferencesSearch failed for ${candidate.name}: ${e.message}")
                    -1
                }
                if (psiUsageCount > 0) return@submit

                // ── Step B: markup text search ────────────────────────────────
                if (isUsedInMarkup(candidate.name, markupContent)) return@submit

                // ── Step C: C# text search ────────────────────────────────────
                val textUsed = isUsedInCsText(candidate.name, candidate.filePath, csTextIndex)
                if (textUsed) {
                    LOG.info("UnusedMethods: rescued by text search: ${candidate.className}.${candidate.name}")
                    return@submit
                }

                // ── Confirmed unused ──────────────────────────────────────────
                LOG.info("UnusedMethods: UNUSED [${candidate.kind}] ${candidate.className}.${candidate.name} " +
                    "[file=${candidate.filePath.substringAfterLast('/')} line=${candidate.lineNumber}]")
                unusedSymbols.add(candidate.toMethodInfo())
            }
        }

        executor.shutdown()
        var waited = 0
        while (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            indicator.checkCanceled()
            if (++waited * 2 > 600) { executor.shutdownNow(); break }
        }

        indicator.fraction = 0.98
        indicator.text2 = ""

        val result = unusedSymbols.sortedWith(compareBy({ it.kind.name }, { it.filePath }, { it.lineNumber }))
        val methodCount = result.count { it.kind == SymbolKind.METHOD }
        val propCount   = result.count { it.kind == SymbolKind.PROPERTY }
        val classCount  = result.count { it.kind == SymbolKind.CLASS }
        val ifaceCount  = result.count { it.kind == SymbolKind.INTERFACE }
        val enumCount   = result.count { it.kind == SymbolKind.ENUM }

        debug.appendLine("Unused found: ${result.size} of $totalSymbols")
        LOG.info("UnusedMethods: done — ${result.size} unused / $totalSymbols in $where")

        val summary = buildString {
            append("Files: ${files.size}  |  Scanned: $totalSymbols  |  Unused: ${result.size}")
            if (methodCount > 0) append("  (Methods: $methodCount")
            if (propCount   > 0) append("  Props: $propCount")
            if (classCount  > 0) append("  Classes: $classCount")
            if (ifaceCount  > 0) append("  Ifaces: $ifaceCount")
            if (enumCount   > 0) append("  Enums: $enumCount")
            if (methodCount > 0 || propCount > 0 || classCount > 0 || ifaceCount > 0 || enumCount > 0) append(")")
        }

        return AnalysisResult(
            methods        = result,
            message        = summary,
            scannedFiles   = files.size,
            scannedMethods = totalSymbols,
            debugInfo      = debug.toString()
        )
    }

    // ── Markup index ──────────────────────────────────────────────────────────

    private fun collectMarkupFiles(project: Project, root: VirtualFile?): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val seen   = mutableSetOf<String>()
        val exts   = setOf("razor", "cshtml", "xaml")

        val baseDir: VirtualFile = if (root != null) root else {
            val bp = project.basePath ?: return result
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(bp) ?: return result
        }

        ReadAction.run<Exception> { collectMarkupRecursive(baseDir, exts, result, seen) }
        return result
    }

    private fun collectMarkupRecursive(
        dir: VirtualFile,
        extensions: Set<String>,
        out: MutableList<VirtualFile>,
        seen: MutableSet<String>
    ) {
        if (!dir.isValid) return
        for (child in dir.children ?: return) {
            when {
                child.isDirectory && child.name !in SKIP_DIRS ->
                    collectMarkupRecursive(child, extensions, out, seen)
                !child.isDirectory && child.extension?.lowercase() in extensions && seen.add(child.path) ->
                    out.add(child)
            }
        }
    }

    private fun buildMarkupIndex(markupFiles: List<VirtualFile>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ReadAction.run<Exception> {
            for (vf in markupFiles) {
                try { result[vf.path] = String(vf.contentsToByteArray(), Charsets.UTF_8) }
                catch (_: Exception) { }
            }
        }
        return result
    }

    private fun isUsedInMarkup(name: String, markupContent: Map<String, String>): Boolean {
        val pattern = Regex("""${Regex.escape(name)}(?!\w)""")
        for (content in markupContent.values) {
            if (!content.contains(name)) continue
            if (pattern.containsMatchIn(content)) return true
        }
        return false
    }

    private fun buildCsTextIndex(
        allCsFiles: List<VirtualFile>,
        @Suppress("UNUSED_PARAMETER") declarationFiles: List<VirtualFile>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ReadAction.run<Exception> {
            for (vf in allCsFiles) {
                try { result[vf.path] = String(vf.contentsToByteArray(), Charsets.UTF_8) }
                catch (_: Exception) { }
            }
        }
        return result
    }

    private fun isUsedInCsText(
        name: String,
        declaringFilePath: String,
        csIndex: Map<String, String>
    ): Boolean {
        val broadPattern  = Regex("""(?<!\w)${Regex.escape(name)}(?!\w)""")
        val strictPattern = Regex("""(?<!\w)${Regex.escape(name)}\s*[(<]""")
        val pattern = if (name.length <= 5) strictPattern else broadPattern

        for ((path, content) in csIndex) {
            if (!content.contains(name)) continue
            val matches = pattern.findAll(content).count()
            if (path == declaringFilePath) {
                if (matches >= 2) return true
            } else {
                if (matches >= 1) return true
            }
        }
        return false
    }

    // ── PSI dump ──────────────────────────────────────────────────────────────

    private fun dumpPsiTree(psiFile: PsiFile, out: StringBuilder) {
        out.appendLine("=== PSI dump: ${psiFile.name} ===")
        val seen = mutableSetOf<String>()
        walkPsi(psiFile) { el ->
            val simple = el.javaClass.simpleName
            if (seen.add(simple)) {
                val nameIF   = (el as? PsiNamedElement)?.name ?: ""
                val nameRefl = try { el.javaClass.getMethod("getName").invoke(el) as? String ?: "" } catch (_: Exception) { "" }
                val nameRx   = symbolKindFor(simple)?.let { extractSymbolName(el, it) } ?: ""
                val text     = el.text.take(60).replace('\n', ' ')
                LOG.info("UnusedMethods PSI TYPE: $simple | nameIF='$nameIF' | nameREFL='$nameRefl' | nameREGEX='$nameRx' | text='$text'")
                out.appendLine("  $simple | nameIF='$nameIF' | nameREFL='$nameRefl' | nameREGEX='$nameRx'")
            }
        }
        out.appendLine("=== end PSI dump (${seen.size} unique types) ===")
    }

    private fun looksLikeMethodFile(psiFile: PsiFile): Boolean {
        val text = try { psiFile.text } catch (_: Exception) { return false }
        return (text.contains("void ") || text.contains("public ") || text.contains("private "))
            && text.contains("(") && text.contains("{")
    }

    // ── Name extraction ───────────────────────────────────────────────────────

    // Matches property/field declarations: "public int Age { get; set; }" → "Age"
    // Also matches: "public string Name => ...;" → "Name"
    // Pattern: modifiers + type + identifier, terminated by { or => or ;
    private val PROPERTY_NAME_REGEX = Regex(
        """^[\s]*(?:(?:public|private|protected|internal|static|virtual|override|abstract|sealed|new|readonly)\s+)+""" +
        """(?:[\w<>\[\],.\s?]+?\s+)""" +
        """(\w+)\s*(?:\{|=>|;)"""
    )

    // Matches class/interface/enum/struct declarations: "public class Foo" → "Foo"
    private val TYPE_DECL_NAME_REGEX = Regex(
        """(?:class|interface|enum|struct|record)\s+(\w+)"""
    )

    private fun extractSymbolName(element: PsiElement, kind: SymbolKind): String? {
        // Tier 1: PsiNamedElement (works for methods in Rider)
        if (element is PsiNamedElement) {
            val name = element.name
            if (!name.isNullOrBlank() && name.length > 1 && name !in KEYWORDS) return name
        }
        // Tier 2: reflection getName()
        try {
            val name = element.javaClass.getMethod("getName").invoke(element) as? String
            if (!name.isNullOrBlank() && name.length > 1 && name !in KEYWORDS) return name
        } catch (_: Exception) { }
        // Tier 3: regex — use kind-specific pattern
        return when (kind) {
            SymbolKind.PROPERTY              -> extractPropertyName(element.text)
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.ENUM                  -> extractTypeDeclName(element.text)
            SymbolKind.METHOD                -> extractNameFromText(element.text)
        }
    }

    // Keep old name for call sites that don't have kind context
    private fun extractMethodName(element: PsiElement): String? = extractSymbolName(element, SymbolKind.METHOD)

    private fun extractPropertyName(text: String): String? {
        val firstLine = text.lineSequence().first().trim()
        PROPERTY_NAME_REGEX.find(firstLine)?.let {
            val n = it.groupValues[1]
            if (n !in KEYWORDS) return n
        }
        // Fallback: last capitalized word before { or => on the first line
        val beforeBrace = firstLine.substringBefore("{").substringBefore("=>").trim()
        val lastWord = beforeBrace.split(Regex("\\s+")).lastOrNull()
        if (!lastWord.isNullOrBlank() && lastWord !in KEYWORDS && lastWord.first().isUpperCase()) return lastWord
        return null
    }

    private fun extractTypeDeclName(text: String): String? {
        // Search first 3 lines (class body can be large)
        val head = text.lineSequence().take(3).joinToString(" ")
        TYPE_DECL_NAME_REGEX.find(head)?.let {
            val n = it.groupValues[1]
            if (n !in KEYWORDS) return n
        }
        return null
    }

    private fun extractNameFromText(text: String): String? {
        val firstLine = text.lineSequence().first().trim()
        METHOD_NAME_REGEX.find(firstLine)?.let {
            val n = it.groupValues[1]
            if (n !in KEYWORDS) return n
        }
        val matches = METHOD_NAME_FALLBACK.findAll(firstLine).toList()
        for (m in matches.reversed()) {
            val candidate = m.groupValues[1]
            if (candidate !in KEYWORDS && candidate.isNotBlank() && candidate.first().isUpperCase()) {
                return candidate
            }
        }
        return null
    }

    // ── PSI symbol collector ──────────────────────────────────────────────────

    private fun collectSymbolElements(
        psiFile: PsiFile,
        settings: UnusedMethodsSettings,
        out: CopyOnWriteArrayList<CsSymbolElement>
    ) {
        if (psiFile.name.endsWith(".Designer.cs", ignoreCase = true) ||
            psiFile.name.endsWith(".g.cs",        ignoreCase = true) ||
            psiFile.name.endsWith(".g.i.cs",      ignoreCase = true)) return

        val excluded = settings.excludedNamesList

        walkPsi(psiFile) { element ->
            val typeName = element.javaClass.simpleName
            val kind = symbolKindFor(typeName) ?: return@walkPsi

            // Skip unwanted kinds based on settings
            when (kind) {
                SymbolKind.PROPERTY  -> if (!settings.analyzeProperties) return@walkPsi
                SymbolKind.CLASS     -> if (!settings.analyzeClasses)    return@walkPsi
                SymbolKind.INTERFACE -> if (!settings.analyzeInterfaces) return@walkPsi
                SymbolKind.ENUM      -> if (!settings.analyzeEnums)      return@walkPsi
                SymbolKind.METHOD    -> { /* always collected */ }
            }

            // Method-specific skips
            if (kind == SymbolKind.METHOD) {
                if (typeName.contains("Destructor"))  return@walkPsi
                if (typeName.contains("Constructor")) return@walkPsi
            }

            val name = extractSymbolName(element, kind) ?: return@walkPsi
            if (name.isBlank() || name.length <= 1) return@walkPsi
            if (name in KEYWORDS) return@walkPsi
            if (excluded.any { name.equals(it, ignoreCase = true) }) return@walkPsi

            val isOverride = isOverrideMethod(element)
            if (kind == SymbolKind.METHOD && settings.excludeOverrides && isOverride) return@walkPsi

            val isPrivate = isPrivateAccess(element)
            if (settings.excludePrivate && isPrivate) return@walkPsi

            if (kind == SymbolKind.METHOD) {
                if (settings.excludeTests && isTestMethod(element)) return@walkPsi
                if (isObsolete(element)) return@walkPsi
                if (hasImplicitUseAttribute(element)) return@walkPsi
            }

            val isStatic  = isStaticMethod(element)
            val className = when (kind) {
                SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> ""
                else -> findClassName(element)
            }

            out.add(CsSymbolElement(
                element     = element,
                name        = name,
                className   = className,
                filePath    = psiFile.virtualFile?.path ?: "",
                lineNumber  = getLineNumber(element),
                isPrivate   = isPrivate,
                isOverride  = isOverride,
                isStatic    = isStatic,
                signature   = element.text.lineSequence().first().trim().take(100),
                psiTypeName = typeName,
                kind        = kind
            ))
        }
    }

    // ── PSI helpers ───────────────────────────────────────────────────────────

    private val CLASS_PSI_KEYWORDS = setOf("class", "struct", "interface", "record", "enum")

    private fun findClassName(element: PsiElement): String {
        val classNameRegex = Regex("""(?:class|struct|record|interface|enum)\s+(\w+)""")

        var cur = element.parent
        val parentChain = mutableListOf<String>()

        while (cur != null) {
            val simpleName = cur.javaClass.simpleName
            parentChain.add(simpleName)

            val sn = simpleName.lowercase()

            val isClassLike = CLASS_PSI_KEYWORDS.any { sn.contains(it) }
                && !sn.contains("namespace")
                && !sn.contains("parameter")
                && !sn.contains("using")
                && !sn.contains("method")

            if (isClassLike) {
                if (cur is PsiNamedElement) {
                    val n = cur.name
                    if (!n.isNullOrBlank()) return n
                }
                try {
                    val n = cur.javaClass.getMethod("getName").invoke(cur) as? String
                    if (!n.isNullOrBlank()) return n
                } catch (_: Exception) { }

                val selfText = try { cur.text.take(400) } catch (_: Exception) { "" }
                classNameRegex.find(selfText)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                val parentText = try { cur.parent?.text?.take(600) } catch (_: Exception) { null }
                if (parentText != null) {
                    classNameRegex.find(parentText)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
            }

            if (!sn.contains("file") && !sn.contains("namespace") && !sn.contains("using")) {
                val text = try { cur.text.take(200) } catch (_: Exception) { "" }
                if (text.contains("class ") || text.contains("struct ") || text.contains("record ")) {
                    classNameRegex.find(text)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
            }

            cur = cur.parent
        }

        LOG.warn("UnusedMethods: findClassName Unknown for method in chain: ${parentChain.takeLast(8).joinToString(" → ")}")
        return "Unknown"
    }

    private fun getLineNumber(element: PsiElement): Int =
        element.containingFile?.viewProvider?.document
            ?.getLineNumber(element.textOffset)?.plus(1) ?: 1

    private fun isPrivateAccess(element: PsiElement): Boolean {
        val head = element.text.take(80).lowercase()
        return head.contains("private") && !head.contains("protected")
    }

    private fun isOverrideMethod(element: PsiElement) =
        element.text.take(100).contains("override")

    private fun isStaticMethod(element: PsiElement) =
        element.text.take(100).contains("static")

    private fun isTestMethod(element: PsiElement): Boolean {
        val ctx = buildAttributeContext(element)
        return ctx.contains("[Test") || ctx.contains("[Fact") ||
               ctx.contains("[Theory") || ctx.contains("[TestMethod")
    }

    private fun isObsolete(element: PsiElement): Boolean =
        buildAttributeContext(element).contains("[Obsolete", ignoreCase = true)

    private fun hasImplicitUseAttribute(element: PsiElement): Boolean {
        val ctx = buildAttributeContext(element)
        return IMPLICIT_USE_ATTRIBUTES.any { ctx.contains("[$it") }
    }

    private fun buildAttributeContext(element: PsiElement): String {
        val sb = StringBuilder()
        var sib = element.prevSibling
        var steps = 0
        while (sib != null && steps++ < 6) {
            sb.append(sib.text)
            sib = sib.prevSibling
        }
        sib = element.parent?.prevSibling
        steps = 0
        while (sib != null && steps++ < 3) {
            sb.append(sib.text)
            sib = sib.prevSibling
        }
        return sb.toString()
    }

    private fun walkPsi(root: PsiElement, visitor: (PsiElement) -> Unit) {
        val stack = ArrayDeque<PsiElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            visitor(el)
            el.children.reversed().forEach { stack.addLast(it) }
        }
    }

    // ── File collection ───────────────────────────────────────────────────────

    fun collectCsFiles(project: Project, root: VirtualFile?): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val seen   = mutableSetOf<String>()

        if (root != null) {
            ReadAction.run<Exception> { collectRecursive(root, result, seen, setOf("cs")) }
            return result
        }

        ReadAction.run<Exception> {
            val roots = ProjectRootManager.getInstance(project).contentRoots
            roots.forEach { collectRecursive(it, result, seen, setOf("cs")) }
        }

        val basePath = project.basePath
        if (basePath != null) {
            val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(basePath)
            if (baseDir != null) {
                ReadAction.run<Exception> { collectRecursive(baseDir, result, seen, setOf("cs")) }
            }
        }

        if (basePath != null) {
            val extraDirs = findProjectDirsFromSln(basePath)
            for (dir in extraDirs) {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(dir)
                if (vf != null) {
                    ReadAction.run<Exception> { collectRecursive(vf, result, seen, setOf("cs")) }
                }
            }
        }

        return result.distinctBy { it.path }
    }

    private fun findProjectDirsFromSln(basePath: String): List<String> {
        val result = mutableListOf<String>()
        val base = java.io.File(basePath)

        val slnFile = sequenceOf(base, base.parentFile)
            .filterNotNull()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.extension.equals("sln", ignoreCase = true) }
            ?: return result

        val projectLineRegex = Regex("""Project\("[^"]*"\)\s*=\s*"[^"]*",\s*"([^"]+\.csproj)"""")
        try {
            slnFile.forEachLine { line ->
                val match = projectLineRegex.find(line) ?: return@forEachLine
                val relativePath = match.groupValues[1].replace('\\', '/')
                val projectFile = java.io.File(slnFile.parent, relativePath).canonicalFile
                val projectDir = projectFile.parentFile
                if (projectDir.exists() && projectDir.canonicalPath !in result) {
                    result.add(projectDir.canonicalPath)
                }
            }
        } catch (e: Exception) {
            LOG.warn("UnusedMethods: failed to parse SLN: ${e.message}")
        }

        return result
    }

    private fun collectRecursive(
        dir: VirtualFile,
        out: MutableList<VirtualFile>,
        seen: MutableSet<String>,
        extensions: Set<String>
    ) {
        if (!dir.isValid) return
        for (child in dir.children ?: return) {
            when {
                child.isDirectory && child.name !in SKIP_DIRS ->
                    collectRecursive(child, out, seen, extensions)
                !child.isDirectory && child.extension?.lowercase() in extensions && seen.add(child.path) ->
                    out.add(child)
            }
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class CsSymbolElement(
        val element: PsiElement,
        val name: String,
        val className: String,
        val filePath: String,
        val lineNumber: Int,
        val isPrivate: Boolean,
        val isOverride: Boolean,
        val isStatic: Boolean,
        val signature: String,
        val psiTypeName: String,
        val kind: SymbolKind
    ) {
        fun toMethodInfo() = MethodInfo(
            name, signature, className, filePath, lineNumber, isPrivate, isOverride, isStatic, kind
        )
    }

    // Kept for backward compat — used by some callers
    @Deprecated("Use CsSymbolElement", ReplaceWith("CsSymbolElement"))
    data class CsMethodElement(
        val element: PsiElement,
        val name: String,
        val className: String,
        val filePath: String,
        val lineNumber: Int,
        val isPrivate: Boolean,
        val isOverride: Boolean,
        val isStatic: Boolean,
        val signature: String,
        val psiTypeName: String
    ) {
        fun toMethodInfo() = MethodInfo(
            name, signature, className, filePath, lineNumber, isPrivate, isOverride, isStatic
        )
    }

    private val KEYWORDS = setOf(
        "if", "while", "for", "foreach", "switch", "catch", "using", "return", "new", "var",
        "get", "set", "add", "remove", "where", "select", "from", "lock", "throw", "await",
        "yield", "class", "struct", "interface", "enum", "record", "namespace", "delegate",
        "true", "false", "null", "base", "this", "value", "async", "static", "void", "int",
        "string", "bool", "double", "float", "decimal", "object", "long", "byte", "char"
    )
}
