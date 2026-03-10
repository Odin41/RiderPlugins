package com.github.unusedmethods

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Shows an orange badge in the gutter of the FIRST line of a .cs file
 * when unused methods were found in it after the last analysis.
 * Clicking the badge navigates to the first unused method in that file.
 */
class UnusedMethodsGutterProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only mark the first element of a file (avoid per-line spam)
        val file = element.containingFile ?: return null
        if (element != file.firstChild) return null
        if (file.virtualFile?.extension?.lowercase() != "cs") return null

        val project: Project = element.project
        val service = UnusedMethodsService.getInstance(project)
        val filePath = file.virtualFile?.path ?: return null
        val count = service.countForFile(filePath)
        if (count == 0) return null

        val icon = BadgeIcon(count)

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { _: PsiElement -> "$count unused method${if (count == 1) "" else "s"} in this file" },
            { _, elt ->
                // Click → navigate to first unused method in file
                val first = service.getForFile(filePath).firstOrNull() ?: return@LineMarkerInfo
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(first.filePath) ?: return@LineMarkerInfo
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, first.lineNumber - 1, 0)
                    .navigate(true)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "$count unused methods" }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Dynamically-drawn badge icon (orange circle with white number)
//  Matches the style of the error/warning count badges in the editor
// ─────────────────────────────────────────────────────────────────────────────
class BadgeIcon(private val count: Int) : Icon {

    private val size = 16

    override fun getIconWidth()  = size
    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Badge background
        g2.color = Color(0xF4B942)   // amber — same as warning
        g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(),
            size.toFloat(), size.toFloat(), size.toFloat(), size.toFloat()))

        // Text
        val text = if (count > 99) "99+" else count.toString()
        g2.color = Color.WHITE
        g2.font  = Font(Font.SANS_SERIF, Font.BOLD, if (count > 9) 8 else 9)
        val fm   = g2.fontMetrics
        val tx   = x + (size - fm.stringWidth(text)) / 2
        val ty   = y + (size + fm.ascent - fm.descent) / 2
        g2.drawString(text, tx, ty)

        g2.dispose()
    }
}
