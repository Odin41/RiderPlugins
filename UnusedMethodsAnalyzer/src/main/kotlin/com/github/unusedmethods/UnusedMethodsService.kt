package com.github.unusedmethods

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Project-level service that holds analysis results and persists them between
 * IDE restarts via IntelliJ's PersistentStateComponent mechanism.
 *
 * State is stored in .idea/unusedMethods.xml (or workspace.xml depending on
 * storages config). Only serialisable data (MethodInfo fields) is saved —
 * no PSI elements.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "UnusedMethodsAnalyzer",
    storages = [Storage("unusedMethods.xml")]
)
class UnusedMethodsService : PersistentStateComponent<UnusedMethodsService.PersistedState> {

    // ── Persisted state (XML-serialisable) ────────────────────────────────────

    /**
     * Only primitive/String fields are serialised by IntelliJ's XmlSerializer.
     * We store each MethodInfo as a flat PersistedMethod bean.
     */
    data class PersistedMethod(
        @JvmField var name: String        = "",
        @JvmField var className: String   = "",
        @JvmField var filePath: String    = "",
        @JvmField var lineNumber: Int     = 0,
        @JvmField var signature: String   = "",
        @JvmField var isPrivate: Boolean  = false,
        @JvmField var isStatic: Boolean   = false,
        @JvmField var isOverride: Boolean = false
    )

    class PersistedState {
        @JvmField var methods: MutableList<PersistedMethod> = mutableListOf()
        @JvmField var scope: String          = "project"
        @JvmField var scannedFiles: Int      = 0
        @JvmField var scannedMethods: Int    = 0
    }

    // ── In-memory runtime state ───────────────────────────────────────────────

    data class AnalysisState(
        val results: List<MethodInfo>  = emptyList(),
        val scope: String              = "project",
        val message: String            = "",
        val scannedFiles: Int          = 0,
        val scannedMethods: Int        = 0
    )

    private var runtimeState = AnalysisState()
    private val listeners    = mutableListOf<(AnalysisState) -> Unit>()

    // ── PersistentStateComponent ──────────────────────────────────────────────

    override fun getState(): PersistedState {
        val ps = PersistedState()
        ps.scope          = runtimeState.scope
        ps.scannedFiles   = runtimeState.scannedFiles
        ps.scannedMethods = runtimeState.scannedMethods
        ps.methods        = runtimeState.results.map { m ->
            PersistedMethod(
                name       = m.name,
                className  = m.className,
                filePath   = m.filePath,
                lineNumber = m.lineNumber,
                signature  = m.signature,
                isPrivate  = m.isPrivate,
                isStatic   = m.isStatic,
                isOverride = m.isOverride
            )
        }.toMutableList()
        return ps
    }

    override fun loadState(ps: PersistedState) {
        val restored = ps.methods.map { p ->
            MethodInfo(
                name       = p.name,
                className  = p.className,
                filePath   = p.filePath,
                lineNumber = p.lineNumber,
                signature  = p.signature,
                isPrivate  = p.isPrivate,
                isStatic   = p.isStatic,
                isOverride = p.isOverride
            )
        }
        runtimeState = AnalysisState(
            results        = restored,
            scope          = ps.scope,
            scannedFiles   = ps.scannedFiles,
            scannedMethods = ps.scannedMethods
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRuntimeState(): AnalysisState = runtimeState

    fun setResults(result: CSharpAnalyzer.AnalysisResult, scope: String = "project") {
        runtimeState = AnalysisState(
            results        = result.methods,
            scope          = scope,
            message        = result.message,
            scannedFiles   = result.scannedFiles,
            scannedMethods = result.scannedMethods
        )
        notifyListeners()
    }

    fun countForFile(filePath: String) = runtimeState.results.count { it.filePath == filePath }
    fun getForFile(filePath: String)   = runtimeState.results.filter { it.filePath == filePath }

    fun addListener(l: (AnalysisState) -> Unit)    { listeners.add(l) }
    fun removeListener(l: (AnalysisState) -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        val snap = runtimeState
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it(snap) }
        }
    }

    companion object {
        fun getInstance(project: Project): UnusedMethodsService = project.service()
    }
}
