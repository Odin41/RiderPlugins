package com.github.unusedmethods

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.*
import javax.swing.RowSorter
import javax.swing.SortOrder

class UnusedMethodsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = UnusedMethodsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class UnusedMethodsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service     = UnusedMethodsService.getInstance(project)
    private val tableModel  = ResultsTableModel()
    private val table       = JBTable(tableModel)
    private val sorter      = TableRowSorter(tableModel)
    private val filterField = SearchTextField()   // method name
    private val classFilter = SearchTextField()   // class name
    private val fileFilter  = SearchTextField()   // file name
    private val statusLabel = JBLabel("Press 'Analyze Project' or use Ctrl+Alt+Shift+U")
    private val scopeLabel  = JBLabel("")
    private val progressBar = JProgressBar().apply {
        isVisible = false; isIndeterminate = true
        preferredSize = Dimension(120, 14)
    }
    private val detailArea = JTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        background = UIUtil.getPanelBackground()
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(8)
        text = "Select a method to see its signature"
    }

    private var allResults: List<MethodInfo> = emptyList()

    init {
        setupTable()
        buildUI()
        loadSavedResults()

        service.addListener { state ->
            allResults = state.results
            applyFilters()
            updateStatus(state)
            progressBar.isVisible = false
            revalidate(); repaint()
        }
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private fun buildUI() {
        background = UIUtil.getPanelBackground()

        val tableScroll  = JBScrollPane(table)
        val detailScroll = JBScrollPane(detailArea).apply {
            preferredSize = Dimension(270, 0); minimumSize = Dimension(180, 0)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailScroll).apply {
            resizeWeight = 0.75; isContinuousLayout = true; border = null
        }

        val statusBar = JPanel(BorderLayout(6, 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(3, 6)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false; add(progressBar); add(statusLabel)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                scopeLabel.foreground = UIUtil.getLabelDisabledForeground()
                add(scopeLabel)
            }
            add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
        }

        add(buildToolbar(), BorderLayout.NORTH)
        add(split,          BorderLayout.CENTER)
        add(statusBar,      BorderLayout.SOUTH)
    }

    private fun buildToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        }

        // ── Row 1: action buttons ─────────────────────────────────────────────
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 4, 0, 4)
        }
        val analyzeBtn  = btn("Analyze Project", AllIcons.Actions.Execute,
            "Scan entire project (Ctrl+Alt+Shift+U)") { runAnalysis(null) }
        val obsoleteBtn = btn("Mark [Obsolete]", AllIcons.Actions.Annotate,
            "Insert [Obsolete] above selected method") { markSelectedObsolete() }
        val goBtn       = btn("Go To", AllIcons.Actions.Find,
            "Navigate to selected method (Enter / double-click)") { navigateToSelected() }
        val exportBtn   = btn("Export", AllIcons.ToolbarDecorator.Export,
            "Export results to CSV or TXT") { showExportDialog() }
        val settingsBtn = JButton(AllIcons.General.Settings).apply {
            toolTipText = "Settings"; isFocusable = false
            addActionListener { showSettingsDialog() }
        }
        actionRow.add(analyzeBtn); actionRow.add(sep())
        actionRow.add(obsoleteBtn); actionRow.add(goBtn); actionRow.add(sep())
        actionRow.add(exportBtn); actionRow.add(Box.createHorizontalGlue())
        actionRow.add(settingsBtn)

        // ── Row 2: filter fields ──────────────────────────────────────────────
        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false; border = JBUI.Borders.empty(0, 4, 2, 4)
        }
        fun lbl(t: String) = JBLabel(t).apply {
            foreground = UIUtil.getLabelDisabledForeground()
        }
        filterField.apply { preferredSize = Dimension(180, 26); toolTipText = "Filter by method name" }
        classFilter.apply { preferredSize = Dimension(150, 26); toolTipText = "Filter by class name"  }
        fileFilter.apply  { preferredSize = Dimension(150, 26); toolTipText = "Filter by file name"   }

        val filterAdapter = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { applyFilters() }
        }
        filterField.addDocumentListener(filterAdapter)
        classFilter.addDocumentListener(filterAdapter)
        fileFilter.addDocumentListener(filterAdapter)

        val clearBtn = JButton("✕").apply {
            toolTipText = "Clear all filters"; isFocusable = false
            preferredSize = Dimension(26, 26)
            addActionListener { filterField.text = ""; classFilter.text = ""; fileFilter.text = "" }
        }
        filterRow.add(lbl("Method:")); filterRow.add(filterField)
        filterRow.add(lbl("Class:"));  filterRow.add(classFilter)
        filterRow.add(lbl("File:"));   filterRow.add(fileFilter)
        filterRow.add(clearBtn)

        toolbar.add(actionRow, BorderLayout.NORTH)
        toolbar.add(filterRow, BorderLayout.SOUTH)
        return toolbar
    }

    private fun btn(text: String, icon: Icon, tip: String, action: () -> Unit) =
        JButton(text, icon).apply {
            toolTipText = tip; isFocusable = false
            addActionListener { action() }
        }

    private fun sep() =
        JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 22) }

    // ── Table ─────────────────────────────────────────────────────────────────

    private fun setupTable() {
        table.apply {
            rowHeight = 26
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            tableHeader.reorderingAllowed = false

            // Sorting
            rowSorter = sorter
            sorter.setComparator(3, compareBy<Any> { (it as? Int) ?: Int.MAX_VALUE })
            sorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
            tableHeader.defaultRenderer = SortableHeaderRenderer(tableHeader.defaultRenderer)

            // Register renderer for ALL column types to prevent colour bleed
            setDefaultRenderer(String::class.java, MethodCellRenderer())
            setDefaultRenderer(Int::class.java,    MethodCellRenderer())
            setDefaultRenderer(Any::class.java,    MethodCellRenderer())

            columnModel.getColumn(0).preferredWidth = 180
            columnModel.getColumn(1).preferredWidth = 140
            columnModel.getColumn(2).preferredWidth = 180
            columnModel.getColumn(3).preferredWidth = 55
            columnModel.getColumn(4).preferredWidth = 70

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e))
                        navigateToSelected()
                }
                override fun mousePressed(e: MouseEvent)  { maybeShowContextMenu(e) }
                override fun mouseReleased(e: MouseEvent) { maybeShowContextMenu(e) }
            })

            getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "go")
            actionMap.put("go", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) { navigateToSelected() }
            })

            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) updateDetailPanel()
            }
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row >= 0) table.setRowSelectionInterval(row, row)
        val m = selectedMethod() ?: return

        JPopupMenu().apply {
            add(JMenuItem("Go To   ${m.fileName}:${m.lineNumber}", AllIcons.Actions.Find)
                .also { it.addActionListener { navigateToSelected() } })
            addSeparator()
            add(JMenuItem("Mark [Obsolete]", AllIcons.Actions.Annotate)
                .also { it.addActionListener { markSelectedObsolete() } })
            addSeparator()
            add(JMenuItem("Copy Method Name")
                .also { it.addActionListener { copyToClipboard(m.name) } })
            add(JMenuItem("Copy Class.Method")
                .also { it.addActionListener { copyToClipboard(m.displayName) } })
            add(JMenuItem("Copy Full Signature")
                .also { it.addActionListener { copyToClipboard(m.signature) } })
        }.show(table, e.x, e.y)
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    fun runAnalysis(dirOverride: com.intellij.openapi.vfs.VirtualFile?) {
        progressBar.isIndeterminate = true
        progressBar.isVisible = true
        val scope = dirOverride?.name ?: "project"
        statusLabel.text = "⏳ Scanning $scope…"
        scopeLabel.text  = "Scope: $scope"
        tableModel.setData(emptyList())
        detailArea.text = ""
        allResults = emptyList()
        revalidate(); repaint()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            if (dirOverride == null) "Analyzing project for unused methods…"
            else "Analyzing '${dirOverride.name}' for unused methods…",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = if (dirOverride == null)
                    CSharpAnalyzer.analyze(project, indicator)
                else
                    CSharpAnalyzer.analyzeDirectory(project, dirOverride, indicator)

                ApplicationManager.getApplication().invokeLater {
                    progressBar.isVisible = false
                    if (result.debugInfo.isNotEmpty()) {
                        detailArea.text = result.debugInfo
                        detailArea.caretPosition = 0
                    }
                    UnusedMethodsService.getInstance(project)
                        .setResults(result, dirOverride?.name ?: "project")
                }
            }
        })
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private fun applyFilters() {
        val nameQ  = filterField.text.trim()
        val classQ = classFilter.text.trim()
        val fileQ  = fileFilter.text.trim()

        val filtered = allResults.filter { m ->
            (nameQ.isBlank()  || m.name.contains(nameQ,  ignoreCase = true)) &&
            (classQ.isBlank() || m.className.contains(classQ, ignoreCase = true)) &&
            (fileQ.isBlank()  || m.fileName.contains(fileQ,  ignoreCase = true))
        }
        tableModel.setData(filtered)
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun updateStatus(state: UnusedMethodsService.AnalysisState) {
        val count = state.results.size
        statusLabel.text = when (count) {
            0    -> "✓ No unused methods found"
            1    -> "1 unused method"
            else -> "$count unused methods"
        }
        scopeLabel.text = buildString {
            append("Scope: ${state.scope}")
            if (state.scannedFiles   > 0) append(" · ${state.scannedFiles} files")
            if (state.scannedMethods > 0) append(" · ${state.scannedMethods} methods scanned")
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToSelected() {
        val m  = selectedMethod() ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(m.filePath) ?: return
        OpenFileDescriptor(project, vf, m.lineNumber - 1, 0).navigate(true)
    }

    private fun updateDetailPanel() {
        val m = selectedMethod()
        detailArea.text = m?.let {
            buildString {
                appendLine("Method :  ${it.name}")
                appendLine("Class  :  ${it.className}")
                appendLine("File   :  ${it.fileName}")
                appendLine("Line   :  ${it.lineNumber}")
                appendLine()
                appendLine("Signature:")
                appendLine(it.signature)
                val flags = listOfNotNull(
                    "private".takeIf  { _ -> it.isPrivate  },
                    "static".takeIf   { _ -> it.isStatic   },
                    "override".takeIf { _ -> it.isOverride }
                )
                if (flags.isNotEmpty()) { appendLine(); append("Flags: ${flags.joinToString(", ")}") }
            }
        } ?: "Select a method to see its signature"
        detailArea.caretPosition = 0
    }

    // ── Mark obsolete ─────────────────────────────────────────────────────────

    private fun markSelectedObsolete() {
        val m = selectedMethod() ?: run {
            Messages.showInfoMessage(project, "Select a method first.", "Mark [Obsolete]")
            return
        }
        val settings = UnusedMethodsSettings.getInstance()
        val msg = Messages.showInputDialog(
            project, "Obsolete message text:", "Mark as [Obsolete]",
            Messages.getQuestionIcon(), settings.obsoleteText, null
        ) ?: return

        val vf = LocalFileSystem.getInstance().findFileByPath(m.filePath) ?: run {
            notify("File not found: ${m.filePath}", NotificationType.ERROR); return
        }

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
            project, "Mark as [Obsolete]", null, {
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                    ?: return@runWriteCommandAction
                val start  = doc.getLineStartOffset(m.lineNumber - 1)
                val indent = doc.text.substring(start).takeWhile { it == ' ' || it == '\t' }
                doc.insertString(start, "$indent[Obsolete(\"$msg\")]\n")
                FileDocumentManager.getInstance().saveDocument(doc)

                val updated = allResults.filter { it != m }
                UnusedMethodsService.getInstance(project)
                    .setResults(CSharpAnalyzer.AnalysisResult(updated, "Updated"), service.getRuntimeState().scope)
                notify("Marked ${m.displayName} as [Obsolete]", NotificationType.INFORMATION)
            }
        )
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun showExportDialog() {
        if (allResults.isEmpty()) {
            Messages.showInfoMessage(project, "No results to export. Run analysis first.", "Export")
            return
        }
        val options = arrayOf("CSV (comma-separated)", "TXT (grouped report)")
        val choice = Messages.showChooseDialog(
            project, "Choose export format:", "Export Results",
            AllIcons.ToolbarDecorator.Export, options, options[0]
        )
        if (choice < 0) return

        val chooser = JFileChooser().apply {
            dialogTitle = "Save export file"
            selectedFile = File(
                project.basePath ?: System.getProperty("user.home"),
                if (choice == 0) "unused_methods.csv" else "unused_methods.txt"
            )
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        try {
            if (choice == 0) exportCsv(chooser.selectedFile)
            else             exportTxt(chooser.selectedFile)
            notify("Exported ${allResults.size} methods to ${chooser.selectedFile.name}",
                NotificationType.INFORMATION)
        } catch (ex: Exception) {
            notify("Export failed: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun exportCsv(file: File) {
        file.bufferedWriter().use { w ->
            w.write("Method,Class,File,Line,Access,Signature\n")
            allResults.forEach { m ->
                val access = when { m.isPrivate -> "private"; m.isStatic -> "static"
                                    m.isOverride -> "override"; else -> "public" }
                fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
                w.write("${esc(m.name)},${esc(m.className)},${esc(m.fileName)}," +
                        "${m.lineNumber},$access,${esc(m.signature)}\n")
            }
        }
    }

    private fun exportTxt(file: File) {
        file.bufferedWriter().use { w ->
            w.write("Unused Methods Report\n")
            w.write("Generated: ${java.time.LocalDateTime.now()}\n")
            w.write("Total: ${allResults.size} unused methods\n")
            w.write("=".repeat(72) + "\n\n")
            allResults.groupBy { it.className }.toSortedMap().forEach { (cls, methods) ->
                w.write("▸ $cls\n")
                methods.sortedBy { it.name }.forEach { m ->
                    w.write("    ${m.name}  (${m.fileName}:${m.lineNumber})\n")
                    w.write("    ${m.signature}\n\n")
                }
            }
        }
    }

    // ── Persist / restore ─────────────────────────────────────────────────────

    private fun loadSavedResults() {
        val state = service.getRuntimeState()
        if (state.results.isNotEmpty()) {
            allResults = state.results
            applyFilters()
            updateStatus(state)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun selectedMethod(): MethodInfo? {
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return null
        return tableModel.getAt(table.convertRowIndexToModel(viewRow))
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notify("Copied to clipboard", NotificationType.INFORMATION)
    }

    private fun notify(msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Unused Methods Analyzer")
            .createNotification(msg, type)
            .notify(project)
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog(project)
        if (dialog.showAndGet()) dialog.applyTo(UnusedMethodsSettings.getInstance())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Table model
// ─────────────────────────────────────────────────────────────────────────────
class ResultsTableModel : AbstractTableModel() {
    private val COLS = arrayOf("Method", "Class", "File", "Line", "Access")
    private var rows: List<MethodInfo> = emptyList()

    fun setData(list: List<MethodInfo>) { rows = list; fireTableDataChanged() }
    fun getAt(row: Int): MethodInfo?    = rows.getOrNull(row)

    override fun getRowCount()                  = rows.size
    override fun getColumnCount()               = COLS.size
    override fun getColumnName(col: Int)        = COLS[col]
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        3    -> Int::class.java
        else -> String::class.java
    }

    override fun getValueAt(row: Int, col: Int): Any = rows[row].run {
        when (col) {
            0 -> name; 1 -> className; 2 -> fileName; 3 -> lineNumber
            4 -> when { isPrivate -> "private"; isStatic -> "static"
                        isOverride -> "override"; else -> "public" }
            else -> ""
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cell renderer  — colour reset on every cell prevents bleed between columns
// ─────────────────────────────────────────────────────────────────────────────
class MethodCellRenderer : DefaultTableCellRenderer() {
    private val altBg         = UIUtil.getDecoratedRowColor()
    private val boldFont      = UIManager.getFont("Label.font")?.deriveFont(Font.BOLD)
    private val monoFont      = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val privateColor  = JBColor(Color(130,  90, 180), Color(180, 140, 220))
    private val staticColor   = JBColor(Color(  0, 120, 180), Color(100, 180, 230))
    private val overrideColor = JBColor(Color( 90, 150,  90), Color(130, 190, 130))

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, col: Int
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)

        border = JBUI.Borders.empty(0, 6)

        // Background — always reset to prevent zebra bleed on sort/filter
        background = when {
            isSelected   -> table.selectionBackground
            row % 2 == 0 -> table.background
            else         -> altBg
        }

        // Foreground — reset to default first, then apply per-column overrides
        foreground = if (isSelected) table.selectionForeground else table.foreground

        font = when (col) { 0 -> boldFont ?: table.font; 3 -> monoFont; else -> table.font }

        if (!isSelected) when (col) {
            3 -> foreground = UIUtil.getLabelDisabledForeground()
            4 -> foreground = when (value?.toString()) {
                "private"  -> privateColor
                "static"   -> staticColor
                "override" -> overrideColor
                else       -> table.foreground
            }
        }

        return this
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sortable header renderer
// ─────────────────────────────────────────────────────────────────────────────
class SortableHeaderRenderer(private val delegate: TableCellRenderer?) : TableCellRenderer {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, col: Int
    ): Component {
        val c = delegate?.getTableCellRendererComponent(
            table, value, isSelected, hasFocus, row, col
        ) ?: JLabel(value?.toString() ?: "")
        if (c is JLabel) {
            c.text = when (table.rowSorter?.sortKeys?.firstOrNull { it.column == col }?.sortOrder) {
                SortOrder.ASCENDING  -> "${value} ▲"
                SortOrder.DESCENDING -> "${value} ▼"
                else                 -> value?.toString() ?: ""
            }
        }
        return c
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Settings dialog
// ─────────────────────────────────────────────────────────────────────────────
class SettingsDialog(project: Project) : com.intellij.openapi.ui.DialogWrapper(project, true) {

    private val s = UnusedMethodsSettings.getInstance()
    val obsoleteField = JTextField(s.obsoleteText, 36)
    val excludedField = JTextField(s.excludedNames, 36)
    val privateBox    = JCheckBox("Exclude private methods",    s.excludePrivate)
    val overrideBox   = JCheckBox("Exclude override methods",   s.excludeOverrides)
    val eventBox      = JCheckBox("Exclude event handlers",     s.excludeEventHandlers)
    val testBox       = JCheckBox("Exclude test methods",       s.excludeTests)

    init { title = "Unused Methods Analyzer — Settings"; init() }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val g = GridBagConstraints().apply {
            insets = JBUI.insets(4, 4); fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        fun row(y: Int, label: String, comp: JComponent) {
            g.gridx = 0; g.gridy = y; g.weightx = 0.0; panel.add(JBLabel(label), g)
            g.gridx = 1; g.weightx = 1.0;              panel.add(comp, g)
        }
        fun span(y: Int, comp: JComponent) {
            g.gridx = 0; g.gridy = y; g.gridwidth = 2; g.weightx = 1.0; panel.add(comp, g)
            g.gridwidth = 1
        }
        row(0, "[Obsolete] text:", obsoleteField)
        row(1, "Excluded names (comma-separated):", excludedField)
        span(2, privateBox); span(3, overrideBox); span(4, eventBox); span(5, testBox)
        return panel
    }

    fun applyTo(s: UnusedMethodsSettings) {
        s.obsoleteText         = obsoleteField.text.trim().ifEmpty { "Не используется в проекте" }
        s.excludedNames        = excludedField.text.trim()
        s.excludePrivate       = privateBox.isSelected
        s.excludeOverrides     = overrideBox.isSelected
        s.excludeEventHandlers = eventBox.isSelected
        s.excludeTests         = testBox.isSelected
    }
}
