# Namespace Mover

Плагин для JetBrains Rider, который автоматически обновляет пространства имён C# при перемещении файлов `.cs` между папками.

## Как работает

1. Вы перетаскиваете файл `.cs` в новую папку в дереве проекта Rider
2. Плагин обнаруживает перемещение через `BulkFileListener`
3. Вычисляет правильное новое пространство имён из ближайшего `.csproj` (`<RootNamespace>`) + относительного пути к папке
4. Диалог подтверждения показывает разницу старое → новое пространство имён
5. После подтверждения все объявления пространства имён в файле обновляются

## Возможности

- Поддерживает как **файловые** (`namespace Foo.Bar;`), так и **блочные** (`namespace Foo.Bar { }`) пространства имён
- Обновляет **все объявления пространств имён** в файле, если их несколько
- Сохраняет оригинальные **переносы строк** (CRLF) и **UTF-8 BOM** (стандарт Visual Studio)
- Преобразует имена папок в допустимые идентификаторы C# (пробелы/дефисы → подчёркивания, ведущие цифры → префикс `_`)

---

## Заметки по работе с файлами для разработчиков плагинов

Эти проблемы были обнаружены в процессе разработки и касаются любого плагина для IntelliJ/Rider, который читает или изменяет исходные файлы C#.

### 1. UTF-8 BOM

Visual Studio по умолчанию создаёт файлы `.cs` с UTF-8 BOM (`\uFEFF`, байты `EF BB BF`).

**Проблема:** `String(file.contentsToByteArray(), Charsets.UTF_8)` **не** убирает BOM.
Результирующая строка начинается с `\uFEFF`, поэтому `^` в многострочном regex не сработает на самой первой строке.

**Решение:**
```kotlin
val stripped = if (content.startsWith("\uFEFF")) content.substring(1) else content
```
Или используйте `Charsets.UTF_8` с явным определением BOM. При обратной записи восстановите BOM, если он был.

---

### 2. Переносы строк CRLF

В Windows файлы используют `\r\n`. Флаг `(?m)` в Java/Kotlin regex делает `^` совпадающим только после `\n` — **не** после `\r`.

**Проблема:** В CRLF-файле `(?m)^(\s*)namespace` захватывает `\r\n` в группу отступа `\s*`,
из-за чего при замене появляется лишняя пустая строка и ломаются отступы.

**Решение:** Нормализуйте переносы строк перед сопоставлением, восстановите после:
```kotlin
fun normalize(content: String) = content.replace("\r\n", "\n").replace("\r", "\n")

// В шаблоне regex используйте [ \t]* для отступа вместо \s*, чтобы никогда не захватывать переносы:
val NS_REGEX = Regex("""(?m)^([ \t]*)namespace\s+([\w.]+)\s*([;{])""")
```

При обратной записи:
```kotlin
val hasCrlf = content.contains("\r\n")
if (hasCrlf) result = result.replace("\n", "\r\n")
```

---

### 3. VirtualFile.path всегда использует прямые слеши

`VirtualFile.path` возвращает пути с разделителем `/` на **всех платформах**, включая Windows.
Вызывать `.replace('\\', '/')` для путей VirtualFile не нужно.

`project.basePath` однако может возвращать обратные слеши на Windows — нормализуйте при сравнении:
```kotlin
val normalizedBase = basePath.replace('\\', '/')
```

---

### 4. Чтение содержимого файла — байты или Document

Два способа прочитать файл:

| Метод | Когда использовать |
|-------|-------------------|
| `file.contentsToByteArray()` | Файл может быть не открыт в редакторе; читает с диска |
| `FileDocumentManager.getInstance().getDocument(file)?.text` | Файл открыт в редакторе; отражает несохранённые изменения |

Предпочтительнее подход через Document — он также интегрируется с отменой/повтором действий.

---

### 5. Запись содержимого файла — Document или setBinaryContent

```kotlin
val doc = FileDocumentManager.getInstance().getDocument(file)
if (doc != null) {
    // Правильно: участвует в отмене/повторе, вызывает обновление редактора
    WriteCommandAction.runWriteCommandAction(project, "Описание", null, {
        doc.setText(newContent)
        FileDocumentManager.getInstance().saveDocument(doc)
    })
} else {
    // Запасной вариант для файлов, не открытых в редакторе
    file.setBinaryContent(newContent.toByteArray(Charsets.UTF_8))
}
```

---

### 6. Регистрация BulkFileListener

Регистрируйте через `<applicationListeners>` в `plugin.xml` — **не** через `<projectListeners>` и не через `<vfs.bulkFileListener>`:

```xml
<applicationListeners>
    <listener
        topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"
        class="com.example.MyBulkFileListener"/>
</applicationListeners>
```

`BulkFileListener` — топик **уровня приложения**. `<projectListeners>` молча не срабатывает для событий VFS.

Слушатель создаётся конструктором без аргументов. Для поиска `Project` по файлу:
```kotlin
private fun findProjectForFile(file: VirtualFile): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { p ->
        if (p.isDisposed) return@firstOrNull false
        try {
            ProjectFileIndex.getInstance(p).isInContent(file)
        } catch (e: Exception) {
            // Запасной вариант: проверка по пути (isInContent может не работать в Rider)
            p.basePath != null && file.path.startsWith(p.basePath!!)
        }
    }
```

Примечание: `ProjectFileIndex.isInContent()` возвращает `false` в Rider сразу после события перемещения — индекс ещё не обновился. Запасной вариант по пути необходим.

---

### 7. ProjectFileIndex.isInContent() возвращает false после перемещения в Rider

После `VFileMoveEvent` индекс проекта Rider ещё не обновился.
`ProjectFileIndex.isInContent(file)` возвращает `false` даже для файлов, принадлежащих проекту.

**Решение:** Всегда добавляйте запасную проверку по пути:
```kotlin
private fun findProjectForFile(file: VirtualFile): Project? {
    for (p in ProjectManager.getInstance().openProjects) {
        if (p.isDisposed) continue
        try {
            if (ProjectFileIndex.getInstance(p).isInContent(file)) return p
        } catch (e: Exception) { /* игнорировать */ }
        // Запасной вариант: isInContent() ненадёжен сразу после VFileMoveEvent в Rider
        if (p.basePath != null && file.path.startsWith(p.basePath!!)) return p
    }
    return null
}
```

---

### 8. Требование EDT для диалогов и WriteCommandAction

`DialogWrapper.showAndGet()` и `WriteCommandAction.runWriteCommandAction()` должны выполняться в потоке EDT.

Вызов из `BulkFileListener.after()`:
```kotlin
ApplicationManager.getApplication().invokeLater({
    // показать диалог, затем WriteCommandAction
}, ModalityState.nonModal())
```

---

### 9. plugin.xml — баг с тегом `<name>`

Задача `patchPluginXml` плагина IntelliJ Platform Gradle Plugin в некоторых окружениях переписывает `<name>` как `<n>`, из-за чего плагин не загружается (расширения не регистрируются).

**Решение:** Полностью отключите `patchPluginXml` в `build.gradle.kts`:
```kotlin
patchPluginXml { enabled = false }
```
После этого управляйте `plugin.xml` вручную. Проверьте содержимое собранного JAR:
```powershell
Add-Type -Assembly System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead("build\distributions\NamespaceMover-1.0.0.zip")
$zip.Entries | Where-Object { $_.Name -eq "plugin.xml" } | ForEach-Object {
    $reader = New-Object IO.StreamReader($_.Open())
    $reader.ReadToEnd()
}
```
