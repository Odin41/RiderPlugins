# C# Namespace Converter – Rider Plugin

Converts **block-scoped namespaces** to **file-scoped namespaces** (C# 10 / .NET 6+) with a single click.

## What it does

**Before:**
```csharp
namespace MyApp.Services
{
    public class OrderService
    {
        public void Process() { }
    }
}
```

**After:**
```csharp
namespace MyApp.Services;

public class OrderService
{
    public void Process() { }
}
```

The plugin also runs Rider's built-in code formatter to fix indentation after the transformation.

---

## Installation (from source)

### Requirements
- JDK 17+
- Gradle 8.5 (via wrapper – no install needed)
- JetBrains Rider 2023.3+ (or adjust `version` in `build.gradle.kts`)

### Build

```bash
# Clone / unzip the project, then:
./gradlew buildPlugin
```

The ready-to-install `.zip` will be at:
```
build/distributions/namespace-converter-1.0.0.zip
```

### Install in Rider

1. Open Rider → **Settings** → **Plugins**
2. Click the ⚙️ gear icon → **Install Plugin from Disk…**
3. Select the `.zip` file produced above
4. Restart Rider

---

## Usage

The action **"Namespace: Remove Curly Braces"** appears in three places for `.cs` files:

| Location | How to access |
|----------|--------------|
| Project/Explorer tree | Right-click on any `.cs` file |
| Editor tab | Right-click on the file tab at the top |
| Inside the editor | Right-click anywhere in the code |

The menu item is **only visible** when the file actually contains a block-scoped namespace – it is hidden for files that already use file-scoped syntax.

---

## Project structure

```
src/
  main/
    kotlin/com/github/nsconverter/
      ConvertNamespaceAction.kt   ← IntelliJ action (menu item, update logic)
      NamespaceConverter.kt       ← Pure conversion logic (no IDE deps)
    resources/META-INF/
      plugin.xml                  ← Plugin descriptor & action registration
  test/
    kotlin/com/github/nsconverter/
      NamespaceConverterTest.kt   ← JUnit 5 unit tests
build.gradle.kts
settings.gradle.kts
```

### Key files explained

#### `NamespaceConverter.kt`
- **`hasBlockScopedNamespace(text)`** – regex check, used to show/hide menu item
- **`convert(text)`** – the full transformation:
  1. Locates `namespace Name` declaration (no trailing `;`)
  2. Finds the matching opening `{`
  3. Walks the file counting brace depth (skips strings, chars, comments)
  4. Finds the closing `}`
  5. Rebuilds the file: replaces declaration with `namespace Name;`, strips one indent level from body lines, removes outer braces

#### `ConvertNamespaceAction.kt`
- `update()` – runs on BGT (background thread); hides action if not applicable
- `actionPerformed()` – runs transformation inside a `WriteCommandAction` (undo-safe), commits PSI, calls `CodeStyleManager.reformat()`, saves the document

---

## Running tests

```bash
./gradlew test
```

---

## Adjusting the Rider SDK version

Edit `build.gradle.kts`:

```kotlin
intellij {
    type.set("RD")
    version.set("2023.3.2")   // ← change to your Rider version
}
```

To use a **local Rider installation** instead of downloading the SDK:

```kotlin
intellij {
    type.set("RD")
    localPath.set("/path/to/JetBrains Rider 2023.3")
}
```

---

## Limitations / known issues

- Handles **one namespace per file** (the standard C# convention).  
  Files with multiple namespaces are not common but will only convert the first one.
- The `reformat()` call uses Rider's active C# code style settings, so the final indentation matches your project style automatically.
