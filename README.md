# DBML Diagram – IntelliJ IDEA plugin

[English](#english) | [Slovensky](#slovensky)

## English

A plugin for IntelliJ-based IDEs that opens `.dbml` files ([dbdiagram.io](https://dbdiagram.io) / DBML) in a split editor:

- **left** – the DBML source with syntax highlighting,
- **right** – a live ER diagram in dbdiagram.io style, updated as you type.

Diagram features: drag tables, pan, zoom (mouse wheel or toolbar), fit to window, reset layout, highlight relationships,
`TableGroup` support, double-click a table to jump to its definition, export to PNG/SVG and copy to clipboard.

### Installation

Requirements: IntelliJ IDEA 2026.3+ and JDK 25.

1. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```
   The build uses the locally installed IDEA from
   `~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate` – change the path in `build.gradle.kts` if yours is elsewhere.
2. In the IDE open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select
   `build/distributions/dbml-idea-plugin-1.0.0.zip`.
3. Restart the IDE and open any `.dbml` file.

To try it without installing, run `./gradlew runIde` (starts a sandbox IDE with the plugin).

---

## Slovensky

Plugin pre IDE postavené na IntelliJ, ktorý otvára súbory `.dbml` ([dbdiagram.io](https://dbdiagram.io) / DBML) v rozdelenom editore:

- **vľavo** – zdrojový DBML so zvýrazňovaním syntaxe,
- **vpravo** – živý ER diagram v štýle dbdiagram.io, ktorý sa aktualizuje počas písania.

Funkcie diagramu: presúvanie tabuliek, posun plátna, zoom (koliesko myši alebo toolbar), prispôsobenie oknu, reset rozloženia,
zvýraznenie vzťahov, podpora `TableGroup`, dvojklik na tabuľku skočí na jej definíciu, export do PNG/SVG a kopírovanie do schránky.

### Inštalácia

Požiadavky: IntelliJ IDEA 2026.3+ a JDK 25.

1. Zostavte plugin:
   ```bash
   ./gradlew buildPlugin
   ```
   Build používa lokálne nainštalovanú IDEA z
   `~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate` – ak ju máte inde, upravte cestu v `build.gradle.kts`.
2. V IDE otvorte **Settings → Plugins → ⚙ → Install Plugin from Disk…** a vyberte
   `build/distributions/dbml-idea-plugin-1.0.0.zip`.
3. Reštartujte IDE a otvorte ľubovoľný `.dbml` súbor.

Bez inštalácie si ho môžete vyskúšať cez `./gradlew runIde` (spustí sandbox IDE s pluginom).
