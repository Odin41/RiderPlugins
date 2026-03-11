# Namespace Mover

JetBrains Rider plugin that automatically updates C# namespaces when `.cs` files are moved between folders.

## How it works

1. You drag & drop a `.cs` file to a new folder in the Rider project tree
2. The plugin detects the move via `BulkFileListener`
3. It calculates the correct new namespace from the nearest `.csproj` (`<RootNamespace>`) + relative folder path
4. A confirmation dialog shows the old → new namespace diff
5. On confirmation, all namespace declarations in the file are updated in-place

## Features

- Supports both **file-scoped** (`namespace Foo.Bar;`) and **block-scoped** (`namespace Foo.Bar { }`) namespaces
- Updates **all namespace declarations** in a file if there are multiple
- Preserves original **line endings** (CRLF) and **UTF-8 BOM** (Visual Studio default)
- Sanitises folder names to valid C# identifiers (spaces/hyphens → underscores, leading digits → prefixed with `_`)

---

## File handling notes for plugin developers

These issues were encountered during development and apply to any IntelliJ/Rider plugin that reads or modifies C# source files.

### 1. UTF-8 BOM

Visual Studio creates `.cs` files with a UTF-8 BOM (`\uFEFF`, bytes `EF BB BF`) by default.

**Problem:** `String(file.contentsToByteArray(), Charsets.UTF_8)` does **not** strip the BOM.
The resulting string starts with `\uFEFF`, so `^` in multiline regex won't match the very first line.

**Fix:**
```kotlin
val stripped = if (content.startsWith("\uFEFF")) content.substring(1) else content
```
Or use `Charsets.UTF_8` with explicit BOM detection. When writing back, restore the BOM if it was present.

---

### 2. CRLF line endings

Windows files use `\r\n`. Java/Kotlin regex flag `(?m)` makes `^` match after `\n` only — **not** after `\r`.

**Problem:** On a CRLF file, `(?m)^(\s*)namespace` captures `\r\n` into the indent group `\s*`,
which causes the replacement to inject an extra blank line and break indentation.

**Fix:** Normalize line endings before matching, restore after:
```kotlin
fun normalize(content: String) = content.replace("\r\n", "\n").replace("\r", "\n")

// In regex pattern, use [ \t]* for indent instead of \s* to never capture newlines:
val NS_REGEX = Regex("""(?m)^([ \t]*)namespace\s+([\w.]+)\s*([;{])""")
```

When writing back:
```kotlin
val hasCrlf = content.contains("\r\n")
if (hasCrlf) result = result.replace("\n", "\r\n")
```

---

### 3. VirtualFile.path always uses forward slashes

`VirtualFile.path` returns `/`-separated paths on **all platforms**, including Windows.
No need to call `.replace('\\', '/')` on VirtualFile paths.

`project.basePath` however can return backslashes on Windows — normalize it if comparing:
```kotlin
val normalizedBase = basePath.replace('\\', '/')
```

---

### 4. Reading file content — bytes vs Document

Two ways to read a file:

| Method | When to use |
|--------|-------------|
| `file.contentsToByteArray()` | File may not be open in editor; reads from disk |
| `FileDocumentManager.getInstance().getDocument(file)?.text` | File is open in editor; reflects unsaved changes |

Prefer the Document approach when available — it also integrates with undo/redo.

---

### 5. Writing file content — Document vs setBinaryContent

```kotlin
val doc = FileDocumentManager.getInstance().getDocument(file)
if (doc != null) {
    // Correct: participates in undo/redo, triggers editor refresh
    WriteCommandAction.runWriteCommandAction(project, "Description", null, {
        doc.setText(newContent)
        FileDocumentManager.getInstance().saveDocument(doc)
    })
} else {
    // Fallback for files not open in editor
    file.setBinaryContent(newContent.toByteArray(Charsets.UTF_8))
}
```

---

### 6. BulkFileListener registration

Register as `<applicationListeners>` in `plugin.xml` — **not** `<projectListeners>` and not `<vfs.bulkFileListener>`:

```xml
<applicationListeners>
    <listener
        topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"
        class="com.example.MyBulkFileListener"/>
</applicationListeners>
```

`BulkFileListener` is an **application-level** topic. `<projectListeners>` silently fails to fire for VFS events.

The listener gets a no-arg constructor. To find the relevant `Project` for a file:
```kotlin
private fun findProjectForFile(file: VirtualFile): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { p ->
        if (p.isDisposed) return@firstOrNull false
        try {
            ProjectFileIndex.getInstance(p).isInContent(file)
        } catch (e: Exception) {
            // Fallback: path-based check (isInContent can fail in Rider)
            p.basePath != null && file.path.startsWith(p.basePath!!)
        }
    }
```

Note: `ProjectFileIndex.isInContent()` returns `false` in Rider immediately after a move event — the index hasn't updated yet. The path-based fallback is necessary.

---

### 7. ProjectFileIndex.isInContent() returns false after move in Rider

After a `VFileMoveEvent`, Rider's project index hasn't updated yet.
`ProjectFileIndex.isInContent(file)` returns `false` even for files that belong to the project.

**Fix:** Always add a path-based fallback:
```kotlin
private fun findProjectForFile(file: VirtualFile): Project? {
    for (p in ProjectManager.getInstance().openProjects) {
        if (p.isDisposed) continue
        try {
            if (ProjectFileIndex.getInstance(p).isInContent(file)) return p
        } catch (e: Exception) { /* ignore */ }
        // Fallback: isInContent() is unreliable right after VFileMoveEvent in Rider
        if (p.basePath != null && file.path.startsWith(p.basePath!!)) return p
    }
    return null
}
```

---

### 8. EDT requirement for dialogs and WriteCommandAction

`DialogWrapper.showAndGet()` and `WriteCommandAction.runWriteCommandAction()` must run on the EDT.

Call from `BulkFileListener.after()` using:
```kotlin
ApplicationManager.getApplication().invokeLater({
    // show dialog, then WriteCommandAction
}, ModalityState.nonModal())
```

---

### 8. plugin.xml — the `<name>` tag bug

The IntelliJ Platform Gradle Plugin `patchPluginXml` task rewrites `<name>` as `<n>` in some tool environments, which prevents the plugin from loading (no extensions are registered).

**Fix:** Disable `patchPluginXml` entirely in `build.gradle.kts`:
```kotlin
patchPluginXml { enabled = false }
```
Then manage `plugin.xml` manually. Verify the built JAR with:
```powershell
Add-Type -Assembly System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead("build\distributions\NamespaceMover-1.0.0.zip")
$zip.Entries | Where-Object { $_.Name -eq "plugin.xml" } | ForEach-Object {
    $reader = New-Object IO.StreamReader($_.Open())
    $reader.ReadToEnd()
}
```
