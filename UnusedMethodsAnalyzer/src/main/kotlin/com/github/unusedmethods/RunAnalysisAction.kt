package com.github.unusedmethods

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager

class RunAnalysisAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow("Unused Methods")?.activate(null)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Analyzing project for unused methods…", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = CSharpAnalyzer.analyze(project, indicator)
                ApplicationManager.getApplication().invokeLater {
                    UnusedMethodsService.getInstance(project).setResults(result, "project")
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
