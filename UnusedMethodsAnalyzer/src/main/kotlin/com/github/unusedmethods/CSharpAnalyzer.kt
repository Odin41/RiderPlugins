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

    // Matches C# method declaration first line, captures method name.
    // e.g. "public override void OnActionExecuting(" → "OnActionExecuting"
    //      "private async Task<bool> OnSubmitAsync(" → "OnSubmitAsync"
    private val METHOD_NAME_REGEX = Regex(
        """^[\s]*(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|extern|unsafe|partial|new)\s+)+""" +
        """(?:[\w<>\[\],.\s?]+?\s+)""" +
        """(\w+)\s*[(<]"""
    )

    private val METHOD_NAME_FALLBACK = Regex("""(\w+)\s*[(<]""")

    // Attributes that imply implicit usage — methods with these should be skipped
    private val IMPLICIT_USE_ATTRIBUTES = setOf(
        "UsedImplicitly", "ApiExplorerSettings", "HttpGet", "HttpPost", "HttpPut",
        "HttpDelete", "HttpPatch", "Route", "Authorize", "AllowAnonymous",
        "JSInvokable", "Parameter", "Inject", "OneWay", "TwoWay", "CascadingParameter"
    )

    // ── Public entry points ───────────────────────────────────────────────────

    fun analyze(project: Project, indicator: ProgressIndicator): AnalysisResult {
        val scope = GlobalSearchScope.projectScope(project)
        val files = collectCsFiles(project, null)
        val markupFiles = collectMarkupFiles(project, null)
        // For cs text search: use ALL cs files in project (not just the analyzed ones)
        return analyzeFiles(project, files, markupFiles, files, scope, indicator)
    }

    fun analyzeDirectory(
        project: Project,
        directory: VirtualFile,
        indicator: ProgressIndicator
    ): AnalysisResult {
        val files = collectCsFiles(project, directory)
        val markupFiles = collectMarkupFiles(project, directory)
        // For cs text search: use whole project scope (callers may be outside selected dir)
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

        // Pre-load markup content for fast text-search in Pass 2
        indicator.text = "Loading markup files..."
        val markupContent = buildMarkupIndex(markupFiles)
        debug.appendLine("Markup index: ${markupContent.size} files loaded")

        // Build cs text index for fallback reference check.
        // Covers: generic method calls GetError(...), interface method calls via variable,
        // delegate assignments, reflection strings, and any other case PSI misses.
        indicator.text = "Building C# text index..."
        val csTextIndex = buildCsTextIndex(allCsFiles, files)
        debug.appendLine("C# text index: ${csTextIndex.size} files indexed")

        val settings = UnusedMethodsSettings.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val candidates = CopyOnWriteArrayList<CsMethodElement>()

        // ── Pass 1: collect PSI method declarations ───────────────────────────
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
                collectMethodElements(psiFile, settings, candidates)
            }
        }

        val psiTypesFound = candidates.map { it.psiTypeName }.toSet()
        debug.appendLine("PSI types matched: $psiTypesFound")
        debug.appendLine("Candidates after Pass 1: ${candidates.size}")
        LOG.info("UnusedMethods: Pass1 done — ${candidates.size} candidates")

        if (candidates.isEmpty()) {
            val msg = "0 method declarations found. See Help > Show Log for PSI dump."
            return AnalysisResult(emptyList(), msg, scannedFiles = files.size, debugInfo = debug.toString())
        }

        // ── Pass 2: check references ──────────────────────────────────────────
        // ReferencesSearch requires smart mode (indices must be ready).
        indicator.text = "Waiting for indices..."
        DumbService.getInstance(project).waitForSmartMode()

        indicator.text = "Checking references (${candidates.size} methods)..."
        indicator.fraction = 0.40

        val unusedMethods = CopyOnWriteArrayList<MethodInfo>()
        val totalMethods  = candidates.size
        val doneMethods   = AtomicInteger(0)

        // Single thread to avoid read-lock deadlocks with ReferencesSearch
        val executor = Executors.newSingleThreadExecutor()

        candidates.forEach { candidate ->
            executor.submit {
                if (indicator.isCanceled) return@submit
                val done = doneMethods.incrementAndGet()
                if (done % 20 == 0) {
                    indicator.fraction = 0.40 + 0.55 * done / totalMethods
                    indicator.text2 = "${candidate.className}.${candidate.name} ($done/$totalMethods)"
                }

                // ── Step A: PSI ReferencesSearch ──────────────────────────────
                // IntelliJ's ReferencesSearch is the only PSI-level search available
                // from Kotlin in Rider. ReSharper's IFinder lives in the C# backend
                // process and is not callable from Kotlin directly.
                //
                // ignoreAccessScope=true is critical: it tells the search to cross
                // visibility boundaries, which is necessary for finding calls through
                // interfaces (IBankPaymentService.InitPaymentAsync) and for private
                // methods called from nested/partial classes.
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

                // ── Step B: markup text search ───────────────────────────────
                // razor/cshtml/xaml event attributes: OnValidSubmit="MethodName"
                if (isUsedInMarkup(candidate.name, markupContent)) return@submit

                // ── Step C: C# text search (last resort) ─────────────────────
                // Catches what PSI misses due to Rider frontend/backend split:
                //   - Generic methods:    GetError(arg) declared as GetError<T>(...)
                //   - Interface dispatch: bankService.InitPaymentAsync(...)
                //   - Delegates:          action = MethodName
                //   - nameof/reflection:  nameof(MethodName), "MethodName"
                //
                // False-positive protection:
                //   - Word boundaries: "GetError" won't match "GetErrorCount"
                //   - Short names (≤5 chars): requires call syntax Name( or Name<
                //   - Declaring file: requires ≥2 occurrences (decl + usage)
                val textUsed = isUsedInCsText(candidate.name, candidate.filePath, csTextIndex)
                if (textUsed) {
                    LOG.info("UnusedMethods: rescued by text search: ${candidate.className}.${candidate.name}")
                    return@submit
                }

                // ── Confirmed unused ──────────────────────────────────────────
                LOG.info("UnusedMethods: UNUSED ${candidate.className}.${candidate.name} " +
                    "[file=${candidate.filePath.substringAfterLast('/')} line=${candidate.lineNumber}]")
                unusedMethods.add(candidate.toMethodInfo())
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

        val result = unusedMethods.sortedWith(compareBy({ it.filePath }, { it.lineNumber }))
        debug.appendLine("Unused found: ${result.size} of $totalMethods")
        LOG.info("UnusedMethods: done — ${result.size} unused / $totalMethods in $where")

        return AnalysisResult(
            methods        = result,
            message        = "Files: ${files.size}  |  Methods scanned: $totalMethods  |  Unused: ${result.size}",
            scannedFiles   = files.size,
            scannedMethods = totalMethods,
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
        LOG.info("UnusedMethods: found ${result.size} markup files")
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

    /**
     * True if the method name appears in markup (razor/cshtml/xaml) as any kind of reference.
     *
     * Covers:
     *   OnValidSubmit="OnSubmitAsync"                        — direct attribute value
     *   @onclick="_ref!.ResetSettings"                       — method group via field
     *   nameof(NotificationsController.SwitchOffConfiguration) — nameof expression
     *   @MethodName(  or  ="@MethodName"                     — Blazor inline call
     *
     * Pattern: right boundary (?!\w) prevents "ResetSettingsAll" from matching "ResetSettings".
     * Left side: we do NOT restrict — the name may be preceded by dot (.ResetSettings),
     * quote, equals, space, or exclamation (!.ResetSettings for null-forgiving operator).
     */
    private fun isUsedInMarkup(methodName: String, markupContent: Map<String, String>): Boolean {
        // Right-boundary only: methodName not followed by a word character
        val pattern = Regex("""${Regex.escape(methodName)}(?!\w)""")
        for (content in markupContent.values) {
            if (!content.contains(methodName)) continue   // fast pre-check
            if (pattern.containsMatchIn(content)) return true
        }
        return false
    }

    /**
     * Build a text index of all .cs files.
     * Memory estimate: 1670 files × ~5KB avg ≈ 8MB — acceptable.
     */
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

    /**
     * True if [methodName] is used (called/referenced) anywhere in the C# codebase.
     *
     * Handles cases ReferencesSearch misses:
     *   _geconService.CreateOrUpdateRequestAsync(model) — call via field/interface
     *   GetError(arg)                                   — generic method call
     *   nameof(MethodName)                              — nameof expression
     *   handler += MethodName                           — delegate assignment
     *
     * IMPORTANT: The lookbehind must NOT exclude dot, because the most common
     * call pattern is `object.MethodName(`. We only exclude word chars (\w)
     * on the left to avoid matching "SomeOtherCreateOrUpdateRequestAsync".
     * On the right we exclude \w to avoid "CreateOrUpdateRequestAsyncInternal".
     *
     * False-positive protection:
     *   - Right boundary (?!\w): "GetError" won't match "GetErrorCount"
     *   - Short names (≤5 chars): require call/generic syntax Name( or Name<
     *   - Declaring file: require ≥2 occurrences (declaration itself + one usage)
     */
    private fun isUsedInCsText(
        methodName: String,
        declaringFilePath: String,
        csIndex: Map<String, String>
    ): Boolean {
        // Left boundary: not preceded by a word char (letter/digit/_)
        // Dot is allowed on the left — that's the normal call pattern: obj.Method(
        // Right boundary: not followed by a word char
        val broadPattern  = Regex("""(?<!\w)${Regex.escape(methodName)}(?!\w)""")
        // For short names additionally require ( or < immediately after (with optional spaces)
        val strictPattern = Regex("""(?<!\w)${Regex.escape(methodName)}\s*[(<]""")
        val pattern = if (methodName.length <= 5) strictPattern else broadPattern

        for ((path, content) in csIndex) {
            if (!content.contains(methodName)) continue   // fast pre-filter
            val matches = pattern.findAll(content).count()
            if (path == declaringFilePath) {
                // Declaration line itself = 1 hit; need ≥1 additional usage
                if (matches >= 2) return true
            } else {
                if (matches >= 1) return true
            }
        }
        return false
    }

    // ── PSI dump (diagnostic) ─────────────────────────────────────────────────

    private fun dumpPsiTree(psiFile: PsiFile, out: StringBuilder) {
        out.appendLine("=== PSI dump: ${psiFile.name} ===")
        LOG.info("UnusedMethods: === PSI dump for ${psiFile.name} ===")
        val seen = mutableSetOf<String>()
        walkPsi(psiFile) { el ->
            val simple = el.javaClass.simpleName
            if (seen.add(simple)) {
                val nameIF   = (el as? PsiNamedElement)?.name ?: ""
                val nameRefl = try { el.javaClass.getMethod("getName").invoke(el) as? String ?: "" } catch (_: Exception) { "" }
                val nameRx   = if (isMethodLike(simple)) extractNameFromText(el.text) ?: "" else ""
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

    private fun extractMethodName(element: PsiElement): String? {
        // Tier 1: PsiNamedElement interface
        if (element is PsiNamedElement) {
            val name = element.name
            if (!name.isNullOrBlank() && name.length > 1 && name !in KEYWORDS) return name
        }
        // Tier 2: reflection getName()
        try {
            val name = element.javaClass.getMethod("getName").invoke(element) as? String
            if (!name.isNullOrBlank() && name.length > 1 && name !in KEYWORDS) return name
        } catch (_: Exception) { }
        // Tier 3: regex on element text (primary path for Rider C# PSI)
        return extractNameFromText(element.text)
    }

    private fun extractNameFromText(text: String): String? {
        val firstLine = text.lineSequence().first().trim()
        METHOD_NAME_REGEX.find(firstLine)?.let {
            val n = it.groupValues[1]
            if (n !in KEYWORDS) return n
        }
        // Fallback: last capitalized word before (
        val matches = METHOD_NAME_FALLBACK.findAll(firstLine).toList()
        for (m in matches.reversed()) {
            val candidate = m.groupValues[1]
            if (candidate !in KEYWORDS && candidate.isNotBlank() && candidate.first().isUpperCase()) {
                return candidate
            }
        }
        return null
    }

    // ── PSI method collector ──────────────────────────────────────────────────

    private fun collectMethodElements(
        psiFile: PsiFile,
        settings: UnusedMethodsSettings,
        out: CopyOnWriteArrayList<CsMethodElement>
    ) {
        if (psiFile.name.endsWith(".Designer.cs", ignoreCase = true) ||
            psiFile.name.endsWith(".g.cs",        ignoreCase = true) ||
            psiFile.name.endsWith(".g.i.cs",      ignoreCase = true)) return

        val excluded = settings.excludedNamesList

        walkPsi(psiFile) { element ->
            val typeName = element.javaClass.simpleName
            if (!isMethodLike(typeName)) return@walkPsi

            // Always skip destructors and constructors
            // Constructors are invoked via `new ClassName()` — ReferencesSearch won't
            // find those as references to the constructor PSI element in Rider.
            if (typeName.contains("Destructor")) return@walkPsi
            if (typeName.contains("Constructor")) return@walkPsi

            val name = extractMethodName(element) ?: return@walkPsi
            if (name.isBlank() || name.length <= 1) return@walkPsi
            if (name in KEYWORDS) return@walkPsi
            if (excluded.any { name.equals(it, ignoreCase = true) }) return@walkPsi

            val isOverride = isOverrideMethod(element)
            if (settings.excludeOverrides && isOverride) return@walkPsi

            val isPrivate = isPrivateAccess(element)
            if (settings.excludePrivate && isPrivate) return@walkPsi

            if (settings.excludeTests && isTestMethod(element)) return@walkPsi
            if (isObsolete(element)) return@walkPsi
            if (hasImplicitUseAttribute(element)) return@walkPsi

            val isStatic  = isStaticMethod(element)
            val className = findClassName(element)

            out.add(CsMethodElement(
                element     = element,
                name        = name,
                className   = className,
                filePath    = psiFile.virtualFile?.path ?: "",
                lineNumber  = getLineNumber(element),
                isPrivate   = isPrivate,
                isOverride  = isOverride,
                isStatic    = isStatic,
                signature   = element.text.lineSequence().first().trim().take(100),
                psiTypeName = typeName
            ))
        }
    }

    // ── PSI helpers ───────────────────────────────────────────────────────────

    private val METHOD_PSI_TYPES = setOf(
        "CSharpMethodDeclaration",
        "CSharpMethodDeclarationImpl",
        "CSharpConstructorDeclaration",
        "CSharpConstructorDeclarationImpl",
        "CSharpDestructorDeclaration",
        "CSharpDestructorDeclarationImpl"
    )

    private fun isMethodLike(typeName: String) = typeName in METHOD_PSI_TYPES

    private val CLASS_PSI_KEYWORDS = setOf("class", "struct", "interface", "record", "enum")

    /**
     * Walk parent chain to find the containing class/struct/record name.
     *
     * Rider PSI class nodes don't implement PsiNamedElement and getName() returns null,
     * so we rely on Tier 3: regex on the node's own text.
     *
     * Key fix: cur.text for a class body starts with `{` after the signature,
     * so we must look at the PARENT of the class node to get the full "class Foo {" line,
     * or use the first line of the class body's parent text.
     * We try both the node itself and its parent to find the class keyword + name.
     */
    private fun findClassName(element: PsiElement): String {
        val classNameRegex = Regex("""(?:class|struct|record|interface|enum)\s+(\w+)""")

        var cur = element.parent
        val parentChain = mutableListOf<String>() // for diagnostics

        while (cur != null) {
            val simpleName = cur.javaClass.simpleName
            parentChain.add(simpleName)

            val sn = simpleName.lowercase()

            // Match any PSI node whose type suggests it's a class/struct/record container.
            // We deliberately do NOT require "declaration" in the name — Rider uses
            // CSharpDummyDeclaration for the outer wrapper that contains both attributes
            // and the actual class body. The real class PSI type name varies.
            val isClassLike = CLASS_PSI_KEYWORDS.any { sn.contains(it) }
                && !sn.contains("namespace")
                && !sn.contains("parameter")
                && !sn.contains("using")
                && !sn.contains("method")

            if (isClassLike) {
                // Tier 1: PsiNamedElement
                if (cur is PsiNamedElement) {
                    val n = cur.name
                    if (!n.isNullOrBlank()) return n
                }
                // Tier 2: reflection getName()
                try {
                    val n = cur.javaClass.getMethod("getName").invoke(cur) as? String
                    if (!n.isNullOrBlank()) return n
                } catch (_: Exception) { }

                // Tier 3a: regex on node's own first 400 chars
                val selfText = try { cur.text.take(400) } catch (_: Exception) { "" }
                classNameRegex.find(selfText)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                // Tier 3b: regex on parent's text (catches body-only nodes starting with {)
                val parentText = try { cur.parent?.text?.take(600) } catch (_: Exception) { null }
                if (parentText != null) {
                    classNameRegex.find(parentText)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
            }

            // Always try regex on every node's text as a last resort —
            // even if isClassLike is false, the parent might have the class keyword
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

        // Log the full parent chain when class name not found — helps diagnose Unknown
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

    /**
     * Skip methods with attributes implying implicit/framework usage:
     * API controllers, DI injected, Blazor parameters, JSInterop, etc.
     */
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
            LOG.info("UnusedMethods: directory scan '${root.path}' → ${result.size} files")
            return result
        }

        // Strategy 1: IntelliJ content roots (may return 0 for Rider — known issue)
        ReadAction.run<Exception> {
            val roots = ProjectRootManager.getInstance(project).contentRoots
            LOG.info("UnusedMethods: contentRoots count = ${roots.size}, paths=${roots.map{it.path}}")
            roots.forEach { collectRecursive(it, result, seen, setOf("cs")) }
        }
        LOG.info("UnusedMethods: after contentRoots: ${result.size} files")

        // Strategy 2: project.basePath recursive walk
        val basePath = project.basePath
        LOG.info("UnusedMethods: basePath = $basePath")
        if (basePath != null) {
            val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(basePath)
            if (baseDir != null) {
                ReadAction.run<Exception> { collectRecursive(baseDir, result, seen, setOf("cs")) }
                LOG.info("UnusedMethods: after basePath scan: ${result.size} files")
            }
        }

        // Strategy 3: scan for .sln file and find ALL referenced .csproj directories.
        // This catches projects that live outside basePath (e.g. shared libraries,
        // helper projects in sibling folders referenced via relative path in .sln).
        if (basePath != null) {
            val extraDirs = findProjectDirsFromSln(basePath)
            LOG.info("UnusedMethods: SLN-referenced project dirs: ${extraDirs.size}")
            for (dir in extraDirs) {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(dir)
                if (vf != null) {
                    ReadAction.run<Exception> { collectRecursive(vf, result, seen, setOf("cs")) }
                }
            }
            LOG.info("UnusedMethods: after SLN project scan: ${result.size} files")
        }

        return result.distinctBy { it.path }
    }

    /**
     * Parse .sln file in [basePath] (or its parent) to find all referenced .csproj
     * directories — including those outside basePath (relative paths like ../Helpers/...).
     * Returns absolute directory paths for each project.
     */
    private fun findProjectDirsFromSln(basePath: String): List<String> {
        val result = mutableListOf<String>()
        val base = java.io.File(basePath)

        // Find .sln in basePath or one level up (some solutions have sln in parent)
        val slnFile = sequenceOf(base, base.parentFile)
            .filterNotNull()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.extension.equals("sln", ignoreCase = true) }
            ?: return result

        LOG.info("UnusedMethods: found SLN: ${slnFile.path}")

        // SLN project lines look like:
        // Project("{...}") = "ProjectName", "relative\path\To.csproj", "{GUID}"
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
