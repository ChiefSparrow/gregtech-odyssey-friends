import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GtoTLauncherInstaller {
    private static final String PACK_NAME = "GregTech Odyssey — Friends Edition";
    private static final String PACK_VERSION = "1.0.4";
    private static final List<String> PREVIOUS_PACK_VERSIONS =
            Collections.unmodifiableList(Arrays.asList("1.0.3", "1.0.2"));
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION = "47.4.20";
    private static final String FORGE_PROFILE_ID =
            MINECRAFT_VERSION + "-forge-" + FORGE_VERSION;
    private static final String JAVA_RUNTIME_COMPONENT = "java-runtime-delta";
    private static final int GAME_JAVA_MAJOR = 21;
    private static final String MARKER_FILE = "GTO-FRIENDS-INSTALLED.txt";
    private static final String RECOVERY_FILE =
            "GTO-FRIENDS-INSTALL-IN-PROGRESS.txt";
    private static final String USER_AGENT =
            "GTO-Friends-TLauncher-Installer/1.0.4 "
                    + "(https://github.com/ChiefSparrow/gregtech-odyssey-friends)";
    private static final DownloadEntry FORGE_INSTALLER =
            new DownloadEntry(
                    ".gto-runtime/forge-" + MINECRAFT_VERSION + "-"
                            + FORGE_VERSION + "-installer.jar",
                    8835110L,
                    "0EBCF198609F925E0018842A79473EF74FDA78534F86D82F2C0FDB26449C1FA4",
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                            + MINECRAFT_VERSION + "-" + FORGE_VERSION
                            + "/forge-" + MINECRAFT_VERSION + "-"
                            + FORGE_VERSION + "-installer.jar",
                    "официальный установщик Forge " + FORGE_VERSION
            );
    private static final DownloadEntry VANILLA_VERSION_JSON =
            new DownloadEntry(
                    "versions/1.20.1/1.20.1.json",
                    34974L,
                    "584F92FBAE08AD68F5E18610A375A850AF3678158E03F7145EA65DB00060C0B2",
                    "https://piston-meta.mojang.com/v1/packages/"
                            + "8a4e093bfaa91de10c17af807570cdd64468bf67/"
                            + "1.20.1.json",
                    "официальное описание Minecraft 1.20.1"
            );
    private static final DownloadEntry TEMURIN_JAVA_21 =
            new DownloadEntry(
                    ".gto-runtime/OpenJDK21U-jre_x64_windows_hotspot_"
                            + "21.0.11_10.zip",
                    49005708L,
                    "BE26677AAA20B39A62EDCAAB4C8857A8B76673B0F45ABC0B6143B142B62717E4",
                    "https://github.com/adoptium/temurin21-binaries/releases/"
                            + "download/jdk-21.0.11%2B10/"
                            + "OpenJDK21U-jre_x64_windows_hotspot_21.0.11_10.zip",
                    "Eclipse Temurin JRE 21.0.11+10"
            );
    private static final LockedEntry ORIGINAL_FORGE_PROFILE =
            new LockedEntry(
                    "versions/1.20.1-forge-47.4.20/"
                            + "1.20.1-forge-47.4.20.json",
                    16629L,
                    "67E2756069A09F292EE1364702DB212591D35C212AEF14E42D47B6D458CC433C"
            );
    private static final LockedEntry[] FORGE_RUNTIME_LOCK = {
            new LockedEntry(
                    "versions/1.20.1/1.20.1.json",
                    34974L,
                    "584F92FBAE08AD68F5E18610A375A850AF3678158E03F7145EA65DB00060C0B2"
            ),
            new LockedEntry(
                    "versions/1.20.1-forge-47.4.20/"
                            + "1.20.1-forge-47.4.20.json",
                    16727L,
                    "01F9B3EF16826B6848FBCAF1F639124FC3060DB2ADE219B58C8E25A2E206A09B"
            ),
            new LockedEntry(
                    "versions/1.20.1/1.20.1.jar",
                    23028853L,
                    "56B71336D2B4FDFFD197F56595B0DA93E32A946F78F382A299B8F4B92758BB0F"
            ),
            new LockedEntry(
                    "libraries/net/minecraftforge/forge/1.20.1-47.4.20/"
                            + "forge-1.20.1-47.4.20-client.jar",
                    4848917L,
                    "AD9F1DA4F4AE6121C3FD4EC6A67B0E89BB7466D7039879B75D7CB9592C0E5605"
            ),
            new LockedEntry(
                    "libraries/net/minecraftforge/forge/1.20.1-47.4.20/"
                            + "forge-1.20.1-47.4.20-universal.jar",
                    2507935L,
                    "3B203EA6105326B8A6D7BEA8327C2793DBFFBAA8E574C9C6626FE6D787591874"
            )
    };

    private GtoTLauncherInstaller() {
    }

    public static void main(String[] args) {
        System.setProperty("https.protocols", "TLSv1.2");
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (options.nonInteractive) {
            Path target = options.target != null ? options.target : defaultTarget();
            try {
                install(target, new ConsoleProgress());
                System.out.println();
                System.out.println("INSTALLATION PASSED");
                printLaunchInstructions(target);
            } catch (Exception error) {
                error.printStackTrace(System.err);
                System.err.println("INSTALLATION FAILED: " + error.getMessage());
                System.exit(1);
            }
            return;
        }

        final Path requestedTarget =
                options.target != null ? options.target : defaultTarget();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showGui(requestedTarget);
            }
        });
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -jar GTO-TLauncher-Installer.jar "
                        + "[--target <directory>] [--non-interactive]"
        );
    }

    private static void showGui(Path initialTarget) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final JFrame frame = new JFrame(PACK_NAME + " — установщик для TLauncher");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 520));

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("Установка " + PACK_NAME + " " + PACK_VERSION);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        root.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 10, 0);

        JLabel description = new JLabel(
                "<html>Prism не нужен. Установщик сам скачает точные версии "
                        + "модов, проверит SHA-256 и подготовит игровую папку. "
                        + "Права администратора не требуются.</html>"
        );
        center.add(description, constraints);

        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 8, 8);
        center.add(new JLabel("Игровая папка:"), constraints);

        final JTextField targetField =
                new JTextField(initialTarget.toAbsolutePath().normalize().toString());
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        center.add(targetField, constraints);

        JButton browseButton = new JButton("Выбрать…");
        constraints.gridx = 2;
        constraints.weightx = 0.0;
        center.add(browseButton, constraints);

        final JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(700, 280));
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 3;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(4, 0, 8, 0);
        center.add(scrollPane, constraints);

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        constraints.gridy++;
        constraints.weighty = 0.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        center.add(progressBar, constraints);

        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton installButton = new JButton("Установить сборку");
        JButton closeButton = new JButton("Закрыть");
        buttons.add(installButton);
        buttons.add(closeButton);
        root.add(buttons, BorderLayout.SOUTH);

        browseButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(targetField.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Выберите отдельную игровую папку TLauncher");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                targetField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        closeButton.addActionListener(event -> frame.dispose());
        installButton.addActionListener(event -> {
            final Path target = Paths.get(targetField.getText())
                    .toAbsolutePath()
                    .normalize();
            installButton.setEnabled(false);
            browseButton.setEnabled(false);
            targetField.setEnabled(false);
            logArea.setText("");

            SwingWorker<Path, Void> worker = new SwingWorker<Path, Void>() {
                @Override
                protected Path doInBackground() throws Exception {
                    install(target, new SwingProgress(logArea, progressBar));
                    return target;
                }

                @Override
                protected void done() {
                    try {
                        Path installedTarget = get();
                        progressBar.setValue(100);
                        progressBar.setString("Готово");
                        String instructions = launchInstructions(installedTarget);
                        JOptionPane.showMessageDialog(
                                frame,
                                instructions,
                                "Сборка установлена",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        if (Desktop.isDesktopSupported()) {
                            try {
                                Desktop.getDesktop().open(installedTarget.toFile());
                            } catch (IOException ignored) {
                            }
                        }
                    } catch (Exception error) {
                        Throwable cause =
                                error.getCause() != null ? error.getCause() : error;
                        progressBar.setString("Ошибка");
                        JOptionPane.showMessageDialog(
                                frame,
                                cause.getMessage(),
                                "Установка не завершена",
                                JOptionPane.ERROR_MESSAGE
                        );
                        installButton.setEnabled(true);
                        browseButton.setEnabled(true);
                        targetField.setEnabled(true);
                    }
                }
            };
            worker.execute();
        });

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void install(Path target, Progress progress) throws Exception {
        target = target.toAbsolutePath().normalize();
        Path currentJavaHome = Paths.get(System.getProperty("java.home"))
                .toAbsolutePath()
                .normalize();
        Path managedJavaHome = javaRuntimeComponentRoot(target)
                .toAbsolutePath()
                .normalize();
        if (currentJavaHome.startsWith(managedJavaHome)) {
            throw new IOException(
                    "Установщик запущен из Java, которую он должен обновить. "
                            + "Закройте TLauncher и запустите "
                            + "INSTALL-GTO-TLAUNCHER.bat ещё раз; BAT использует "
                            + "отдельную Java TLauncher."
            );
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Не удалось определить родительскую папку: " + target);
        }
        Files.createDirectories(parent);
        Path lockPath = parent.resolve(
                "." + target.getFileName() + ".gto-install.lock"
        );
        boolean acquired = false;
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        )) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException error) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException(
                        "Для этой игровой папки уже запущен другой установщик GTO."
                );
            }
            acquired = true;
            try {
                installLocked(target, parent, progress);
            } finally {
                lock.release();
            }
        } finally {
            if (acquired) {
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException cleanupError) {
                    progress.log("Не удалось удалить файл блокировки: " + lockPath);
                }
            }
        }
    }

    private static void installLocked(
            Path target,
            Path parent,
            Progress progress
    ) throws Exception {
        validateTarget(target);
        boolean repairExisting = hasSupportedMarker(target);

        Path staging = parent.resolve(
                        "." + target.getFileName() + ".gto-installing-"
                                + UUID.randomUUID().toString()
                )
                .toAbsolutePath()
                .normalize();
        if (!staging.getParent().equals(parent)) {
            throw new IOException("Небезопасный путь временной папки: " + staging);
        }
        Files.createDirectories(staging);

        try {
            progress.log("Целевая папка: " + target);
            progress.log("Временная папка: " + staging);
            if (repairExisting) {
                progress.log(
                        "Найдена установленная Friends Edition. "
                                + "Моды повторно скачиваться не будут."
                );
                progress.update(10, "Проверка сборки");
                verifyClient(target);

                progress.update(20, "Установка Java 21");
                extractPayloadFile(staging, "README-TLAUNCHER.txt");
                prepareEasyPackConfig(target, staging, progress);
                prepareVanillaDefaults(target, staging, progress);
                installJava21Runtime(staging, progress);
                verifyJava21Runtime(staging);

                progress.update(58, "Установка Forge");
                installForgeRuntime(staging, progress);
                verifyForgeRuntime(staging);

                progress.update(88, "Копирование исправлений");
                rejectUnsafeLinks(target);
                replaceManagedJavaRuntime(staging, target);
                deleteTree(javaRuntimePlatformRoot(staging));
                copyTree(staging, target);

                progress.update(96, "Финальная проверка");
                verifyClient(target);
                verifyForgeRuntime(target);
                verifyJava21Runtime(target);
                verifyPackConfigurationAfterUpdate(target);
                writeMarker(target);

                progress.update(100, "Готово");
                progress.log("Локальный профиль Forge и Java 21 исправлены.");
                progress.log("Выберите в TLauncher: " + FORGE_PROFILE_ID);
                return;
            }

            progress.log("Распаковка файлов сборки…");
            extractPayload(staging);

            List<DownloadEntry> downloads = loadDownloads();
            progress.log("Нужно получить файлов: " + downloads.size());
            int completed = 0;
            for (DownloadEntry entry : downloads) {
                completed++;
                int percent =
                        5 + (int) Math.floor(completed * 80.0 / downloads.size());
                progress.update(percent, completed + "/" + downloads.size());
                Path destination = safeResolve(staging, entry.destination);
                if (matches(destination, entry.size, entry.sha256)) {
                    progress.log("Уже проверен: " + entry.destination);
                    continue;
                }
                downloadWithRetries(entry, destination, progress);
            }

            progress.update(88, "Проверка");
            progress.log("Полная проверка модов и ресурсов…");
            verifyClient(staging);
            verifyCleanPackConfiguration(staging);
            progress.log("Проверка временной сборки пройдена.");

            progress.update(90, "Установка Java 21");
            installJava21Runtime(staging, progress);
            verifyJava21Runtime(staging);
            progress.log("Приватная Java 21 проверена.");

            progress.update(93, "Установка Forge");
            installForgeRuntime(staging, progress);
            verifyForgeRuntime(staging);
            progress.log("Локальный профиль Forge проверен.");

            progress.update(96, "Копирование");
            Files.createDirectories(target);
            Files.deleteIfExists(target.resolve(MARKER_FILE));
            writeRecoveryMarker(target);
            rejectUnsafeLinks(target);
            replaceManagedJavaRuntime(staging, target);
            deleteTree(javaRuntimePlatformRoot(staging));
            copyTree(staging, target);

            progress.update(98, "Финальная проверка");
            verifyClient(target);
            verifyForgeRuntime(target);
            verifyJava21Runtime(target);
            verifyCleanPackConfiguration(target);
            writeMarker(target);
            Files.deleteIfExists(target.resolve(RECOVERY_FILE));
            progress.log("Финальная проверка пройдена.");

            progress.update(100, "Готово");
            progress.log("Установка завершена.");
            progress.log(
                    "Minecraft " + MINECRAFT_VERSION + ", Forge " + FORGE_VERSION
            );
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException cleanupError) {
                progress.log(
                        "Не удалось удалить временную папку: " + staging
                );
            }
        }
    }

    private static void validateTarget(Path target) throws IOException {
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("Целевой путь не является папкой: " + target);
        }
        if (!Files.exists(target)) {
            return;
        }
        if (hasSupportedMarker(target)) {
            rejectUnsafeLinks(target);
            return;
        }
        if (hasRecoveryMarker(target)) {
            rejectUnsafeLinks(target);
            if (hasExistingWorld(target)) {
                throw new IOException(
                        "В незавершённой установке неожиданно появились миры. "
                                + "Выберите новую пустую папку."
                );
            }
            return;
        }
        rejectUnsafeLinks(target);
        if (hasExistingWorld(target)) {
            throw new IOException(
                    "В выбранной папке уже есть миры Minecraft. "
                            + "Выберите отдельную пустую папку для GTO Friends Easy."
            );
        }
        Path mods = target.resolve("mods");
        if (directoryHasFiles(mods)) {
            throw new IOException(
                    "В выбранной папке уже есть сторонние моды. "
                            + "Выберите пустую папку, чтобы не смешивать сборки: "
                            + target
            );
        }
        Path config = target.resolve("config");
        if (directoryHasFiles(config)) {
            throw new IOException(
                    "В выбранной папке уже есть конфигурация другой сборки. "
                            + "Выберите отдельную пустую папку: " + target
            );
        }
    }

    private static boolean hasSupportedMarker(Path target) {
        if (hasMarkerVersion(target, PACK_VERSION)) {
            return true;
        }
        for (String version : PREVIOUS_PACK_VERSIONS) {
            if (hasMarkerVersion(target, version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMarkerVersion(Path target, String version) {
        Path marker = target.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            boolean baseMarker = lines.contains(PACK_NAME)
                    && lines.contains("pack-version=" + version)
                    && lines.contains("minecraft=" + MINECRAFT_VERSION)
                    && lines.contains("forge=" + FORGE_VERSION)
                    && lines.contains("client-verification=passed");
            return baseMarker
                    && (!PACK_VERSION.equals(version)
                    || (lines.contains("forge-runtime-verification=passed")
                    && lines.contains("java-runtime-verification=passed")
                    && lines.contains("pack-mode-default=GTO-Easy")
                    && lines.contains("vanilla-difficulty-default=NORMAL")));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean hasRecoveryMarker(Path target) {
        Path marker = target.resolve(RECOVERY_FILE);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            return lines.contains(PACK_NAME)
                    && lines.contains("target-version=" + PACK_VERSION)
                    && lines.contains("status=install-in-progress");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean directoryHasFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            return stream.anyMatch(Files::isRegularFile);
        }
    }

    private static Path defaultTarget() {
        String appData = System.getenv("APPDATA");
        Path root = appData != null && !appData.trim().isEmpty()
                ? Paths.get(appData)
                : Paths.get(System.getProperty("user.home"));
        Path normalMinecraft = root.resolve(".minecraft");
        Path currentPack = root.resolve(".gto-friends-" + PACK_VERSION);
        if (hasSupportedMarker(currentPack) || hasRecoveryMarker(currentPack)) {
            return currentPack;
        }
        for (String version : PREVIOUS_PACK_VERSIONS) {
            Path previousPack = root.resolve(".gto-friends-" + version);
            if (hasSupportedMarker(previousPack)) {
                return previousPack;
            }
        }
        if (hasSupportedMarker(normalMinecraft) || hasRecoveryMarker(normalMinecraft)) {
            return normalMinecraft;
        }
        try {
            if (!directoryHasFiles(normalMinecraft.resolve("mods"))
                    && !directoryHasFiles(normalMinecraft.resolve("config"))
                    && !hasExistingWorld(normalMinecraft)) {
                return normalMinecraft;
            }
        } catch (IOException ignored) {
        }
        return currentPack;
    }

    private static void prepareEasyPackConfig(
            Path installedTarget,
            Path staging,
            Progress progress
    ) throws Exception {
        if (hasExistingWorld(installedTarget)) {
            Path existingConfig =
                    installedTarget.resolve("config").resolve("gtocore.yaml");
            try {
                requireGtoEasy(existingConfig);
            } catch (IOException error) {
                throw new IOException(
                        "В выбранной папке найден мир, созданный не в режиме GTO Easy. "
                                + "Его нельзя безопасно понизить. Укажите новую пустую "
                                + "игровую папку для Friends Edition Easy; старая папка "
                                + "не изменена.",
                        error
                );
            }
            progress.log(
                    "Найдены существующие миры GTO Easy. Их режим сохранён без изменений."
            );
            return;
        }

        Path installedConfig = installedTarget.resolve("config").resolve("gtocore.yaml");
        if (!Files.isRegularFile(installedConfig)) {
            extractPayloadFile(staging, "config/gtocore.yaml");
            progress.log("Установлен режим сборки GTO: Easy.");
            return;
        }

        String current = new String(
                Files.readAllBytes(installedConfig),
                StandardCharsets.UTF_8
        );
        String updated = replaceGtoDifficultyWithEasy(current);
        Path stagedConfig = staging.resolve("config").resolve("gtocore.yaml");
        if (updated == null) {
            throw new IOException(
                    "Не удалось безопасно изменить только gamePlay.difficulty в "
                            + installedConfig + ". Исправьте или удалите этот файл "
                            + "только после резервной копии."
            );
        } else {
            writeUtf8Atomically(stagedConfig, updated);
            progress.log(
                    "Режим сборки GTO изменён на Easy; остальные настройки сохранены."
            );
        }
        requireGtoEasy(stagedConfig);
    }

    private static void prepareVanillaDefaults(
            Path installedTarget,
            Path staging,
            Progress progress
    ) throws Exception {
        Path installedConfig = installedTarget
                .resolve("config")
                .resolve("defaultoptions-common.toml");
        if (!Files.isRegularFile(installedConfig)) {
            extractPayloadFile(staging, "config/defaultoptions-common.toml");
            requireVanillaNormalDefaults(
                    staging.resolve("config").resolve("defaultoptions-common.toml")
            );
            progress.log("Установлена ванильная сложность по умолчанию: Normal.");
            return;
        }

        String current = new String(
                Files.readAllBytes(installedConfig),
                StandardCharsets.UTF_8
        );
        String withDifficulty = replaceActiveSetting(
                current,
                "defaultDifficulty",
                "\"NORMAL\""
        );
        String updated = withDifficulty != null
                ? replaceActiveSetting(withDifficulty, "lockDifficulty", "false")
                : null;
        if (updated == null) {
            throw new IOException(
                    "Не удалось безопасно обновить defaultoptions-common.toml. "
                            + "Исправьте дублирующиеся или отсутствующие параметры "
                            + "defaultDifficulty/lockDifficulty."
            );
        }

        Path stagedConfig = staging
                .resolve("config")
                .resolve("defaultoptions-common.toml");
        writeUtf8Atomically(stagedConfig, updated);
        requireVanillaNormalDefaults(stagedConfig);
        progress.log("Ванильная сложность новых миров оставлена Normal.");
    }

    private static String replaceActiveSetting(
            String text,
            String key,
            String expectedValue
    ) {
        int position = 0;
        int matchStart = -1;
        int matchEnd = -1;
        String replacement = null;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int separator = line.indexOf('=');
                if (separator >= 0
                        && line.substring(0, separator).trim().equals(key)) {
                    if (matchStart >= 0) {
                        return null;
                    }
                    String suffix = line.substring(separator + 1);
                    int comment = suffix.indexOf('#');
                    String inlineComment = comment >= 0
                            ? " " + suffix.substring(comment).trim()
                            : "";
                    matchStart = position;
                    matchEnd = contentEnd;
                    replacement = line.substring(0, separator + 1)
                            + " " + expectedValue + inlineComment;
                }
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        if (matchStart < 0 || replacement == null) {
            return null;
        }
        return text.substring(0, matchStart)
                + replacement
                + text.substring(matchEnd);
    }

    private static boolean hasExistingWorld(Path root) throws IOException {
        Path saves = root.resolve("saves");
        if (!Files.isDirectory(saves)) {
            return false;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(saves, 2)) {
            return paths.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                String name = path.getFileName().toString();
                return name.equals("level.dat") || name.equals("level.dat_old");
            });
        }
    }

    private static String replaceGtoDifficultyWithEasy(String text) {
        int directChildIndentation = directChildIndentation(text, "gamePlay");
        if (directChildIndentation < 1) {
            return null;
        }
        int position = 0;
        boolean inGamePlay = false;
        int matchStart = -1;
        int matchEnd = -1;
        String replacement = null;

        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indentation = 0;
            while (indentation < line.length()) {
                char value = line.charAt(indentation);
                if (value != ' ' && value != '\t') {
                    break;
                }
                indentation++;
            }

            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indentation == 0) {
                    inGamePlay = trimmed.equals("gamePlay:");
                } else if (inGamePlay
                        && indentation == directChildIndentation
                        && trimmed.startsWith("difficulty:")) {
                    if (!trimmed.substring("difficulty:".length()).trim().isEmpty()) {
                        if (matchStart >= 0) {
                            return null;
                        }
                        matchStart = position;
                        matchEnd = contentEnd;
                        replacement =
                                line.substring(0, indentation) + "difficulty: Easy";
                    }
                }
            }

            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }

        if (matchStart < 0 || replacement == null) {
            return null;
        }
        return text.substring(0, matchStart)
                + replacement
                + text.substring(matchEnd);
    }

    private static void verifyCleanPackConfiguration(Path root) throws IOException {
        requireGtoEasy(root.resolve("config").resolve("gtocore.yaml"));
        requireVanillaNormalDefaults(
                root.resolve("config").resolve("defaultoptions-common.toml")
        );
    }

    private static void verifyPackConfigurationAfterUpdate(Path root)
            throws IOException {
        requireGtoEasy(root.resolve("config").resolve("gtocore.yaml"));
        requireVanillaNormalDefaults(
                root.resolve("config").resolve("defaultoptions-common.toml")
        );
    }

    private static void requireGtoEasy(Path config) throws IOException {
        if (!Files.isRegularFile(config)) {
            throw new IOException("Отсутствует config/gtocore.yaml.");
        }
        String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        if (!hasSingleNestedSetting(text, "gamePlay", "difficulty", "Easy")) {
            throw new IOException("Режим сборки GTO должен быть Easy.");
        }
    }

    private static void requireVanillaNormalDefaults(Path config)
            throws IOException {
        if (!Files.isRegularFile(config)) {
            throw new IOException("Отсутствует config/defaultoptions-common.toml.");
        }
        String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        if (!hasSingleActiveSetting(text, "defaultDifficulty", "\"NORMAL\"")
                || !hasSingleActiveSetting(text, "lockDifficulty", "false")) {
            throw new IOException(
                    "Ванильная сложность должна быть NORMAL и не заблокирована."
            );
        }
    }

    private static boolean hasSingleNestedSetting(
            String text,
            String section,
            String key,
            String expectedValue
    ) {
        int directChildIndentation = directChildIndentation(text, section);
        if (directChildIndentation < 1) {
            return false;
        }
        int position = 0;
        boolean inSection = false;
        int matches = 0;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indentation = line.length() - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indentation == 0) {
                    inSection = trimmed.equals(section + ":");
                } else if (inSection
                        && indentation == directChildIndentation
                        && trimmed.startsWith(key + ":")) {
                    String value = trimmed.substring((key + ":").length()).trim();
                    int comment = value.indexOf('#');
                    if (comment >= 0) {
                        value = value.substring(0, comment).trim();
                    }
                    if (value.equals(expectedValue)) {
                        matches++;
                    } else {
                        return false;
                    }
                }
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        return matches == 1;
    }

    private static int directChildIndentation(String text, String section) {
        int position = 0;
        boolean inSection = false;
        int sectionCount = 0;
        int minimumIndentation = Integer.MAX_VALUE;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indentation = line.length() - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indentation == 0) {
                    inSection = trimmed.equals(section + ":");
                    if (inSection) {
                        sectionCount++;
                    }
                } else if (inSection) {
                    minimumIndentation = Math.min(minimumIndentation, indentation);
                }
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        return sectionCount == 1 && minimumIndentation != Integer.MAX_VALUE
                ? minimumIndentation
                : -1;
    }

    private static String stripLeadingWhitespace(String text) {
        int index = 0;
        while (index < text.length()) {
            char value = text.charAt(index);
            if (value != ' ' && value != '\t') {
                break;
            }
            index++;
        }
        return text.substring(index);
    }

    private static boolean hasSingleActiveSetting(
            String text,
            String key,
            String expectedValue
    ) {
        int matches = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0
                    || !trimmed.substring(0, separator).trim().equals(key)) {
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            int comment = value.indexOf('#');
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            if (!value.equals(expectedValue)) {
                return false;
            }
            matches++;
        }
        return matches == 1;
    }

    private static void writeUtf8Atomically(Path destination, String text)
            throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-" + UUID.randomUUID().toString()
        );
        try {
            Files.write(
                    temporary,
                    text.getBytes(StandardCharsets.UTF_8)
            );
            moveAtomically(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void extractPayload(Path staging) throws Exception {
        CodeSource source =
                GtoTLauncherInstaller.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IOException("Не удалось определить файл установщика.");
        }
        URI location = source.getLocation().toURI();
        Path jarPath = Paths.get(location);
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "Установщик необходимо запускать из собранного JAR: " + jarPath
            );
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("payload/")) {
                    continue;
                }
                String relative = entry.getName().substring("payload/".length());
                if (relative.isEmpty()) {
                    continue;
                }
                Path destination = safeResolve(staging, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input =
                             new BufferedInputStream(jar.getInputStream(entry))) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void extractPayloadFile(Path staging, String relative) throws IOException {
        Path destination = safeResolve(staging, relative);
        String resourceName = "/payload/" + relative.replace('\\', '/');
        InputStream stream = GtoTLauncherInstaller.class.getResourceAsStream(resourceName);
        if (stream == null) {
            throw new IOException("В установщике отсутствует файл: " + resourceName);
        }
        Files.createDirectories(destination.getParent());
        try (InputStream input = new BufferedInputStream(stream)) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<DownloadEntry> loadDownloads() throws IOException {
        List<DownloadEntry> entries = new ArrayList<DownloadEntry>();
        try (BufferedReader reader = resourceReader("/downloads.tsv")) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("destination\t")) {
                throw new IOException("Повреждён downloads.tsv.");
            }
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", 7);
                if (fields.length != 7) {
                    throw new IOException("Некорректная строка downloads.tsv: " + line);
                }
                entries.add(
                        new DownloadEntry(
                                fields[0],
                                Long.parseLong(fields[1]),
                                fields[2].toUpperCase(Locale.ROOT),
                                fields[3],
                                fields[6]
                        )
                );
            }
        }
        if (entries.size() != 171) {
            throw new IOException(
                    "Ожидалась 171 загрузка, найдено: " + entries.size()
            );
        }
        return entries;
    }

    private static List<LockedEntry> loadClientLock() throws IOException {
        List<LockedEntry> entries = new ArrayList<LockedEntry>();
        try (BufferedReader reader = resourceReader("/client-lock.tsv")) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("path\t")) {
                throw new IOException("Повреждён client-lock.tsv.");
            }
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) {
                    throw new IOException("Некорректная строка client-lock.tsv: " + line);
                }
                entries.add(
                        new LockedEntry(
                                fields[0],
                                Long.parseLong(fields[1]),
                                fields[2].toUpperCase(Locale.ROOT)
                        )
                );
            }
        }
        if (entries.size() != 177) {
            throw new IOException(
                    "Ожидалось 177 файлов в lock, найдено: " + entries.size()
            );
        }
        return entries;
    }

    private static BufferedReader resourceReader(String name) throws IOException {
        InputStream stream = GtoTLauncherInstaller.class.getResourceAsStream(name);
        if (stream == null) {
            throw new IOException("В установщике отсутствует ресурс: " + name);
        }
        return new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );
    }

    private static void downloadWithRetries(
            DownloadEntry entry,
            Path destination,
            Progress progress
    ) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                progress.log(
                        "Скачивание: " + entry.name
                                + (attempt > 1 ? " (попытка " + attempt + ")" : "")
                );
                download(entry, destination);
                if (!matches(destination, entry.size, entry.sha256)) {
                    throw new IOException(
                            "Контрольная сумма не совпала: " + entry.destination
                    );
                }
                return;
            } catch (Exception error) {
                lastError = error;
                Files.deleteIfExists(destination);
                Files.deleteIfExists(partPath(destination));
                if (attempt < 4) {
                    Thread.sleep(1000L * attempt);
                }
            }
        }
        throw new IOException(
                "Не удалось скачать " + entry.name + ": "
                        + (lastError != null ? lastError.getMessage() : "неизвестная ошибка"),
                lastError
        );
    }

    private static void download(DownloadEntry entry, Path destination)
            throws Exception {
        Files.createDirectories(destination.getParent());
        Path partial = partPath(destination);
        Files.deleteIfExists(partial);

        URL current = new URL(entry.url);
        HttpURLConnection connection = null;
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) {
                throw new IOException(
                        "Разрешены только HTTPS-загрузки: " + current
                );
            }
            String host = current.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("edge.forgecdn.net")
                    && !host.endsWith(".forgecdn.net")
                    && !host.equals("maven.minecraftforge.net")
                    && !host.equals("files.minecraftforge.net")
                    && !host.equals("piston-meta.mojang.com")
                    && !host.equals("github.com")
                    && !host.equals("release-assets.githubusercontent.com")) {
                throw new IOException(
                        "Запрещённый узел загрузки: " + current.getHost()
                );
            }
            connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/octet-stream,*/*");
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("Перенаправление без адреса: " + entry.url);
                }
                current = new URL(current, location);
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + status + " для " + entry.url);
            }
            break;
        }
        if (connection == null
                || connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Слишком много перенаправлений: " + entry.url);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input =
                     new BufferedInputStream(connection.getInputStream());
             OutputStream output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                count += read;
                if (count > entry.size) {
                    throw new IOException(
                            "Загрузка превысила ожидаемый размер: "
                                    + entry.destination
                    );
                }
            }
        } finally {
            connection.disconnect();
        }

        String actualHash = toHex(digest.digest());
        if (count != entry.size) {
            Files.deleteIfExists(partial);
            throw new IOException(
                    "Размер " + entry.destination + ": ожидалось "
                            + entry.size + ", получено " + count
            );
        }
        if (!actualHash.equals(entry.sha256)) {
            Files.deleteIfExists(partial);
            throw new IOException(
                    "SHA-256 " + entry.destination + ": ожидалось "
                            + entry.sha256 + ", получено " + actualHash
            );
        }
        try {
            Files.move(
                    partial,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    partial,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static Path partPath(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".part");
    }

    private static boolean matches(Path file, long size, String sha256)
            throws Exception {
        return Files.isRegularFile(file)
                && Files.size(file) == size
                && sha256(file).equals(sha256);
    }

    private static String sha256(Path file) throws Exception {
        return digestFile(file, "SHA-256");
    }

    private static String sha1(Path file) throws Exception {
        return digestFile(file, "SHA-1");
    }

    private static String digestFile(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input =
                     new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private static void verifyClient(Path root) throws Exception {
        List<LockedEntry> locked = loadClientLock();
        Set<String> expectedMods = new HashSet<String>();
        for (LockedEntry entry : locked) {
            Path file = safeResolve(root, entry.path);
            if (!matches(file, entry.size, entry.sha256)) {
                throw new IOException("Не совпадает с эталоном: " + entry.path);
            }
            if (entry.path.startsWith("mods/")) {
                expectedMods.add(entry.path.substring("mods/".length())
                        .toLowerCase(Locale.ROOT));
            }
        }

        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods)) {
            throw new IOException("Отсутствует папка mods.");
        }
        try (java.util.stream.Stream<Path> files = Files.list(mods)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!expectedMods.contains(name)) {
                    throw new IOException(
                            "Обнаружен лишний мод: mods/" + file.getFileName()
                    );
                }
            }
        }
    }

    private static void installForgeRuntime(Path root, Progress progress)
            throws Exception {
        Files.createDirectories(root);
        Path profiles = root.resolve("launcher_profiles.json");
        if (Files.exists(profiles)) {
            throw new IOException(
                    "Во временной папке неожиданно найден launcher_profiles.json."
            );
        }

        Path runtimeDirectory = root.resolve(".gto-runtime");
        Files.createDirectories(runtimeDirectory);
        Path forgeTemp = runtimeDirectory.resolve("tmp");
        Files.createDirectories(forgeTemp);
        Path installer = safeResolve(root, FORGE_INSTALLER.destination);
        try {
            writeEmptyLauncherProfiles(profiles);
            progress.log(
                    "Получение официального Forge " + FORGE_VERSION
                            + " с maven.minecraftforge.net…"
            );
            downloadWithRetries(FORGE_INSTALLER, installer, progress);

            progress.log(
                    "Создание локального профиля " + FORGE_PROFILE_ID
                            + ". Это может занять несколько минут…"
            );
            runForgeInstaller(root, installer, forgeTemp);
        } finally {
            Files.deleteIfExists(profiles);
            if (Files.isDirectory(runtimeDirectory)) {
                deleteTree(runtimeDirectory);
            }
        }

        progress.log("Фиксация официального описания Minecraft 1.20.1…");
        Path vanillaJson = safeResolve(root, VANILLA_VERSION_JSON.destination);
        downloadWithRetries(VANILLA_VERSION_JSON, vanillaJson, progress);
        patchForgeProfileForJava21(root);
    }

    private static void patchForgeProfileForJava21(Path root) throws Exception {
        Path profile = safeResolve(root, ORIGINAL_FORGE_PROFILE.path);
        LockedEntry patchedProfile = FORGE_RUNTIME_LOCK[1];
        if (matches(profile, patchedProfile.size, patchedProfile.sha256)) {
            return;
        }
        if (!matches(
                profile,
                ORIGINAL_FORGE_PROFILE.size,
                ORIGINAL_FORGE_PROFILE.sha256
        )) {
            throw new IOException(
                    "Исходный локальный профиль Forge не совпал с проверенным эталоном."
            );
        }

        String text = new String(
                Files.readAllBytes(profile),
                StandardCharsets.UTF_8
        );
        String needle = "    \"inheritsFrom\": \"" + MINECRAFT_VERSION + "\",";
        String replacement = needle + "\n"
                + "    \"javaVersion\": {\n"
                + "        \"component\": \"" + JAVA_RUNTIME_COMPONENT + "\",\n"
                + "        \"majorVersion\": " + GAME_JAVA_MAJOR + "\n"
                + "    },";
        if (countOccurrences(text, needle) != 1) {
            throw new IOException(
                    "Не удалось однозначно закрепить Java 21 в профиле Forge."
            );
        }

        Path temporary = profile.resolveSibling(profile.getFileName() + ".java21.tmp");
        Files.deleteIfExists(temporary);
        try {
            Files.write(
                    temporary,
                    text.replace(needle, replacement).getBytes(StandardCharsets.UTF_8)
            );
            if (!matches(
                    temporary,
                    patchedProfile.size,
                    patchedProfile.sha256
            )) {
                throw new IOException(
                        "Патч Java 21 создал неожиданный профиль Forge."
                );
            }
            moveAtomically(temporary, profile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static void writeEmptyLauncherProfiles(Path profiles)
            throws IOException {
        Files.write(
                profiles,
                Collections.singletonList(
                        "{\"profiles\":{},\"settings\":{},\"version\":3}"
                ),
                StandardCharsets.UTF_8
        );
    }

    private static void runForgeInstaller(
            Path root,
            Path installer,
            Path forgeTemp
    )
            throws Exception {
        Path javaExecutable = javaRuntimeComponentRoot(root)
                .resolve("bin")
                .resolve("java.exe");
        if (!isJava21X64(javaExecutable)) {
            throw new IOException(
                    "Forge должен устанавливаться через проверенную Java 21: "
                            + javaExecutable
            );
        }
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Djava.net.preferIPv4Stack=true",
                "-Djava.io.tmpdir=" + forgeTemp.toString(),
                "-jar",
                installer.toString(),
                "--installClient",
                root.toString()
        );
        builder.directory(installer.getParent().toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        List<String> tail = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                tail.add(line);
                if (tail.size() > 100) {
                    tail.remove(0);
                }
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw error;
        }
        if (exitCode != 0) {
            StringBuilder details = new StringBuilder();
            for (String line : tail) {
                details.append(System.lineSeparator()).append(line);
            }
            throw new IOException(
                    "Официальный установщик Forge завершился с кодом "
                            + exitCode + "." + details
            );
        }
    }

    private static void installJava21Runtime(
            Path root,
            Progress progress
    ) throws Exception {
        Path currentHome = Paths.get(
                System.getProperty("java.home")
        ).toAbsolutePath().normalize();
        Path currentJava = currentHome.resolve("bin").resolve("java.exe");
        if (isJava21X64(currentJava)) {
            progress.log(
                    "Найдена подходящая Java 21. Создаётся приватная копия для GTO…"
            );
            installJavaHome(root, currentHome);
            return;
        }

        progress.log(
                "Java 21 не найдена. Скачивание проверенной Eclipse Temurin "
                        + "21.0.11+10…"
        );
        Path runtimeDirectory = root.resolve(".gto-runtime");
        Path archive = safeResolve(root, TEMURIN_JAVA_21.destination);
        Path extraction = runtimeDirectory.resolve("java21-extract");
        try {
            Files.createDirectories(runtimeDirectory);
            downloadWithRetries(TEMURIN_JAVA_21, archive, progress);
            extractJavaArchive(archive, extraction);
            Path javaHome = findExtractedJavaHome(extraction);
            if (javaHome == null) {
                throw new IOException(
                        "В архиве Eclipse Temurin не найдена Java 21 x64."
                );
            }
            installJavaHome(root, javaHome);
        } finally {
            if (Files.isDirectory(runtimeDirectory)) {
                deleteTree(runtimeDirectory);
            }
        }
    }

    private static void installJavaHome(Path root, Path javaHome)
            throws Exception {
        Path platformRoot = javaRuntimePlatformRoot(root);
        if (Files.exists(platformRoot)) {
            deleteTree(platformRoot);
        }
        Path componentRoot = javaRuntimeComponentRoot(root);
        Files.createDirectories(componentRoot);
        copyTree(javaHome, componentRoot);

        Files.deleteIfExists(platformRoot.resolve(".version"));
        writeTLauncherJavaManifest(root);
        verifyJava21Runtime(root);
    }

    private static Path javaRuntimePlatformRoot(Path root) {
        return root.resolve("runtime")
                .resolve(JAVA_RUNTIME_COMPONENT)
                .resolve("windows");
    }

    private static Path javaRuntimeComponentRoot(Path root) {
        return javaRuntimePlatformRoot(root).resolve(JAVA_RUNTIME_COMPONENT);
    }

    private static Path javaRuntimeManifest(Path root) {
        return javaRuntimePlatformRoot(root)
                .resolve(JAVA_RUNTIME_COMPONENT + ".sha1");
    }

    private static void extractJavaArchive(Path archive, Path extraction)
            throws Exception {
        if (Files.exists(extraction)) {
            deleteTree(extraction);
        }
        Files.createDirectories(extraction);
        int entries = 0;
        long extractedBytes = 0L;
        Set<String> extractedPaths = new HashSet<String>();
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive))
        )) {
            ZipEntry entry;
            byte[] buffer = new byte[1024 * 128];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 10000) {
                    throw new IOException("Слишком много файлов в архиве Java 21.");
                }
                String relative = entry.getName().replace('\\', '/');
                if (relative.isEmpty()
                        || !isSafeWindowsArchivePath(relative)) {
                    throw new IOException(
                            "Небезопасный путь в архиве Java 21: " + entry.getName()
                    );
                }
                Path destination = safeResolve(extraction, relative);
                String normalizedKey = extraction.relativize(destination)
                        .toString()
                        .toLowerCase(Locale.ROOT);
                if (!extractedPaths.add(normalizedKey)) {
                    throw new IOException(
                            "Повторяющийся путь в архиве Java 21: " + entry.getName()
                    );
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    zip.closeEntry();
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(destination)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        extractedBytes += read;
                        if (extractedBytes > 450L * 1024L * 1024L) {
                            throw new IOException(
                                    "Распакованный архив Java 21 превысил безопасный размер."
                            );
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (entries < 50) {
            throw new IOException("Архив Java 21 выглядит неполным.");
        }
    }

    private static boolean isSafeWindowsArchivePath(String relative) {
        if (relative.startsWith("/")
                || relative.startsWith("\\")
                || relative.contains(":")) {
            return false;
        }
        String[] segments = relative.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.endsWith(".")
                    || segment.endsWith(" ")) {
                return false;
            }
            String base = segment;
            int extension = base.indexOf('.');
            if (extension >= 0) {
                base = base.substring(0, extension);
            }
            String upper = base.toUpperCase(Locale.ROOT);
            if (upper.equals("CON")
                    || upper.equals("PRN")
                    || upper.equals("AUX")
                    || upper.equals("NUL")
                    || upper.matches("COM[1-9]")
                    || upper.matches("LPT[1-9]")) {
                return false;
            }
        }
        return true;
    }

    private static Path findExtractedJavaHome(Path extraction)
            throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> paths = Files.walk(extraction, 4)) {
            paths.filter(path ->
                            Files.isRegularFile(path)
                                    && path.getFileName().toString().equalsIgnoreCase(
                                    "java.exe"
                            )
                                    && path.getParent() != null
                                    && path.getParent().getFileName().toString()
                                    .equalsIgnoreCase("bin")
                    )
                    .forEach(path -> candidates.add(path.getParent().getParent()));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(
                    candidate.resolve("bin").resolve("java.exe")
            )
                    && Files.isRegularFile(
                    candidate.resolve("bin").resolve("javaw.exe")
            )) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isJava21X64(Path javaExecutable) {
        return java21ProbeError(javaExecutable) == null;
    }

    private static String java21ProbeError(Path javaExecutable) {
        if (!Files.isRegularFile(javaExecutable)) {
            return "файл java.exe отсутствует";
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    javaExecutable.toString(),
                    "-XshowSettings:properties",
                    "-version"
            );
            builder.redirectErrorStream(true);
            process = builder.start();
            final Process runningProcess = process;
            final StringBuilder output = new StringBuilder();
            final boolean[] tooLarge = new boolean[]{false};
            final IOException[] readError = new IOException[]{null};
            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    runningProcess.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (output.length() + line.length() < 128 * 1024) {
                                output.append(line).append('\n');
                            } else {
                                tooLarge[0] = true;
                            }
                        }
                    } catch (IOException error) {
                        readError[0] = error;
                    }
                }
            }, "gto-java21-probe");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                readerThread.join(2000L);
                return "проверка java.exe превысила 15 секунд";
            }
            readerThread.join(2000L);
            if (readerThread.isAlive()) {
                return "не удалось прочитать диагностический вывод java.exe";
            }
            if (readError[0] != null) {
                return "не удалось прочитать java.exe: " + readError[0].getMessage();
            }
            if (tooLarge[0]) {
                return "java.exe вернула слишком большой диагностический вывод";
            }
            int exitCode = process.exitValue();
            String details = output.toString();
            if (exitCode != 0) {
                return "java.exe завершилась с кодом " + exitCode;
            }
            if (!details.matches(
                    "(?s).*java\\.specification\\.version\\s*=\\s*21(?:\\s|$).*"
            )) {
                return "java.exe не подтвердила specification version 21";
            }
            if (!details.matches(
                    "(?s).*sun\\.arch\\.data\\.model\\s*=\\s*64(?:\\s|$).*"
            )) {
                return "java.exe не подтвердила 64-битную архитектуру";
            }
            return null;
        } catch (Exception error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void writeTLauncherJavaManifest(Path root)
            throws Exception {
        Path componentRoot = javaRuntimeComponentRoot(root);
        List<Path> files = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> paths = Files.walk(componentRoot)) {
            paths.filter(Files::isRegularFile).forEach(files::add);
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return manifestRelative(componentRoot, left).compareTo(
                        manifestRelative(componentRoot, right)
                );
            }
        });
        if (files.size() < 50) {
            throw new IOException("Приватная Java 21 выглядит неполной.");
        }

        Path manifest = javaRuntimeManifest(root);
        Files.createDirectories(manifest.getParent());
        Path temporary = manifest.resolveSibling(
                manifest.getFileName() + ".tmp-" + UUID.randomUUID().toString()
        );
        try (BufferedWriter writer =
                     Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                writer.write(manifestRelative(componentRoot, file));
                writer.write(" /#// ");
                writer.write(sha1(file).toLowerCase(Locale.ROOT));
                writer.write(" 0");
                writer.newLine();
            }
        }
        try {
            moveAtomically(temporary, manifest);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String manifestRelative(Path root, Path file) {
        return root.relativize(file).toString();
    }

    private static void verifyJava21Runtime(Path root) throws Exception {
        Path componentRoot = javaRuntimeComponentRoot(root);
        Path java = componentRoot.resolve("bin").resolve("java.exe");
        Path javaw = componentRoot.resolve("bin").resolve("javaw.exe");
        String probeError = java21ProbeError(java);
        if (probeError != null || !Files.isRegularFile(javaw)) {
            throw new IOException(
                    "Приватная Java должна быть 64-битной Java 21: "
                            + (probeError != null
                            ? probeError
                            : "файл javaw.exe отсутствует")
            );
        }

        Path manifest = javaRuntimeManifest(root);
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Отсутствует манифест приватной Java 21.");
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() < 50) {
            throw new IOException("Манифест приватной Java 21 неполон.");
        }
        Set<String> expectedFiles = new HashSet<String>();
        for (String line : lines) {
            String[] fields = line.split("/#// ", -1);
            String relative = fields.length == 2 ? fields[0].trim() : "";
            String[] hashFields = fields.length == 2
                    ? fields[1].trim().split("\\s+")
                    : new String[0];
            if (fields.length != 2
                    || relative.isEmpty()
                    || !fields[0].equals(relative + " ")
                    || hashFields.length != 2
                    || !hashFields[0].matches("[a-f0-9]{40}")
                    || !hashFields[1].equals("0")
                    || !expectedFiles.add(relative.toLowerCase(Locale.ROOT))) {
                throw new IOException("Повреждён манифест приватной Java 21.");
            }
            Path file = safeResolve(componentRoot, relative);
            if (!Files.isRegularFile(file)
                    || !sha1(file).equalsIgnoreCase(hashFields[0])) {
                throw new IOException(
                        "Не совпал файл приватной Java 21: " + relative
                );
            }
        }
        try (java.util.stream.Stream<Path> files = Files.walk(componentRoot)) {
            long actualFiles = files.filter(Files::isRegularFile).count();
            if (actualFiles != expectedFiles.size()) {
                throw new IOException(
                        "В приватной Java 21 обнаружены лишние или пропущенные файлы."
                );
            }
        }
    }

    private static void verifyForgeRuntime(Path root) throws Exception {
        for (LockedEntry entry : FORGE_RUNTIME_LOCK) {
            Path file = safeResolve(root, entry.path);
            if (!matches(file, entry.size, entry.sha256)) {
                throw new IOException(
                        "Не совпадает файл локального Forge: " + entry.path
                );
            }
        }

        Path profile = safeResolve(
                root,
                "versions/" + FORGE_PROFILE_ID + "/"
                        + FORGE_PROFILE_ID + ".json"
        );
        String profileText = new String(
                Files.readAllBytes(profile),
                StandardCharsets.UTF_8
        );
        if (!profileText.contains("\"id\": \"" + FORGE_PROFILE_ID + "\"")
                || !profileText.contains(
                        "\"inheritsFrom\": \"" + MINECRAFT_VERSION + "\""
                )
                || !profileText.contains(
                        "\"component\": \"" + JAVA_RUNTIME_COMPONENT + "\""
                )
                || !profileText.contains(
                        "\"majorVersion\": " + GAME_JAVA_MAJOR
                )
                || profileText.contains("default_user_jvm")) {
            throw new IOException(
                    "Некорректное описание локального профиля Forge."
            );
        }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        String normalizedRelative = relative.replace('/', File.separatorChar);
        Path result = root.resolve(normalizedRelative)
                .toAbsolutePath()
                .normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("Небезопасный путь в манифесте: " + relative);
        }
        return result;
    }

    private static void rejectUnsafeLinks(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        final Path realRoot = normalizedRoot.toRealPath();
        try (java.util.stream.Stream<Path> paths = Files.walk(normalizedRoot)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException(
                            "Символические ссылки и точки повторного анализа "
                                    + "запрещены в игровой папке: " + path
                    );
                }
                Path real = path.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new IOException(
                            "Путь выходит за пределы игровой папки: " + path
                    );
                }
            }
        }
    }

    private static void replaceManagedJavaRuntime(Path staging, Path target)
            throws Exception {
        Path source = javaRuntimePlatformRoot(staging);
        Path destination = javaRuntimePlatformRoot(target);
        if (!Files.isDirectory(source)) {
            throw new IOException("Во временной папке отсутствует Java 21.");
        }
        Files.createDirectories(destination.getParent());
        Path backup = destination.resolveSibling(
                destination.getFileName() + ".gto-backup-"
                        + UUID.randomUUID().toString()
        );
        boolean hadPrevious = Files.exists(destination);
        if (hadPrevious) {
            moveAtomically(destination, backup);
        }
        try {
            copyTree(source, destination);
            verifyJava21Runtime(target);
        } catch (Exception error) {
            try {
                if (Files.exists(destination)) {
                    deleteTree(destination);
                }
                if (hadPrevious && Files.exists(backup)) {
                    moveAtomically(backup, destination);
                }
            } catch (Exception rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
        if (hadPrevious && Files.exists(backup)) {
            try {
                deleteTree(backup);
            } catch (IOException ignored) {
                // The verified new runtime is already committed. A locked old
                // backup is safer to leave behind than to roll back to a
                // partially deleted runtime.
            }
        }
    }

    private static void moveAtomically(Path source, Path destination)
            throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void copyTree(final Path source, final Path target)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                Path relative = source.relativize(directory);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Path temporary = destination.resolveSibling(
                        destination.getFileName() + ".gto-copy-"
                                + UUID.randomUUID().toString() + ".tmp"
                );
                try {
                    Files.copy(
                            file,
                            temporary,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES
                    );
                    moveAtomically(temporary, destination);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException error
            ) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeMarker(Path target) throws IOException {
        Path marker = target.resolve(MARKER_FILE);
        Path temporaryMarker = target.resolve(MARKER_FILE + ".tmp");
        Files.deleteIfExists(temporaryMarker);
        try (BufferedWriter writer =
                     Files.newBufferedWriter(temporaryMarker, StandardCharsets.UTF_8)) {
            writer.write(PACK_NAME);
            writer.newLine();
            writer.write("pack-version=" + PACK_VERSION);
            writer.newLine();
            writer.write("minecraft=" + MINECRAFT_VERSION);
            writer.newLine();
            writer.write("forge=" + FORGE_VERSION);
            writer.newLine();
            writer.write("forge-profile=" + FORGE_PROFILE_ID);
            writer.newLine();
            writer.write("client-verification=passed");
            writer.newLine();
            writer.write("forge-runtime-verification=passed");
            writer.newLine();
            writer.write("java-runtime=" + JAVA_RUNTIME_COMPONENT + "-"
                    + GAME_JAVA_MAJOR);
            writer.newLine();
            writer.write("java-runtime-verification=passed");
            writer.newLine();
            writer.write("pack-mode-default=GTO-Easy");
            writer.newLine();
            writer.write("vanilla-difficulty-default=NORMAL");
            writer.newLine();
            writer.write("existing-world-mode=preserved");
            writer.newLine();
        }
        try {
            Files.move(
                    temporaryMarker,
                    marker,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporaryMarker,
                    marker,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporaryMarker);
        }
    }

    private static void writeRecoveryMarker(Path target) throws IOException {
        Path marker = target.resolve(RECOVERY_FILE);
        String text = PACK_NAME + "\n"
                + "target-version=" + PACK_VERSION + "\n"
                + "status=install-in-progress\n";
        writeUtf8Atomically(marker, text);
    }

    private static String launchInstructions(Path target) {
        return "Готово. Сборка, локальный Forge и приватная Java 21 проверены.\n\n"
                + "1. Полностью перезапустите TLauncher.\n"
                + "2. В настройках укажите игровую папку:\n" + target + "\n"
                + "3. Выберите локальную версию " + FORGE_PROFILE_ID + ".\n"
                + "   НЕ выбирайте удалённую запись «Forge 1.20.1».\n"
                + "4. Отключите «Принудительное обновление».\n"
                + "5. Оставьте выбор Java по умолчанию: профиль сам использует установленную Java 21.\n"
                + "6. Не выбирайте вручную Java 8 или Java 17.\n"
                + "7. Выделите 8192–12288 МБ RAM (рекомендуется 10240).\n"
                + "8. Введите постоянный ник и запускайте игру.";
    }

    private static void printLaunchInstructions(Path target) {
        System.out.println(launchInstructions(target));
    }

    private interface Progress {
        void log(String message);

        void update(int percent, String label);
    }

    private static final class ConsoleProgress implements Progress {
        @Override
        public void log(String message) {
            System.out.println(message);
        }

        @Override
        public void update(int percent, String label) {
            System.out.println("[" + percent + "%] " + label);
        }
    }

    private static final class SwingProgress implements Progress {
        private final JTextArea logArea;
        private final JProgressBar progressBar;

        private SwingProgress(JTextArea logArea, JProgressBar progressBar) {
            this.logArea = logArea;
            this.progressBar = progressBar;
        }

        @Override
        public void log(final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    logArea.append(message + System.lineSeparator());
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            });
        }

        @Override
        public void update(final int percent, final String label) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    progressBar.setValue(percent);
                    progressBar.setString(label);
                }
            });
        }
    }

    private static final class DownloadEntry {
        private final String destination;
        private final long size;
        private final String sha256;
        private final String url;
        private final String name;

        private DownloadEntry(
                String destination,
                long size,
                String sha256,
                String url,
                String name
        ) {
            this.destination = destination;
            this.size = size;
            this.sha256 = sha256;
            this.url = url;
            this.name = name;
        }
    }

    private static final class LockedEntry {
        private final String path;
        private final long size;
        private final String sha256;

        private LockedEntry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class CliOptions {
        private final Path target;
        private final boolean nonInteractive;

        private CliOptions(Path target, boolean nonInteractive) {
            this.target = target;
            this.nonInteractive = nonInteractive;
        }

        private static CliOptions parse(String[] args) {
            Path target = null;
            boolean nonInteractive = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--target".equals(argument)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException(
                                "После --target требуется путь."
                        );
                    }
                    target = Paths.get(args[++index]).toAbsolutePath().normalize();
                } else if ("--non-interactive".equals(argument)) {
                    nonInteractive = true;
                } else if ("--help".equals(argument) || "-h".equals(argument)) {
                    printUsage();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException(
                            "Неизвестный аргумент: " + argument
                    );
                }
            }
            return new CliOptions(target, nonInteractive);
        }
    }
}
