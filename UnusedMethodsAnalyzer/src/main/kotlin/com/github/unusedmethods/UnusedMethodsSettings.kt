package com.github.unusedmethods

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service
@State(
    name = "UnusedMethodsAnalyzer",
    storages = [Storage("UnusedMethodsAnalyzer.xml")]
)
class UnusedMethodsSettings : PersistentStateComponent<UnusedMethodsSettings.State> {

    data class State(
        var obsoleteText: String = "Не используется в проекте",
        var excludePrivate: Boolean = false,
        var excludeOverrides: Boolean = true,
        var excludeEventHandlers: Boolean = true,
        var excludeTests: Boolean = true,
        var excludedNames: String = "Main,Dispose,ToString,GetHashCode,Equals,GetEnumerator"
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(s: State) { myState = s }

    var obsoleteText: String        get() = myState.obsoleteText;        set(v) { myState.obsoleteText = v }
    var excludePrivate: Boolean     get() = myState.excludePrivate;      set(v) { myState.excludePrivate = v }
    var excludeOverrides: Boolean   get() = myState.excludeOverrides;    set(v) { myState.excludeOverrides = v }
    var excludeEventHandlers: Boolean get() = myState.excludeEventHandlers; set(v) { myState.excludeEventHandlers = v }
    var excludeTests: Boolean       get() = myState.excludeTests;        set(v) { myState.excludeTests = v }
    var excludedNames: String       get() = myState.excludedNames;       set(v) { myState.excludedNames = v }

    val excludedNamesList: List<String>
        get() = excludedNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun getInstance(): UnusedMethodsSettings = service()
    }
}
