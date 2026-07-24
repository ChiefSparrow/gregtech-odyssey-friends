# Сторонние компоненты

Исходный репозиторий содержит локальные исправления, перевод, конфигурацию и
инструменты упаковки. Сторонние игровые файлы скачиваются по точным
идентификаторам или URL и проверяются по закреплённым размеру и SHA-256.
Лицензионный установщик также использует официальные portable Prism Launcher и
Eclipse Temurin; приватная копия Temurin 21 включена в его ZIP, чтобы запуск не
зависел от уже установленной в Windows Java.

| Компонент | Project ID | File ID | Источник |
|---|---:|---:|---|
| GregTech Odyssey 0.5.6-beta | — | — | https://gtodyssey.com/ |
| Minecraft Forge 1.20.1-47.4.20 | — | — | https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html |
| Minecraft 1.20.1 version metadata | — | — | https://piston-meta.mojang.com/ |
| Prism Launcher 11.0.3, Windows MinGW x64 Portable | — | — | https://github.com/PrismLauncher/PrismLauncher/releases/tag/11.0.3 |
| Eclipse Temurin JRE 21.0.11+10 x64 | — | — | https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.11%2B10 |
| Easy Villagers 1.20.1-1.1.39 | 400514 | 7202309 | https://www.curseforge.com/minecraft/mc-mods/easy-villagers |
| Oculus 1.20.1-1.8.0 | 581495 | 6020952 | https://www.curseforge.com/minecraft/mc-mods/oculus |
| HT's TreeChop 0.19.0 fixed | 421377 | 5565422 | https://www.curseforge.com/minecraft/mc-mods/treechop |
| Panda's Falling Trees 0.13.2 | 880630 | 6231030 | https://www.curseforge.com/minecraft/mc-mods/pandas-falling-trees |
| Complementary Reimagined r5.6.1 | 627557 | 7090226 | https://www.curseforge.com/minecraft/shaders/complementary-reimagined |
| Euphoria Patcher 1.7.7 r5.6.1 Forge | 915902 | 7214963 | https://www.curseforge.com/minecraft/mc-mods/euphoria-patches |

Complementary и Euphoria не загружаются в GitHub Release напрямую. Такой способ
соблюдает требование их лицензий использовать штатные системы CurseForge или
патчер.

Лицензионный установщик размещает Prism Launcher, его `UserData`, игровую
сборку и приватную Java в отдельной папке
`%LOCALAPPDATA%\GTO-Friends-Licensed`. Он не заменяет установленный
пользователем Prism Launcher. Учётная запись Microsoft хранится самим Prism и
не включается в релиз, резервные копии или журналы установщика.

- Официальная страница загрузки Prism для Windows:
  https://prismlauncher.org/download/windows/
- Лицензия Prism Launcher: GNU General Public License v3.0.
- Eclipse Temurin основан на OpenJDK и распространяется в соответствии с
  лицензией GNU GPL v2 с Classpath Exception; сведения о лицензиях входят в
  официальный архив Temurin.
