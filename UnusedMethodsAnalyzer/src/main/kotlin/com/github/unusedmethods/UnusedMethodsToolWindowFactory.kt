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
    private val filterField = SearchTextField()   // symbol name
    private val classFilter = SearchTextField()   // class name
    private val fileFilter  = SearchTextField()   // file name
    private val kindFilter  = JComboBox(arrayOf("All", "Method", "Property", "Class", "Interface", "Enum"))
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
        text = "Select a symbol to see its details"
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
            "Navigate to selected symbol (Enter / double-click)") { navigateToSelected() }
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
        filterField.apply { preferredSize = Dimension(160, 26); toolTipText = "Filter by name" }
        classFilter.apply { preferredSize = Dimension(130, 26); toolTipText = "Filter by class name" }
        fileFilter.apply  { preferredSize = Dimension(130, 26); toolTipText = "Filter by file name"  }
        kindFilter.apply  { preferredSize = Dimension(100, 26); toolTipText = "Filter by symbol type" }

        val filterAdapter = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { applyFilters() }
        }
        filterField.addDocumentListener(filterAdapter)
        classFilter.addDocumentListener(filterAdapter)
        fileFilter.addDocumentListener(filterAdapter)
        kindFilter.addActionListener { applyFilters() }

        val clearBtn = JButton("✕").apply {
            toolTipText = "Clear all filters"; isFocusable = false
            preferredSize = Dimension(26, 26)
            addActionListener {
                filterField.text = ""; classFilter.text = ""; fileFilter.text = ""
                kindFilter.selectedIndex = 0
            }
        }
        filterRow.add(lbl("Name:"));  filterRow.add(filterField)
        filterRow.add(lbl("Class:")); filterRow.add(classFilter)
        filterRow.add(lbl("File:"));  filterRow.add(fileFilter)
        filterRow.add(lbl("Type:"));  filterRow.add(kindFilter)
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

            rowSorter = sorter
            sorter.setComparator(4, compareBy<Any> { (it as? Int) ?: Int.MAX_VALUE })
            sorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
            tableHeader.defaultRenderer = SortableHeaderRenderer(tableHeader.defaultRenderer)

            setDefaultRenderer(String::class.java, SymbolCellRenderer())
            setDefaultRenderer(Int::class.java,    SymbolCellRenderer())
            setDefaultRenderer(Any::class.java,    SymbolCellRenderer())

            // Col: Kind, Name, Class, File, Line, Access
            columnModel.getColumn(0).preferredWidth = 80
            columnModel.getColumn(1).preferredWidth = 170
            columnModel.getColumn(2).preferredWidth = 130
            columnModel.getColumn(3).preferredWidth = 170
            columnModel.getColumn(4).preferredWidth = 50
            columnModel.getColumn(5).preferredWidth = 65

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
        val m = selectedSymbol() ?: return

        JPopupMenu().apply {
            add(JMenuItem("Go To   ${m.fileName}:${m.lineNumber}", AllIcons.Actions.Find)
                .also { it.addActionListener { navigateToSelected() } })
            addSeparator()
            if (m.kind == SymbolKind.METHOD) {
                add(JMenuItem("Mark [Obsolete]", AllIcons.Actions.Annotate)
                    .also { it.addActionListener { markSelectedObsolete() } })
                addSeparator()
            }
            add(JMenuItem("Copy Name")
                .also { it.addActionListener { copyToClipboard(m.name) } })
            add(JMenuItem("Copy Display Name")
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
            if (dirOverride == null) "Analyzing project for unused symbols…"
            else "Analyzing '${dirOverride.name}' for unused symbols…",
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
        val kindQ  = kindFilter.selectedItem?.toString() ?: "All"

        val filtered = allResults.filter { m ->
            (nameQ.isBlank()  || m.name.contains(nameQ,      ignoreCase = true)) &&
            (classQ.isBlank() || m.className.contains(classQ, ignoreCase = true)) &&
            (fileQ.isBlank()  || m.fileName.contains(fileQ,   ignoreCase = true)) &&
            (kindQ == "All"   || m.kind.label == kindQ)
        }
        tableModel.setData(filtered)
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun updateStatus(state: UnusedMethodsService.AnalysisState) {
        val total   = state.results.size
        val methods = state.results.count { it.kind == SymbolKind.METHOD }
        val props   = state.results.count { it.kind == SymbolKind.PROPERTY }
        val classes = state.results.count { it.kind == SymbolKind.CLASS }
        val ifaces  = state.results.count { it.kind == SymbolKind.INTERFACE }
        val enums   = state.results.count { it.kind == SymbolKind.ENUM }

        statusLabel.text = when (total) {
            0    -> "✓ No unused symbols found"
            1    -> "1 unused symbol"
            else -> buildString {
                append("$total unused:")
                if (methods > 0) append(" $methods methods")
                if (props   > 0) append(", $props props")
                if (classes > 0) append(", $classes classes")
                if (ifaces  > 0) append(", $ifaces interfaces")
                if (enums   > 0) append(", $enums enums")
            }
        }
        scopeLabel.text = buildString {
            append("Scope: ${state.scope}")
            if (state.scannedFiles   > 0) append(" · ${state.scannedFiles} files")
            if (state.scannedMethods > 0) append(" · ${state.scannedMethods} scanned")
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToSelected() {
        val m  = selectedSymbol() ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(m.filePath) ?: return
        OpenFileDescriptor(project, vf, m.lineNumber - 1, 0).navigate(true)
    }

    private fun updateDetailPanel() {
        val m = selectedSymbol()
        detailArea.text = m?.let {
            buildString {
                appendLine("Kind    :  ${it.kind.label}")
                appendLine("Name    :  ${it.name}")
                if (it.className.isNotEmpty()) appendLine("Class   :  ${it.className}")
                appendLine("File    :  ${it.fileName}")
                appendLine("Line    :  ${it.lineNumber}")
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
        } ?: "Select a symbol to see its details"
        detailArea.caretPosition = 0
    }

    // ── Mark obsolete (methods only) ──────────────────────────────────────────

    private fun markSelectedObsolete() {
        val m = selectedSymbol() ?: run {
            Messages.showInfoMessage(project, "Select a method first.", "Mark [Obsolete]")
            return
        }
        if (m.kind != SymbolKind.METHOD) {
            Messages.showInfoMessage(project, "[Obsolete] can only be applied to methods.", "Mark [Obsolete]")
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
                if (choice == 0) "unused_symbols.csv" else "unused_symbols.txt"
            )
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        try {
            if (choice == 0) exportCsv(chooser.selectedFile)
            else             exportTxt(chooser.selectedFile)
            notify("Exported ${allResults.size} symbols to ${chooser.selectedFile.name}",
                NotificationType.INFORMATION)
        } catch (ex: Exception) {
            notify("Export failed: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun exportCsv(file: File) {
        file.bufferedWriter().use { w ->
            w.write("Kind,Name,Class,File,Line,Access,Signature\n")
            allResults.forEach { m ->
                val access = when { m.isPrivate -> "private"; m.isStatic -> "static"
                                    m.isOverride -> "override"; else -> "public" }
                fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
                w.write("${m.kind.label},${esc(m.name)},${esc(m.className)},${esc(m.fileName)}," +
                        "${m.lineNumber},$access,${esc(m.signature)}\n")
            }
        }
    }

    private fun exportTxt(file: File) {
        file.bufferedWriter().use { w ->
            w.write("Unused Symbols Report\n")
            w.write("Generated: ${java.time.LocalDateTime.now()}\n")
            w.write("Total: ${allResults.size} unused symbols\n")
            w.write("=".repeat(72) + "\n\n")

            SymbolKind.values().forEach { kind ->
                val group = allResults.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                w.write("━━━ ${kind.label}s (${group.size}) ━━━\n\n")
                group.groupBy { it.className.ifEmpty { it.fileName } }.toSortedMap().forEach { (cls, items) ->
                    if (cls.isNotEmpty()) w.write("▸ $cls\n")
                    items.sortedBy { it.name }.forEach { m ->
                        w.write("    ${m.name}  (${m.fileName}:${m.lineNumber})\n")
                        w.write("    ${m.signature}\n\n")
                    }
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

    private fun selectedSymbol(): MethodInfo? {
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
//  Table model  — columns: Kind | Name | Class | File | Line | Access
// ─────────────────────────────────────────────────────────────────────────────
class ResultsTableModel : AbstractTableModel() {
    private val COLS = arrayOf("Kind", "Name", "Class", "File", "Line", "Access")
    private var rows: List<MethodInfo> = emptyList()

    fun setData(list: List<MethodInfo>) { rows = list; fireTableDataChanged() }
    fun getAt(row: Int): MethodInfo?    = rows.getOrNull(row)

    override fun getRowCount()                  = rows.size
    override fun getColumnCount()               = COLS.size
    override fun getColumnName(col: Int)        = COLS[col]
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        4    -> Int::class.java
        else -> String::class.java
    }

    override fun getValueAt(row: Int, col: Int): Any = rows[row].run {
        when (col) {
            0 -> kind.label
            1 -> name
            2 -> className
            3 -> fileName
            4 -> lineNumber
            5 -> when { isPrivate -> "private"; isStatic -> "static"
                         isOverride -> "override"; else -> "public" }
            else -> ""
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cell renderer
// ─────────────────────────────────────────────────────────────────────────────
class SymbolCellRenderer : DefaultTableCellRenderer() {
    private val altBg         = UIUtil.getDecoratedRowColor()
    private val boldFont      = UIManager.getFont("Label.font")?.deriveFont(Font.BOLD)
    private val monoFont      = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val privateColor  = JBColor(Color(130,  90, 180), Color(180, 140, 220))
    private val staticColor   = JBColor(Color(  0, 120, 180), Color(100, 180, 230))
    private val overrideColor = JBColor(Color( 90, 150,  90), Color(130, 190, 130))
    // Kind badge colors
    private val kindMethodColor    = JBColor(Color( 60, 130, 200), Color( 80, 160, 230))
    private val kindPropertyColor  = JBColor(Color( 90, 165,  90), Color(110, 190, 110))
    private val kindClassColor     = JBColor(Color(200, 130,  40), Color(230, 160,  60))
    private val kindInterfaceColor = JBColor(Color(170,  60, 170), Color(200,  90, 200))
    private val kindEnumColor      = JBColor(Color(150,  90,  40), Color(190, 130,  70))

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, col: Int
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)

        border = JBUI.Borders.empty(0, 6)

        background = when {
            isSelected   -> table.selectionBackground
            row % 2 == 0 -> table.background
            else         -> altBg
        }

        foreground = if (isSelected) table.selectionForeground else table.foreground

        font = when (col) { 1 -> boldFont ?: table.font; 4 -> monoFont; else -> table.font }

        if (!isSelected) when (col) {
            0 -> foreground = when (value?.toString()) {
                "Method"    -> kindMethodColor
                "Property"  -> kindPropertyColor
                "Class"     -> kindClassColor
                "Interface" -> kindInterfaceColor
                "Enum"      -> kindEnumColor
                else        -> table.foreground
            }
            4 -> foreground = UIUtil.getLabelDisabledForeground()
            5 -> foreground = when (value?.toString()) {
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
    val obsoleteField  = JTextField(s.obsoleteText, 36)
    val excludedField  = JTextField(s.excludedNames, 36)
    val privateBox     = JCheckBox("Exclude private methods",    s.excludePrivate)
    val overrideBox    = JCheckBox("Exclude override methods",   s.excludeOverrides)
    val eventBox       = JCheckBox("Exclude event handlers",     s.excludeEventHandlers)
    val testBox        = JCheckBox("Exclude test methods",       s.excludeTests)
    val propsBox       = JCheckBox("Analyze properties",         s.analyzeProperties)
    val classesBox     = JCheckBox("Analyze classes",            s.analyzeClasses)
    val interfacesBox  = JCheckBox("Analyze interfaces",         s.analyzeInterfaces)
    val enumsBox       = JCheckBox("Analyze enums",              s.analyzeEnums)

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
        fun sep(y: Int, title: String) {
            g.gridx = 0; g.gridy = y; g.gridwidth = 2; g.weightx = 1.0
            panel.add(JBLabel(title).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.emptyTop(6)
            }, g)
            g.gridwidth = 1
        }
        row(0, "[Obsolete] text:", obsoleteField)
        row(1, "Excluded names (comma-separated):", excludedField)
        sep(2, "— Methods ——————————————————————————")
        span(3, privateBox); span(4, overrideBox); span(5, eventBox); span(6, testBox)
        sep(7,  "— Symbol types to analyze ——————————")
        span(8,  propsBox); span(9, classesBox); span(10, interfacesBox); span(11, enumsBox)
        return panel
    }

    fun applyTo(s: UnusedMethodsSettings) {
        s.obsoleteText         = obsoleteField.text.trim().ifEmpty { "Не используется в проекте" }
        s.excludedNames        = excludedField.text.trim()
        s.excludePrivate       = privateBox.isSelected
        s.excludeOverrides     = overrideBox.isSelected
        s.excludeEventHandlers = eventBox.isSelected
        s.excludeTests         = testBox.isSelected
        s.analyzeProperties    = propsBox.isSelected
        s.analyzeClasses       = classesBox.isSelected
        s.analyzeInterfaces    = interfacesBox.isSelected
        s.analyzeEnums         = enumsBox.isSelected
    }
}
