# GTO Simple Crafting Terminal Fix

Local compatibility patch for GregTech Odyssey 0.5.6-beta.

When the storage block next to a Simple Crafting Terminal disappears, the
terminal now drops its stale inventory delegate and clears external-storage
strategy wrappers. This prevents a picked-up Sophisticated Backpack from
leaving a second, stale copy of its contents accessible through the terminal.

The patch runs only when `SimpleCraftingTerminal.updateTarget()` is invoked by
the existing mod logic; it adds no tick handler or inventory scan.

## Build

Requires a JDK and network access. Gradle uses a Java 17 toolchain.

```powershell
.\gradlew.bat clean build --no-daemon
```

The reobfuscated Forge JAR is written to `build/libs/`.
