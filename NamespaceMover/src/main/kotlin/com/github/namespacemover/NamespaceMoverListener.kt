package com.github.namespacemover

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

// -----------------------------------------------------------------------------
//  Registered via <applicationListeners> in plugin.xml — no-arg constructor.
//  BulkFileListener is application-level; project is resolved per-event.
// -----------------------------------------------------------------------------

class NamespaceMoverBulkListener : BulkFileListener {

    private val LOG = Logger.getInstance(NamespaceMoverBulkListener::class.java)

    override fun after(events: List<VFileEvent>) {
        val moves = events.filterIsInstance<VFileMoveEvent>()
        if (moves.isEmpty()) return

        for (event in moves) {
            val file = event.file
            if (!file.name.endsWith(".cs")) continue
            if (event.oldParent == event.newParent) continue
            val project = findProjectForFile(file) ?: continue
            processMoveEvent(file, project)
        }
    }

    private fun processMoveEvent(file: VirtualFile, project: Project) {
        val currentContent = try {
            ApplicationManager.getApplication().runReadAction<String> {
                String(file.contentsToByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            LOG.warn("NamespaceMover: cannot read " + file.name + ": " + e.message)
            return
        }

        val existingNamespaces = NamespaceParser.findAll(currentContent)
        if (existingNamespaces.isEmpty()) return

        val newNamespace = NamespaceCalculator.calculate(file, project) ?: return

        if (existingNamespaces.all { it == newNamespace }) return

        val oldNamespace = existingNamespaces.first()

        ApplicationManager.getApplication().invokeLater({
            val confirmed = NamespaceMoverDialog(
                project  = project,
                fileName = file.name,
                oldNs    = oldNamespace,
                newNs    = newNamespace,
                nsCount  = existingNamespaces.size
            ).showAndGetResult()

            if (!confirmed) return@invokeLater

            WriteCommandAction.runWriteCommandAction(project, "Update Namespace", null, {
                try {
                    val doc = FileDocumentManager.getInstance().getDocument(file)
                    if (doc != null) {
                        doc.setText(NamespaceParser.replaceAll(doc.text, newNamespace))
                        FileDocumentManager.getInstance().saveDocument(doc)
                    } else {
                        file.setBinaryContent(
                            NamespaceParser.replaceAll(currentContent, newNamespace)
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                    notify(project,
                        "Updated namespace in " + file.name + "\n" + oldNamespace + "  ->  " + newNamespace,
                        NotificationType.INFORMATION)
                } catch (e: Exception) {
                    LOG.error("NamespaceMover: write failed", e)
                    notify(project, "Failed to update namespace: " + e.message, NotificationType.ERROR)
                }
            })
        }, ModalityState.nonModal())
    }

    // ProjectFileIndex.isInContent() returns false in Rider immediately after
    // a move event — the index hasn't updated yet. Path-based fallback is required.
    private fun findProjectForFile(file: VirtualFile): Project? {
        for (p in ProjectManager.getInstance().openProjects) {
            if (p.isDisposed) continue
            try {
                if (ProjectFileIndex.getInstance(p).isInContent(file)) return p
            } catch (e: Exception) {
                // ignore, fall through to path check
            }
            if (p.basePath != null && file.path.startsWith(p.basePath!!)) return p
        }
        return null
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Namespace Mover")
            .createNotification(msg, type)
            .notify(project)
    }
}

// -----------------------------------------------------------------------------
//  Namespace parser — file-scoped and block-scoped support
//
//  KEY LESSONS (file handling in IntelliJ plugins):
//
//  1. UTF-8 BOM: Files created by Visual Studio have a BOM (\uFEFF) as the
//     first byte. String(bytes, Charsets.UTF_8) does NOT strip it.
//     Always strip manually before processing, restore after.
//
//  2. CRLF line endings: Windows files use \r\n. The regex flag (?m) makes
//     ^ match after \n but NOT after \r. This silently breaks multiline
//     matching on Windows files. Always normalize \r\n -> \n before regex,
//     then restore original endings in the output.
//
//  3. Indent regex: use ([ \t]*) instead of (\s*) to capture indent —
//     \s* also matches \r\n which pulls the previous line's newline into
//     the replacement and causes formatting corruption.
//
//  4. VirtualFile.path always uses '/' as separator on all platforms —
//     no need to handle backslash in VirtualFile paths.
//     project.basePath may return backslashes on Windows — normalize if comparing.
//
//  5. contentsToByteArray() reads raw bytes from disk. For files open in
//     the editor, prefer FileDocumentManager.getDocument(file) which
//     reflects unsaved changes.
//
//  6. doc.setText() inside WriteCommandAction participates in undo/redo.
//
//  7. ProjectFileIndex.isInContent() returns false in Rider right after a
//     VFileMoveEvent — the index hasn't updated yet. Always add a
//     path.startsWith(basePath) fallback.
// -----------------------------------------------------------------------------

object NamespaceParser {
    // ([ \t]*) captures only spaces/tabs as indent — not \r\n
    private val NS_REGEX = Regex("""(?m)^([ \t]*)namespace\s+([\w.]+)\s*([;{])""")

    private fun normalize(content: String): String {
        val stripped = if (content.startsWith("\uFEFF")) content.substring(1) else content
        return stripped.replace("\r\n", "\n").replace("\r", "\n")
    }

    fun findAll(content: String): List<String> =
        NS_REGEX.findAll(normalize(content)).map { it.groupValues[2] }.toList()

    fun replaceAll(content: String, newNamespace: String): String {
        val hasBom  = content.startsWith("\uFEFF")
        val hasCrlf = content.contains("\r\n")
        var result = NS_REGEX.replace(normalize(content)) { m ->
            val indent     = m.groupValues[1]
            val terminator = m.groupValues[3]
            indent + "namespace " + newNamespace + if (terminator == ";") ";" else " {"
        }
        if (hasCrlf) result = result.replace("\n", "\r\n")
        if (hasBom)  result = "\uFEFF" + result
        return result
    }
}

// -----------------------------------------------------------------------------
//  Namespace calculator — RootNamespace from .csproj + folder path
// -----------------------------------------------------------------------------

object NamespaceCalculator {
    private val LOG = Logger.getInstance(NamespaceCalculator::class.java)

    fun calculate(file: VirtualFile, project: Project): String? {
        val fileDir    = file.parent ?: return null
        val csprojFile = findNearestCsproj(fileDir) ?: return null
        val rootNs     = extractRootNamespace(csprojFile) ?: csprojFile.nameWithoutExtension
        val relative   = buildRelativePath(csprojFile.parent, fileDir)
        return if (relative.isEmpty()) rootNs else "$rootNs.$relative"
    }

    private fun findNearestCsproj(startDir: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = startDir
        while (dir != null) {
            val found = dir.children?.firstOrNull { it.extension?.lowercase() == "csproj" }
            if (found != null) return found
            dir = dir.parent
        }
        return null
    }

    private fun extractRootNamespace(csprojFile: VirtualFile): String? = try {
        val content = String(csprojFile.contentsToByteArray(), Charsets.UTF_8)
        Regex("""<RootNamespace>\s*([\w.]+)\s*</RootNamespace>""")
            .find(content)?.groupValues?.get(1)?.trim()
    } catch (e: Exception) {
        LOG.warn("NamespaceMover: cannot read csproj: " + e.message)
        null
    }

    private fun buildRelativePath(baseDir: VirtualFile, targetDir: VirtualFile): String {
        val base     = baseDir.path.trimEnd('/')
        val target   = targetDir.path.trimEnd('/')
        if (!target.startsWith(base)) return ""
        val relative = target.removePrefix(base).trimStart('/')
        if (relative.isEmpty()) return ""
        return relative.split('/').filter { it.isNotEmpty() }.joinToString(".") { sanitise(it) }
    }

    private fun sanitise(segment: String): String {
        var s = segment.replace(' ', '_').replace('-', '_')
        if (s.isNotEmpty() && s[0].isDigit()) s = "_$s"
        return s
    }
}

// -----------------------------------------------------------------------------
//  Confirmation dialog
// -----------------------------------------------------------------------------

class NamespaceMoverDialog(
    project:              Project,
    private val fileName: String,
    private val oldNs:    String,
    private val newNs:    String,
    private val nsCount:  Int
) : DialogWrapper(project, true) {

    init {
        title = "Update Namespace"
        setOKButtonText("Update")
        setCancelButtonText("Keep Original")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(8, 12, 4, 12)
            preferredSize = Dimension(500, if (nsCount > 1) 190 else 170)
        }

        val headerText = "<html><body>File <b>$fileName</b> was moved to a different folder." +
            (if (nsCount > 1) "<br><b>$nsCount namespace declarations</b> will be updated." else "") +
            "<br></body></html>"
        panel.add(JLabel(headerText), BorderLayout.NORTH)

        val changePanel = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
                JBUI.Borders.empty(10, 12)
            )
        }
        changePanel.add(JLabel("<html><b>Namespace change:</b></html>"), BorderLayout.NORTH)
        changePanel.add(JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(JLabel("<html><tt><font color='#CC3333'>- $oldNs</font></tt></html>"), BorderLayout.NORTH)
            add(JLabel("<html><tt><font color='#33AA33'>+ $newNs</font></tt></html>"), BorderLayout.SOUTH)
        }, BorderLayout.CENTER)

        panel.add(changePanel, BorderLayout.CENTER)
        panel.add(
            JLabel("<html><font color='gray'><small>Namespace calculated from RootNamespace in .csproj + folder path.</small></font></html>"),
            BorderLayout.SOUTH
        )
        return panel
    }

    fun showAndGetResult(): Boolean = showAndGet()
}
