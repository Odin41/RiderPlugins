package com.github.nsconverter

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

class ConvertNamespaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * Show the action only when at least one selected .cs file
     * contains a block-scoped namespace.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val candidates = getTargetFiles(e)
        val convertibleCount = candidates.count { file ->
            val text = readText(file) ?: return@count false
            NamespaceConverter.hasBlockScopedNamespace(text)
        }

        e.presentation.isEnabledAndVisible = convertibleCount > 0
        e.presentation.text = if (convertibleCount > 1)
            "Namespace: Remove Curly Braces ($convertibleCount files)"
        else
            "Namespace: Remove Curly Braces"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getTargetFiles(e)

        val toConvert = files.filter { file ->
            val text = readText(file) ?: return@filter false
            NamespaceConverter.hasBlockScopedNamespace(text)
        }

        if (toConvert.isEmpty()) {
            notify(project, "No block-scoped namespaces found in selected files.", NotificationType.INFORMATION)
            return
        }

        var converted = 0
        val errors = mutableListOf<String>()

        for (file in toConvert) {
            when (val result = convertFile(project, file)) {
                is ConvertResult.Success -> converted++
                is ConvertResult.Error   -> errors.add("${file.name}: ${result.message}")
                is ConvertResult.Skipped -> Unit
            }
        }

        when {
            errors.isEmpty() && converted == 1 ->
                notify(project, "Namespace converted successfully.", NotificationType.INFORMATION)
            errors.isEmpty() && converted > 1 ->
                notify(project, "$converted files converted successfully.", NotificationType.INFORMATION)
            errors.isNotEmpty() && converted > 0 ->
                notify(project,
                    "$converted file(s) converted. ${errors.size} error(s):\n${errors.joinToString("\n")}",
                    NotificationType.WARNING)
            else ->
                notify(project,
                    "Conversion failed:\n${errors.joinToString("\n")}",
                    NotificationType.ERROR)
        }
    }

    // ---------------------------------------------------------------------------
    // Core per-file conversion
    // ---------------------------------------------------------------------------

    private fun convertFile(project: Project, file: VirtualFile): ConvertResult {
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file)
            ?: return ConvertResult.Error("Cannot open document")

        val originalText = document.text
        if (!NamespaceConverter.hasBlockScopedNamespace(originalText))
            return ConvertResult.Skipped

        val convertedText = try {
            NamespaceConverter.convert(originalText)
        } catch (ex: NamespaceConverter.ConversionException) {
            return ConvertResult.Error(ex.message ?: "Unknown error")
        }

        WriteCommandAction.runWriteCommandAction(
            project, "Convert to File-Scoped Namespace", null, {
                document.setText(convertedText)

                val psiDocManager = PsiDocumentManager.getInstance(project)
                psiDocManager.commitDocument(document)

                val psiFile = psiDocManager.getPsiFile(document)
                if (psiFile != null) {
                    com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                        .reformat(psiFile)
                }

                documentManager.saveDocument(document)
            }
        )

        return ConvertResult.Success
    }

    private sealed class ConvertResult {
        object Success : ConvertResult()
        object Skipped : ConvertResult()
        data class Error(val message: String) : ConvertResult()
    }

    // ---------------------------------------------------------------------------
    // File resolution helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns all convertible .cs files from the current action context.
     * Supports multi-selection in the project tree via VIRTUAL_FILE_ARRAY.
     */
    private fun getTargetFiles(e: AnActionEvent): List<VirtualFile> {
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!selectedFiles.isNullOrEmpty()) {
            val csFiles = selectedFiles.filter { !it.isDirectory && it.extension?.lowercase() == "cs" }
            if (csFiles.isNotEmpty()) return csFiles
        }

        val single = getSingleFile(e)
        return if (single != null) listOf(single) else emptyList()
    }

    private fun getSingleFile(e: AnActionEvent): VirtualFile? {
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (selectedFile != null && !selectedFile.isDirectory) return selectedFile

        val editor  = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        if (editor != null && project != null) {
            val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
            return PsiManager.getInstance(project).findFile(vf)?.virtualFile
        }
        return null
    }

    private fun readText(file: VirtualFile): String? {
        val doc = FileDocumentManager.getInstance().getDocument(file)
        if (doc != null) return doc.text
        return try { file.contentsToByteArray().toString(Charsets.UTF_8) } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------------------

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Namespace Converter")
            .createNotification(message, type)
            .notify(project)
    }
}
