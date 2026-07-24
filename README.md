# GregTech Odyssey — Friends Edition

Неофициальная сборка для совместной игры на базе **GregTech Odyssey
0.5.6-beta** (Minecraft 1.20.1, Forge 47.4.20).

Готовый файл для игроков находится в разделе
[Releases](https://github.com/ChiefSparrow/gregtech-odyssey-friends/releases).
Скачивать исходники через зелёную кнопку **Code** для установки игры не нужно.

## Выберите инструкцию

- **Есть лицензия Minecraft:** используйте
  [one-click установщик с portable Prism Launcher](INSTALL-PRISM-LICENSED.md).
  Отдельно устанавливать Prism, Java или Forge и вручную импортировать сборку
  не нужно.
- **Нет лицензии, запуск через TLauncher:** используйте
  [отдельную инструкцию для TLauncher](INSTALL-TLAUNCHER-NO-LICENSE.md).
  Скачайте отдельный `GTO-Friends-TLauncher-Installer-*.zip`: Prism для этого
  варианта не нужен, точные зависимости установщик скачает и проверит сам.

## Краткая установка с лицензией

1. Скачайте и полностью распакуйте
   [`GTO-Friends-Licensed-Prism-Installer-v1.0.4.zip`](https://github.com/ChiefSparrow/gregtech-odyssey-friends/releases/latest/download/GTO-Friends-Licensed-Prism-Installer-v1.0.4.zip).
2. Закройте Minecraft и все окна Prism Launcher.
3. Дважды нажмите `INSTALL-GTO-LICENSED.bat` и дождитесь окончания установки.
4. В открывшемся Prism войдите в Microsoft-аккаунт, на котором куплена
   Minecraft: Java Edition: **кнопка аккаунта → Manage Accounts → Add
   Microsoft**.
5. Выберите **GregTech Odyssey — Friends Edition** и нажмите
   **Launch / Запустить**.

Установщик ставит отдельный официальный portable Prism Launcher 11.0.3
(MinGW x64), приватную Temurin Java 21 и точную Friends Edition 1.0.4 со всеми
нашими исправлениями. Ручной импорт ZIP, выбор Forge и настройка Java не нужны.
Полная инструкция, обновление и решение типовых проблем описаны в
[инструкции для лицензии](INSTALL-PRISM-LICENSED.md).

## Краткая установка через TLauncher

1. Запустите через TLauncher любую версию Minecraft один раз и закройте её.
2. Скачайте и полностью распакуйте
   [`GTO-Friends-TLauncher-Installer-v1.0.4.zip`](https://github.com/ChiefSparrow/gregtech-odyssey-friends/releases/latest/download/GTO-Friends-TLauncher-Installer-v1.0.4.zip).
3. Дважды нажмите `INSTALL-GTO-TLAUNCHER.bat`, затем
   **Установить сборку**.
4. Полностью перезапустите TLauncher, укажите показанную игровую папку и
   выберите локальную версию `1.20.1-forge-47.4.20` и `10240 МБ` памяти.
   Выбор Java оставьте автоматическим: установщик добавляет приватную Java 21.
   Запись `Forge 1.20.1` не выбирайте.

Установщик не требует прав администратора, не меняет реестр и проверяет SHA-256
каждой загрузки, всех 177 обязательных файлов, локального Forge и Java 21.
Подробности и безопасная проверка архива описаны в
[инструкции для TLauncher](INSTALL-TLAUNCHER-NO-LICENSE.md).

Если шейдер не включился при самом первом запуске, откройте
**Настройки → Графика → Наборы шейдеров** и выберите
`ComplementaryReimagined_r5.6.1 + EuphoriaPatches_1.7.7`.

## Что добавлено

- `gto-terminal-fix 1.0.0` — закрывает дюп Simple Crafting Terminal с
  переносимым Sophisticated Backpack без фоновых сканирований и новых тиков.
- `gto-farming-fix 1.0.1` — безопасный сбор зрелых культур ПКМ, совместимость
  Easy Villagers с проверенными культурами и нормальная скорость тыкв/арбузов.
- `gto-russian-fixes 1.0.0` — 1 563 точечных русских перевода в 34
  пространствах имён, включая интерфейсы и меню карты Xaero.
- TreeChop и Panda's Falling Trees — быстрая рубка и физическое падение дерева.
- Oculus + официальный Complementary Reimagined r5.6.1 + Euphoria Patches
  1.7.7, а также перенесённый пользовательский пресет FreshEdit.

Оригинальные моды, TreeChop и шейдерные компоненты скачиваются лаунчером с
CurseForge. Это не поиск «последней версии»: `manifest.json` фиксирует точные
пары `projectID + fileID`. Начиная с версии 1.0.1 сборка дополнительно содержит
`CLIENT-MOD-LOCK.json` с размером и SHA-256 каждого файла и строгий проверяющий
скрипт. Поэтому несовпавшая, пропущенная или лишняя версия обнаруживается до
подключения к серверу.

Запрещённые лицензиями копии сторонних JAR/шейдеров в репозитории и релизе не
хранятся. В частности, лицензии Complementary и Euphoria требуют установки
через CurseForge/патчер, поэтому «целый ZIP со всеми бинарниками» нельзя
легально опубликовать в открытом GitHub.

## Совместимость

- Minecraft: `1.20.1`
- Forge: `47.4.20`
- База: GregTech Odyssey `0.5.6-beta`
- Easy Villagers: `1.20.1-1.1.39`
- Режим прогрессии GTOCore: `Easy`.
- Ванильная сложность новых миров: `Normal`, без блокировки.
- Клиенту нужна 64-битная Java 21. Оба one-click установщика ставят отдельную
  приватную копию автоматически.
- Сервер должен использовать ту же базовую версию и серверные версии обоих
  `gto-*-fix` модов. Шейдеры и Oculus серверу не нужны.

## Обновление без потери миров

Для проверки или восстановления лицензированной 1.0.4 повторно запустите её
`INSTALL-GTO-LICENSED.bat`, выбрав прежнюю папку установки. Переходите с ней
на будущую версию в той же папке только при явном указании совместимости в
описании нового релиза; иначе используйте отдельную папку. Перед операцией
закройте Minecraft и Prism. Установщик сохраняет Microsoft-аккаунт, настройки
Prism, миры, серверы, скриншоты и карты Xaero. TLauncher-версия обновляется
своим `INSTALL-GTO-TLAUNCHER.bat` по правилам её инструкции.

Режим GTO записывается внутрь мира отдельно от ванильной сложности. Мир,
созданный в GTO `Normal` или `Expert`, нельзя молча понизить до `Easy`:
оба установщика остановят такое обновление до изменения файлов. Для этой
редакции выберите отдельную чистую папку и создайте новый мир после установки
GTO Easy.

## Для разработчика или нейросети

Строгий машинно-читаемый контракт установки находится в
[`distribution/INSTALL-AI.json`](distribution/INSTALL-AI.json), а параметры
релиза — в [`distribution/pack-config.json`](distribution/pack-config.json).
Эталон файлов клиента находится в
[`distribution/CLIENT-MOD-LOCK.json`](distribution/CLIENT-MOD-LOCK.json).

Сборка релиза на Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\build-release.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\verify-release.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\VERIFY-CLIENT.ps1 `
  -MinecraftDirectory 'C:\path\to\Prism\instance\minecraft' `
  -LockPath .\distribution\CLIENT-MOD-LOCK.json

# Отдельный воспроизводимый установщик для TLauncher
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\build-tlauncher-installer.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\verify-tlauncher-installer.ps1

# Отдельный воспроизводимый one-click установщик с portable Prism
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\build-prism-installer.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\distribution\verify-prism-installer.ps1
```

Скрипт принимает только официальный базовый архив с зафиксированной SHA-256 и
явно перечисленные общие файлы. Он не читает и не упаковывает `saves`, логи,
скриншоты, карту Xaero, аккаунты, `servers.dat` или пользовательский кэш.

## Авторы и ответственность

Это неофициальная надстройка. GregTech Odyssey и все сторонние моды принадлежат
их авторам. Официальные источники и точные CurseForge ID перечислены в
[`THIRD-PARTY.md`](THIRD-PARTY.md). Проблемы этой редакции следует сообщать в
данный репозиторий, а не авторам Complementary или GregTech Odyssey.
