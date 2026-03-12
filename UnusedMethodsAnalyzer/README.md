# Unused Methods Analyzer

A JetBrains Rider plugin that finds unused C# symbols across your entire solution — methods, properties, classes, interfaces, and enums — using the same reference search engine Rider uses for **Find Usages**, extended with markup and text search to catch cases PSI alone would miss.

---

## Features

- **Full solution scan** or **selected folder only**
- **Four symbol types** — methods, properties, classes, interfaces, and enums
- **Three-tier reference detection** — no false positives from interface calls, generic methods, or Blazor/Razor event bindings
- **Sortable, filterable results table** — filter by name, class, file, or symbol type simultaneously
- **One-click navigation** — double-click or Enter to jump to any symbol in the editor
- **Right-click context menu** — Go To, Mark \[Obsolete\], Copy name / signature
- **Export results** to CSV (Excel-compatible) or TXT (grouped report by symbol type)
- **Persistent results** — last analysis is restored automatically when you reopen the project
- **Editor gutter markers** — files with unused symbols are annotated in the gutter
- **Mark as \[Obsolete\]** directly from the results list, with custom message (methods only)

---

## How It Works

Analysis runs in three passes:

**Pass 1 — Collect candidates**
Walks the PSI tree of all `.cs` files and extracts declarations. Each symbol type uses a dedicated name-extraction strategy:

| Symbol | PSI type | Name extraction |
|--------|----------|-----------------|
| Method | `CSharpMethodDeclaration` | `PsiNamedElement` → reflection → method regex |
| Property / Field | `CSharpPropertyDeclaration` | Property regex (`public T Name {`) |
| Class / Struct | `CSharpClassDeclaration` | Type-declaration regex (`class Foo`) |
| Interface | `CSharpInterfaceDeclaration` | Type-declaration regex (`interface IFoo`) |
| Enum | `CSharpEnumDeclaration` | Type-declaration regex (`enum MyEnum`) |

Constructors, destructors, and generated files (`.Designer.cs`, `.g.cs`) are excluded automatically.

**Pass 2 — Verify each symbol through three steps:**

| Step | What it checks | What it catches |
|------|---------------|-----------------|
| **A** PSI `ReferencesSearch` | Direct semantic references across the project | Direct calls, most usages |
| **B** Markup text search | `.razor`, `.cshtml`, `.xaml` files | `OnValidSubmit="Method"`, `@onclick="_ref.Method"`, `nameof(...)` |
| **C** C# text search | All `.cs` files (word-boundary regex) | Interface dispatch, generic calls, delegates, `nameof`, reflection strings |

A symbol is reported as unused only if all three steps find zero references.

**Project discovery**
The plugin parses the `.sln` file to find all referenced `.csproj` directories, including projects outside `basePath` (shared libraries, helper projects in sibling folders).

---

## Installation

1. Build the plugin:
   ```bat
   docker-build.bat
   ```
   The `.zip` file is produced in `build/distributions/`.

2. In Rider: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the `.zip`.

3. Restart Rider.

---

## Usage

### Analyze entire solution
- Click **Analyze Project** in the *Unused Methods* tool window, or
- Use the shortcut **Ctrl+Alt+Shift+U**, or
- Go to **Tools → Analyze Unused Methods**

### Analyze a specific folder
Right-click any folder in the Project tree → **Analyze Unused Methods Here**

### Navigate to a symbol
Double-click a row, press **Enter**, or right-click → **Go To**.

### Filter results
Use the filter fields in the toolbar to narrow results by name, class, file, or symbol type. All filters work simultaneously (AND logic). The **Type** dropdown lets you show only Methods, Properties, Classes, Interfaces, or Enums. Click **✕** to clear all filters.

### Sort results
Click any column header to sort. Click again to reverse. A third click removes the sort. The **Line** column sorts numerically.

### Mark as \[Obsolete\]
Select a method → click **Mark \[Obsolete\]** (or right-click → **Mark \[Obsolete\]**) → enter a message. The attribute is inserted above the method declaration and the method is removed from the results list. Only available for methods.

### Export
Click **Export** in the toolbar and choose a format:
- **CSV** — comma-separated, includes a `Kind` column, suitable for Excel or any spreadsheet tool
- **TXT** — plain text report grouped by symbol type, then by class, suitable for code review

---

## Settings

Open **Settings** (⚙ icon in the toolbar) to configure:

**Method options**

| Option | Description |
|--------|-------------|
| \[Obsolete\] text | Default message inserted by Mark \[Obsolete\] |
| Excluded names | Comma-separated symbol names to always ignore |
| Exclude private methods | Skip methods declared `private` |
| Exclude override methods | Skip methods with `override` modifier |
| Exclude event handlers | Skip methods matching common event handler patterns |
| Exclude test methods | Skip methods with `[Test]`, `[Fact]`, `[Theory]` attributes |

**Symbol types to analyze**

| Option | Description |
|--------|-------------|
| Analyze properties | Include property and field declarations |
| Analyze classes | Include class and struct declarations |
| Analyze interfaces | Include interface declarations |
| Analyze enums | Include enum declarations |

---

## What Is Automatically Excluded

The following are never reported regardless of settings:

- Constructors and destructors
- Methods with attributes: `[Obsolete]`, `[Test]`, `[Fact]`, `[Theory]`, `[UsedImplicitly]`, `[HttpGet/Post/Put/Delete/Patch]`, `[JSInvokable]`, `[Parameter]`, `[Inject]`
- Generated files: `*.Designer.cs`, `*.g.cs`, `*.g.i.cs`

---

## Known Limitations

- **PSI search scope** is limited to what IntelliJ's `ReferencesSearch` can resolve from the Kotlin frontend. Calls that exist only in reflection strings are caught by text search only — no semantic validation.
- **Dynamic dispatch** (calls through the `dynamic` keyword) is not detected.
- **Class/interface name extraction** relies on regex on PSI element text, since `CSharpClassDeclaration` does not implement `PsiNamedElement` in Rider. If a type name is not found, it appears as `Unknown` — check the PSI dump in idea.log.
- Results **do not auto-refresh** when you edit code — re-run the analysis after making changes.

---

## Project Structure

```
src/main/kotlin/com/github/unusedmethods/
├── CSharpAnalyzer.kt                 # Core analysis engine (PSI + text search)
├── UnusedMethodsToolWindowFactory.kt # Tool window UI, filters, export
├── UnusedMethodsService.kt           # Project service, persistent state
├── UnusedMethodsSettings.kt          # Application-level settings
├── UnusedMethodsGutterProvider.kt    # Editor gutter markers
├── RunAnalysisAction.kt              # Toolbar / shortcut action
├── AnalyzeFolderAction.kt            # Project tree context menu action
└── MethodInfo.kt                     # Data class for a result entry (with SymbolKind)
```

---

## Build Requirements

| Component | Version |
|-----------|---------|
| JetBrains Rider | 2025.2+ |
| IntelliJ Platform Gradle Plugin | 2.11.0 |
| Gradle | 8.13 |
| Docker (for build image) | any recent version |

The build uses a pre-built base image `rider-plugin-base` that caches the Rider SDK for fast incremental builds.
