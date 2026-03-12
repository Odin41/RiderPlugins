# RiderPlugins

A collection of plugins for JetBrains Rider.

Developed to automate routine tasks when working with C# projects.
Built with the assistance of AI-based tools.

---

## Plugins

| Plugin | Description |
|--------|-------------|
| [NamespaceConverter](./NamespaceConverter) | Converts C# block-scoped namespaces to file-scoped namespaces |
| [NamespaceMover](./NamespaceMover) | Automatically updates the namespace declaration when a `.cs` file is moved to a different folder |
| [UnusedMethodsAnalyzer](./UnusedMethodsAnalyzer) | Finds unused C# symbols (methods, properties, classes, interfaces, enums) across the entire solution |

---

## Building

Each plugin is built independently via Docker:

```bat
cd UnusedMethodsAnalyzer
docker-build.bat
```

See the `README.md` inside each plugin folder for details.

---

## License

Distributed under the **MIT License**.

You are free to use, copy, modify, and distribute this code for personal and commercial purposes, subject to one condition:

**When using this code or any part of it, you must include a reference to the original source:**
```
https://github.com/Odin41/RiderPlugins
```

The software is provided **"as is"**, without warranty of any kind — express or implied. The authors are not liable for any damages arising from the use of this code.

Full license text: [LICENSE](./LICENSE)
