package com.github.unusedmethods

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

class AnalyzeFolderAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = getSelectedDirectory(e) ?: return

        // Open tool window
        ToolWindowManager.getInstance(project)
            .getToolWindow("Unused Methods")?.activate(null)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Analyzing '${dir.name}' for unused methods…", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = CSharpAnalyzer.analyzeDirectory(project, dir, indicator)
                ApplicationManager.getApplication().invokeLater {
                    UnusedMethodsService.getInstance(project).setResults(result, dir.name)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val dir = getSelectedDirectory(e)
        e.presentation.isEnabledAndVisible = dir != null && e.project != null
        if (dir != null) {
            e.presentation.text = "Analyze Unused Methods in '${dir.name}'"
        }
    }

    private fun getSelectedDirectory(e: AnActionEvent): VirtualFile? {
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return if (vf.isDirectory) vf else null
    }
}
