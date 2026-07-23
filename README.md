# GregTech Odyssey — Friends Edition

Неофициальная сборка для совместной игры на базе **GregTech Odyssey
0.5.6-beta** (Minecraft 1.20.1, Forge 47.4.20).

Готовый файл для игроков находится в разделе
[Releases](https://github.com/ChiefSparrow/gregtech-odyssey-friends/releases).
Скачивать исходники через зелёную кнопку **Code** для установки игры не нужно.

## Установка для игрока

1. Установите [Prism Launcher](https://prismlauncher.org/download/).
2. Скачайте последний файл
   `GregTech-Odyssey-Friends-0.5.6-v*.zip` из Releases.
3. В Prism Launcher нажмите **Добавить сборку → Импорт → файл ZIP**.
4. Войдите в лицензионный Microsoft/Minecraft-аккаунт.
5. Выделите сборке **8–12 ГБ RAM**. Для слабого компьютера начните с 8 ГБ.
6. Запустите сборку. Первый запуск дольше обычного: лаунчер скачивает моды, а
   Euphoria Patcher один раз создаёт лицензионно корректную версию шейдера.
7. Закройте игру, откройте папку экземпляра и запустите
   `VERIFY-CLIENT.ps1`. Результат `CLIENT VERIFICATION PASSED` подтверждает,
   что все 176 модов совпадают с эталоном байт-в-байт.

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
пары `projectID + fileID`. Версия 1.0.1 дополнительно содержит
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
- Клиенту рекомендуется Java 17. Prism может скачать подходящую Java сам.
- Сервер должен использовать ту же базовую версию и серверные версии обоих
  `gto-*-fix` модов. Шейдеры и Oculus серверу не нужны.

## Обновление без потери миров

Импортируйте новый ZIP как отдельную сборку. После успешного запуска перенесите
только нужные `saves` из старого экземпляра или подключитесь к тому же серверу.
Не заменяйте старую папку целиком: так проще откатиться при проблеме.

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
```

Скрипт принимает только официальный базовый архив с зафиксированной SHA-256 и
явно перечисленные общие файлы. Он не читает и не упаковывает `saves`, логи,
скриншоты, карту Xaero, аккаунты, `servers.dat` или пользовательский кэш.

## Авторы и ответственность

Это неофициальная надстройка. GregTech Odyssey и все сторонние моды принадлежат
их авторам. Официальные источники и точные CurseForge ID перечислены в
[`THIRD-PARTY.md`](THIRD-PARTY.md). Проблемы этой редакции следует сообщать в
данный репозиторий, а не авторам Complementary или GregTech Odyssey.
