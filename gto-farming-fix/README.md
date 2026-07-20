# GTO Farming Fix

Local Forge 1.20.1 compatibility addon for GregTech Odyssey 0.5.6-beta.

## Features

- Quark-style right-click harvesting for verified simple crops. Immature crops
  continue to accept bone meal; a click on the mature crop harvests it, removes
  one planting item from the drops when available, and resets the same block.
- Easy Villagers farmer support for Farmer's Delight crops, melon and pumpkin.
  Melon and pumpkin stems restart their full growth cycle after each harvest so
  their production speed stays consistent with ordinary crops.
- Also supports vanilla mushrooms, sugar cane, Nether Wart, cocoa, vanilla and modded
  berry bushes, Ars Nouveau Magebloom, Farmer's Respite tea, and Farmer's
  Delight rice.
- Tomato seeds are mapped to the fruiting tomato vine instead of the budding
  placeholder, fixing the upstream "seeds only" output.
- Coffee, Oddion, cactus, bamboo, kelp, torchflower, and pitcher plants are
  intentionally unsupported.

Tea seeds default to black leaves. Right-click a tea farmer with green, yellow,
or black tea leaves to choose its harvest stage. The sample leaf is not
consumed. Shift-right-click returns the original planting item for all custom
crops.

All crop work is event-driven or uses Easy Villagers' existing farmer tick.
There is no world scan or additional per-tick crop loop.

## Build

Requires a JDK and network access. Gradle uses a Java 17 toolchain and resolves
the exact Easy Villagers 1.20.1-1.1.39 dependency from CurseMaven.

```powershell
.\gradlew.bat clean build --no-daemon
```

The reobfuscated Forge JAR is written to `build/libs/`.
