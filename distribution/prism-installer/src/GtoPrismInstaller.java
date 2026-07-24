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
import java.nio.file.DirectoryStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Transparent, standard-user installer for the licensed Friends Edition.
 *
 * <p>The installer deliberately owns only a dedicated portable Prism directory
 * and a single Prism instance.  It never imports, reads, hashes, copies, backs
 * up, or rewrites Prism's accounts.json.  Existing worlds and ordinary game
 * settings are outside the managed update set.</p>
 */
public final class GtoPrismInstaller {
    private static final String PACK_NAME =
            "GregTech Odyssey — Friends Edition";
    private static final String PACK_VERSION = "1.0.4";
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION = "47.4.20";
    private static final String PRISM_VERSION = "11.0.3";
    private static final String INSTANCE_ID = "GTO-Friends";
    private static final int GAME_JAVA_MAJOR = 21;
    private static final String MARKER_FILE =
            "GTO-FRIENDS-LICENSED-INSTALLED.txt";
    private static final String RECOVERY_FILE =
            "GTO-FRIENDS-LICENSED-INSTALL-IN-PROGRESS.txt";
    private static final String JAVA_LOCK_FILE =
            "gto-temurin-21.lock.tsv";
    private static final String CLIENT_LOCK_FILE =
            "GTO-FRIENDS-CLIENT-LOCK.tsv";
    /*
     * This is the first licensed installer, so there are no genuine prior
     * licensed releases yet.  A future release must add only versions for
     * which it also embeds and tests an explicit migration.
     */
    private static final List<String> PREVIOUS_PACK_VERSIONS =
            Collections.emptyList();
    private static final String USER_AGENT =
            "GTO-Friends-Licensed-Prism-Installer/1.0.4 "
                    + "(https://github.com/ChiefSparrow/"
                    + "gregtech-odyssey-friends)";
    private static final DownloadEntry PRISM_ARCHIVE =
            new DownloadEntry(
                    ".gto-downloads/"
                            + "PrismLauncher-Windows-MinGW-w64-Portable-"
                            + PRISM_VERSION + ".zip",
                    43902886L,
                    "7E27AEDD92EABB0699792B5F6305DB6635290D83652CBD73742C70350E42B7F8",
                    "https://github.com/PrismLauncher/PrismLauncher/releases/"
                            + "download/" + PRISM_VERSION + "/"
                            + "PrismLauncher-Windows-MinGW-w64-Portable-"
                            + PRISM_VERSION + ".zip",
                    "официальный portable Prism Launcher " + PRISM_VERSION
            );
    private static final long PRISM_EXE_SIZE = 20796176L;
    private static final String PRISM_EXE_SHA256 =
            "4A074C611AFE0219A4AB478EF95429575550B9059202AE2A7E625C116A699CCA";
    private static final LinkedHashMap<String, String> INSTANCE_SETTINGS =
            createInstanceSettings();

    private GtoPrismInstaller() {
    }

    public static void main(String[] args) {
        System.setProperty("https.protocols", "TLSv1.2");
        final CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (options.selfCheck) {
            try {
                selfCheck();
                System.out.println("SELF-CHECK PASSED");
            } catch (Exception error) {
                error.printStackTrace(System.err);
                System.err.println("SELF-CHECK FAILED: " + error.getMessage());
                System.exit(1);
            }
            return;
        }

        if (options.nonInteractive) {
            Path target = options.target != null
                    ? options.target
                    : defaultTarget();
            try {
                install(target, new ConsoleProgress());
                System.out.println();
                System.out.println("INSTALLATION PASSED");
                printLaunchInstructions(target);
                if (!options.noLaunch) {
                    try {
                        launchPrism(target);
                    } catch (Exception launchError) {
                        System.err.println(
                                "Сборка установлена, но Prism не удалось открыть: "
                                        + launchError.getMessage()
                        );
                    }
                }
            } catch (Exception error) {
                error.printStackTrace(System.err);
                System.err.println("INSTALLATION FAILED: " + error.getMessage());
                System.exit(1);
            }
            return;
        }

        final Path requestedTarget = options.target != null
                ? options.target
                : defaultTarget();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showGui(requestedTarget, options.noLaunch);
            }
        });
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -jar GTO-Licensed-Prism-Installer.jar "
                        + "[--target <directory>] [--non-interactive] "
                        + "[--no-launch] [--self-check]"
        );
    }

    private static void showGui(
            Path initialTarget,
            final boolean noLaunch
    ) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final JFrame frame = new JFrame(
                PACK_NAME + " — установщик для лицензии"
        );
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(780, 540));

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel(
                "Установка " + PACK_NAME + " " + PACK_VERSION
        );
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
                "<html>Установщик создаст отдельный официальный portable Prism, "
                        + "приватную Java 21 и готовый профиль сборки. "
                        + "Ручной импорт, выбор Forge и настройка Java не нужны. "
                        + "Права администратора не требуются.</html>"
        );
        center.add(description, constraints);

        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 8, 8);
        center.add(new JLabel("Папка установки:"), constraints);

        final JTextField targetField = new JTextField(
                initialTarget.toAbsolutePath().normalize().toString()
        );
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        center.add(targetField, constraints);

        final JButton browseButton = new JButton("Выбрать…");
        constraints.gridx = 2;
        constraints.weightx = 0.0;
        center.add(browseButton, constraints);

        final JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(720, 300));
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 3;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.BOTH;
        center.add(scrollPane, constraints);
        root.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Готов к установке");
        bottom.add(progressBar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        final JButton installButton = new JButton("Установить / обновить");
        buttons.add(installButton);
        bottom.add(buttons, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        browseButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Выберите отдельную папку для GTO Friends");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setSelectedFile(
                    Paths.get(targetField.getText()).toFile()
            );
            if (chooser.showOpenDialog(frame)
                    == JFileChooser.APPROVE_OPTION) {
                targetField.setText(
                        chooser.getSelectedFile().toPath()
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                );
            }
        });

        installButton.addActionListener(event -> {
            final Path selected;
            try {
                selected = Paths.get(targetField.getText())
                        .toAbsolutePath()
                        .normalize();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Некорректный путь: " + error.getMessage(),
                        "Ошибка",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }
            installButton.setEnabled(false);
            browseButton.setEnabled(false);
            targetField.setEnabled(false);
            logArea.setText("");
            progressBar.setValue(0);
            progressBar.setString("Подготовка");

            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                private Throwable failure;

                @Override
                protected Void doInBackground() {
                    try {
                        install(
                                selected,
                                new SwingProgress(logArea, progressBar)
                        );
                    } catch (Throwable error) {
                        failure = error;
                    }
                    return null;
                }

                @Override
                protected void done() {
                    if (failure == null) {
                        progressBar.setValue(100);
                        progressBar.setString("Готово");
                        if (!noLaunch) {
                            try {
                                launchPrism(selected);
                            } catch (Exception launchError) {
                                logArea.append(
                                        "Сборка установлена, но Prism не удалось "
                                                + "открыть: "
                                                + launchError.getMessage()
                                                + System.lineSeparator()
                                );
                            }
                        }
                        JOptionPane.showMessageDialog(
                                frame,
                                launchInstructions(selected),
                                "Установка завершена",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().open(selected.toFile());
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        progressBar.setString("Ошибка");
                        JOptionPane.showMessageDialog(
                                frame,
                                failure.getMessage(),
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

    private static void install(Path requestedTarget, Progress progress)
            throws Exception {
        requireWindowsX64();
        Path target = requestedTarget.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException(
                    "Не удалось определить родительскую папку: " + target
            );
        }
        Path currentJavaHome = Paths.get(System.getProperty("java.home"))
                .toAbsolutePath()
                .normalize();
        rejectDangerousTarget(target, currentJavaHome);
        rejectExistingPathChain(target);
        Files.createDirectories(parent);
        if (currentJavaHome.startsWith(javaHome(target))) {
            throw new IOException(
                    "Установщик запущен из Java установленной сборки. "
                            + "Закройте Prism и запустите "
                            + "INSTALL-GTO-LICENSED.bat из распакованного архива."
            );
        }

        Path lockPath = parent.resolve(
                "." + target.getFileName() + ".gto-licensed-install.lock"
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
                        "Для этой папки уже запущен другой установщик GTO."
                );
            }
            acquired = true;
            try {
                selfCheck();
                verifyBootstrapJava(currentJavaHome);
                InstallationKind kind = validateTarget(target);
                if (kind == InstallationKind.UPDATE) {
                    updateInstall(target, parent, currentJavaHome, progress);
                } else {
                    cleanInstall(
                            target,
                            parent,
                            currentJavaHome,
                            kind == InstallationKind.RECOVER,
                            progress
                    );
                }
            } finally {
                lock.release();
            }
        } finally {
            if (acquired) {
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException cleanupError) {
                    progress.log(
                            "Не удалось удалить файл блокировки: " + lockPath
                    );
                }
            }
        }
    }

    private static void rejectDangerousTarget(
            Path target,
            Path currentJavaHome
    ) throws Exception {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path root = normalizedTarget.getRoot();
        if (root == null || normalizedTarget.equals(root)) {
            throw new IOException(
                    "Нельзя устанавливать сборку в корень диска."
            );
        }
        Path installerDirectory = ownJarPath().getParent();
        if (pathsOverlap(normalizedTarget, currentJavaHome)
                || (installerDirectory != null
                && pathsOverlap(
                normalizedTarget,
                installerDirectory.toAbsolutePath().normalize()
        ))) {
            throw new IOException(
                    "Папка установки не должна находиться внутри папки "
                            + "установщика/BOOTSTRAP-JAVA и не должна быть "
                            + "их родителем. Оставьте предложенный путь "
                            + "в LOCALAPPDATA."
            );
        }
        for (String environmentName : Arrays.asList(
                "SystemRoot",
                "WINDIR",
                "ProgramFiles",
                "ProgramFiles(x86)",
                "ProgramW6432"
        )) {
            String value = System.getenv(environmentName);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            Path protectedRoot = Paths.get(value)
                    .toAbsolutePath()
                    .normalize();
            if (pathsOverlap(normalizedTarget, protectedRoot)) {
                throw new IOException(
                        "Нельзя устанавливать сборку в системную папку "
                                + environmentName + ": " + normalizedTarget
                );
            }
        }
    }

    private static boolean pathsOverlap(Path left, Path right) {
        return left.startsWith(right) || right.startsWith(left);
    }

    private static void rejectExistingPathChain(Path target)
            throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        if (current == null) {
            throw new IOException("У пути установки нет корня.");
        }
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            rejectSingleLink(current);
        }
        for (Path part : current.relativize(normalized)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            rejectSingleLink(current);
        }
    }

    private static void cleanInstall(
            Path target,
            Path parent,
            Path currentJavaHome,
            boolean recover,
            Progress progress
    ) throws Exception {
        Path staging = createStaging(parent, target, "installing");
        try {
            progress.log("Папка установки: " + target);
            progress.log("Проверка и подготовка файлов во временной папке…");
            progress.update(2, "Prism Launcher");

            Path stagedPrism = prismRoot(staging);
            installPrism(staging, stagedPrism, progress);
            Files.createDirectories(userData(staging));
            writeCleanPrismSettings(userData(staging));
            writeEmptyAccountsForClean(userData(staging));

            progress.update(12, "Java 21");
            installJavaFromBootstrap(
                    currentJavaHome,
                    javaHome(staging),
                    javaLockPath(staging)
            );

            progress.update(18, "Файлы сборки");
            Path stagedGame = gameRoot(staging);
            extractPayload(stagedGame);
            List<DownloadEntry> downloads = loadDownloads();
            int completed = 0;
            for (DownloadEntry entry : downloads) {
                completed++;
                int percent = 18
                        + (int) Math.floor(
                        completed * 67.0 / downloads.size()
                );
                progress.update(
                        percent,
                        completed + "/" + downloads.size()
                );
                Path destination = safeResolve(stagedGame, entry.destination);
                downloadWithRetries(entry, destination, progress);
            }

            progress.update(87, "Профиль Prism");
            writeFreshInstanceMetadata(instanceRoot(staging));
            extractResourceFile(
                    "/PLAY-GTO-LICENSED.bat",
                    staging.resolve("PLAY-GTO-LICENSED.bat")
            );
            extractResourceFile(
                    "/README-LICENSED.txt",
                    staging.resolve("README-LICENSED.txt")
            );
            extractResourceFile(
                    "/client-lock.tsv",
                    staging.resolve(CLIENT_LOCK_FILE)
            );
            writeRecoveryMarker(staging);

            progress.update(91, "Проверка");
            verifyCompleteInstall(staging);
            verifyCleanPrismSettings(userData(staging));
            writeMarker(staging);
            verifyMarker(staging);

            progress.update(96, "Сохранение");
            commitClean(staging, target, recover);
            Files.deleteIfExists(target.resolve(RECOVERY_FILE));

            progress.update(98, "Финальная проверка");
            verifyCompleteInstall(target);
            verifyCleanPrismSettings(userData(target));
            verifyMarker(target);
            progress.update(100, "Готово");
            progress.log("Установка завершена и полностью проверена.");
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

    private static void updateInstall(
            Path target,
            Path parent,
            Path currentJavaHome,
            Progress progress
    ) throws Exception {
        progress.log("Найдена установленная Friends Edition.");
        progress.log("Сначала выполняется полная проверка без изменений.");
        progress.update(3, "Проверка установки");

        ensurePrismClosed(target);
        rejectManagedLinks(target);
        verifySupportedMarker(target);
        verifyPrismDistribution(prismRoot(target));
        verifyClientForUpdate(gameRoot(target));
        guardExistingWorld(gameRoot(target));
        verifyFileCanBeUpdated(
                prismRoot(target).resolve("prismlauncher.exe"),
                "Prism Launcher"
        );
        verifyOneModCanBeUpdated(gameRoot(target));
        Path installedJavaw =
                javaHome(target).resolve("bin").resolve("javaw.exe");
        if (Files.isRegularFile(
                installedJavaw,
                LinkOption.NOFOLLOW_LINKS
        )) {
            verifyFileCanBeUpdated(installedJavaw, "Minecraft");
        }

        Path staging = createStaging(parent, target, "updating");
        UpdateTransaction transaction = null;
        try {
            progress.update(18, "Подготовка Java 21");
            installJavaFromBootstrap(
                    currentJavaHome,
                    javaHome(staging),
                    javaLockPath(staging)
            );

            progress.update(46, "Настройки сложности");
            List<String> configUpdates = prepareConfigUpdates(
                    gameRoot(target),
                    gameRoot(staging),
                    progress
            );
            verifyProspectiveManagedConfigs(
                    gameRoot(target),
                    gameRoot(staging),
                    configUpdates
            );

            progress.update(58, "Профиль Prism");
            prepareUpdatedInstanceMetadata(
                    instanceRoot(target),
                    instanceRoot(staging)
            );
            extractResourceFile(
                    "/PLAY-GTO-LICENSED.bat",
                    staging.resolve("PLAY-GTO-LICENSED.bat")
            );
            extractResourceFile(
                    "/README-LICENSED.txt",
                    staging.resolve("README-LICENSED.txt")
            );
            extractResourceFile(
                    "/client-lock.tsv",
                    staging.resolve(CLIENT_LOCK_FILE)
            );
            writeMarker(staging);

            verifyJavaRuntime(javaHome(staging), javaLockPath(staging));
            verifyInstanceMetadata(instanceRoot(staging));
            for (String relative : configUpdates) {
                verifyUpdatedConfigFile(
                        gameRoot(staging),
                        relative
                );
            }

            progress.update(72, "Применение обновления");
            writeRecoveryMarker(target);
            transaction = new UpdateTransaction(parent, target);
            transaction.replaceDirectory(
                    javaHome(staging),
                    javaHome(target)
            );
            transaction.replaceFile(
                    javaLockPath(staging),
                    javaLockPath(target)
            );
            for (String relative : configUpdates) {
                transaction.replaceFile(
                        safeResolve(gameRoot(staging), relative),
                        safeResolve(gameRoot(target), relative)
                );
            }
            transaction.replaceFile(
                    instanceRoot(staging).resolve("instance.cfg"),
                    instanceRoot(target).resolve("instance.cfg")
            );
            transaction.replaceFile(
                    instanceRoot(staging).resolve("mmc-pack.json"),
                    instanceRoot(target).resolve("mmc-pack.json")
            );
            transaction.replaceFile(
                    staging.resolve("PLAY-GTO-LICENSED.bat"),
                    target.resolve("PLAY-GTO-LICENSED.bat")
            );
            transaction.replaceFile(
                    staging.resolve("README-LICENSED.txt"),
                    target.resolve("README-LICENSED.txt")
            );
            transaction.replaceFile(
                    staging.resolve(CLIENT_LOCK_FILE),
                    target.resolve(CLIENT_LOCK_FILE)
            );
            transaction.replaceFile(
                    staging.resolve(MARKER_FILE),
                    target.resolve(MARKER_FILE)
            );

            progress.update(92, "Финальная проверка");
            verifyCompleteInstall(target);
            verifyMarker(target);
            transaction.commit();
            transaction = null;
            Files.deleteIfExists(target.resolve(RECOVERY_FILE));
            progress.update(100, "Готово");
            progress.log(
                    "Установка проверена и обновлена; миры и настройки сохранены."
            );
        } catch (Exception error) {
            if (transaction != null) {
                try {
                    transaction.rollback();
                    Files.deleteIfExists(target.resolve(RECOVERY_FILE));
                } catch (Exception rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw error;
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

    private static Path createStaging(
            Path parent,
            Path target,
            String purpose
    ) throws IOException {
        Path staging = parent.resolve(
                "." + target.getFileName() + ".gto-" + purpose + "-"
                        + UUID.randomUUID().toString()
        ).toAbsolutePath().normalize();
        if (!parent.toAbsolutePath().normalize().equals(staging.getParent())) {
            throw new IOException("Небезопасный временный путь: " + staging);
        }
        Files.createDirectories(staging);
        return staging;
    }

    private static void commitClean(
            Path staging,
            Path target,
            boolean recover
    ) throws Exception {
        Path parent = target.getParent();
        Path backup = parent.resolve(
                "." + target.getFileName() + ".gto-old-"
                        + UUID.randomUUID().toString()
        );
        boolean hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (hadTarget) {
            if (recover && !containsOnlyRecoveryMarker(target)) {
                throw new IOException(
                        "Во время загрузки в незавершённой папке появились "
                                + "новые данные. Они не будут удалены; "
                                + "выберите новую пустую папку."
                );
            }
            if (!recover && !isDirectoryEmpty(target)) {
                throw new IOException(
                        "Папка перестала быть пустой во время установки: "
                                + target
                );
            }
            moveAtomically(target, backup);
        }
        try {
            moveAtomically(staging, target);
        } catch (Exception error) {
            if (hadTarget && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveAtomically(backup, target);
                } catch (Exception rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw error;
        }
        if (hadTarget && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(backup);
        }
    }

    private static InstallationKind validateTarget(Path target)
            throws Exception {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return InstallationKind.CLEAN;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw new IOException(
                    "Путь установки должен быть обычной папкой: " + target
            );
        }
        if (hasSupportedMarker(target)) {
            return InstallationKind.UPDATE;
        }
        if (hasRecoveryMarker(target)) {
            if (!containsOnlyRecoveryMarker(target)) {
                throw new IOException(
                        "Незавершённая папка содержит неизвестные данные. "
                                + "Установщик не будет их обходить, читать "
                                + "или удалять; выберите новую пустую папку."
                );
            }
            return InstallationKind.RECOVER;
        }
        if (!isDirectoryEmpty(target)) {
            throw new IOException(
                    "Выбранная папка не пуста и не является установкой "
                            + "GTO Friends для лицензии. Выберите отдельную "
                            + "пустую папку: " + target
            );
        }
        return InstallationKind.CLEAN;
    }

    private static boolean isDirectoryEmpty(Path directory)
            throws IOException {
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    private static boolean containsOnlyRecoveryMarker(Path directory)
            throws IOException {
        int count = 0;
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                count++;
                if (count > 1
                        || !entry.getFileName().toString().equals(
                        RECOVERY_FILE
                )
                        || !Files.isRegularFile(
                        entry,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    return false;
                }
            }
        }
        return count == 1;
    }

    private static Path defaultTarget() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData != null && !localAppData.trim().isEmpty()
                ? Paths.get(localAppData)
                : Paths.get(System.getProperty("user.home"))
                .resolve("AppData")
                .resolve("Local");
        return root.resolve("GTO-Friends-Licensed")
                .toAbsolutePath()
                .normalize();
    }

    private static Path prismRoot(Path root) {
        return root.resolve("PrismLauncher");
    }

    private static Path userData(Path root) {
        return prismRoot(root).resolve("UserData");
    }

    private static Path instanceRoot(Path root) {
        return userData(root).resolve("instances").resolve(INSTANCE_ID);
    }

    private static Path gameRoot(Path root) {
        return instanceRoot(root).resolve("minecraft");
    }

    private static Path javaHome(Path root) {
        return userData(root)
                .resolve("java")
                .resolve("gto-temurin-21");
    }

    private static Path javaLockPath(Path root) {
        return userData(root)
                .resolve("java")
                .resolve(JAVA_LOCK_FILE);
    }

    private static void installPrism(
            Path stagingRoot,
            Path destination,
            Progress progress
    ) throws Exception {
        Path archive = safeResolve(stagingRoot, PRISM_ARCHIVE.destination);
        downloadWithRetries(PRISM_ARCHIVE, archive, progress);
        List<LockedEntry> lock = loadPrismLock();
        try {
            extractLockedZip(archive, destination, lock);
            verifyPrismDistribution(destination);
        } finally {
            Files.deleteIfExists(archive);
            Path downloadDirectory = archive.getParent();
            if (downloadDirectory != null
                    && Files.isDirectory(downloadDirectory)
                    && isDirectoryEmpty(downloadDirectory)) {
                Files.delete(downloadDirectory);
            }
        }
    }

    private static void extractLockedZip(
            Path archive,
            Path destination,
            List<LockedEntry> lock
    ) throws Exception {
        Map<String, LockedEntry> expected = lockMap(lock);
        Set<String> directories = expectedDirectoryKeys(lock);
        Set<String> seen = new HashSet<String>();
        int entries = 0;
        long extracted = 0L;
        Files.createDirectories(destination);

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive))
        )) {
            ZipEntry entry;
            byte[] buffer = new byte[1024 * 128];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 1000) {
                    throw new IOException(
                            "В архиве Prism неожиданно много файлов."
                    );
                }
                String relative = normalizeArchivePath(
                        entry.getName(),
                        entry.isDirectory()
                );
                if (relative.isEmpty()
                        || !isSafeWindowsArchivePath(relative)) {
                    throw new IOException(
                            "Небезопасный путь в архиве Prism: "
                                    + entry.getName()
                    );
                }
                String key = relative.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    throw new IOException(
                            "Повторяющийся путь в архиве Prism: " + relative
                    );
                }
                Path output = safeResolve(destination, relative);
                if (entry.isDirectory()) {
                    if (!directories.contains(key)) {
                        throw new IOException(
                                "Лишняя папка в архиве Prism: " + relative
                        );
                    }
                    Files.createDirectories(output);
                    zip.closeEntry();
                    continue;
                }
                LockedEntry expectedFile = expected.get(key);
                if (expectedFile == null) {
                    throw new IOException(
                            "Лишний файл в архиве Prism: " + relative
                    );
                }
                Files.createDirectories(output.getParent());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0L;
                try (OutputStream stream = Files.newOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        stream.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        count += read;
                        extracted += read;
                        if (count > expectedFile.size
                                || extracted > 450L * 1024L * 1024L) {
                            throw new IOException(
                                    "Архив Prism превысил ожидаемый размер."
                            );
                        }
                    }
                }
                if (count != expectedFile.size
                        || !toHex(digest.digest()).equals(
                        expectedFile.sha256
                )) {
                    throw new IOException(
                            "Не совпадает файл из архива Prism: " + relative
                    );
                }
                zip.closeEntry();
            }
        }
        if (seen.size() < lock.size()) {
            throw new IOException("Архив Prism неполон.");
        }
    }

    private static String normalizeArchivePath(
            String path,
            boolean directory
    ) {
        String normalized = path.replace('\\', '/');
        while (directory && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void verifyPrismDistribution(Path root)
            throws Exception {
        List<LockedEntry> lock = loadPrismLock();
        try {
            verifyLockedDirectory(root, lock, "UserData");
        } catch (Exception error) {
            throw new IOException(
                    "Файлы Prism Launcher отличаются от закреплённой "
                            + "официальной версии " + PRISM_VERSION + ". "
                            + "Установщик не станет молча перезаписывать "
                            + "или понижать другой Prism; выберите новую "
                            + "папку либо верните Prism " + PRISM_VERSION + ".",
                    error
            );
        }
        Path executable = root.resolve("prismlauncher.exe");
        if (!matches(executable, PRISM_EXE_SIZE, PRISM_EXE_SHA256)) {
            throw new IOException(
                    "Исполняемый файл Prism Launcher не совпадает "
                            + "с закреплённой официальной версией "
                            + PRISM_VERSION + ". Обновление не будет "
                            + "молча заменено или понижено; выберите новую "
                            + "папку либо верните Prism " + PRISM_VERSION + "."
            );
        }
        if (!Files.isRegularFile(root.resolve("portable.txt"))) {
            throw new IOException(
                    "В Prism отсутствует portable.txt; аккаунты и настройки "
                            + "могли бы попасть в общую папку."
            );
        }
    }

    private static void installJavaFromBootstrap(
            Path sourceJavaHome,
            Path destinationJavaHome,
            Path destinationLock
    ) throws Exception {
        verifyBootstrapJava(sourceJavaHome);
        if (Files.exists(destinationJavaHome, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(destinationJavaHome);
        }
        copyTree(sourceJavaHome, destinationJavaHome);
        extractResourceFile("/bootstrap-java-lock.tsv", destinationLock);
        verifyJavaRuntime(destinationJavaHome, destinationLock);
    }

    private static void verifyBootstrapJava(Path currentJavaHome)
            throws Exception {
        List<LockedEntry> lock = loadJavaLock();
        verifyLockedDirectory(currentJavaHome, lock, null);
        requireJava21X64(
                currentJavaHome.resolve("bin").resolve("java.exe")
        );
        if (!Files.isRegularFile(
                currentJavaHome.resolve("bin").resolve("javaw.exe")
        )) {
            throw new IOException(
                    "В комплектной Java отсутствует bin/javaw.exe. "
                            + "Запускайте INSTALL-GTO-LICENSED.bat из полностью "
                            + "распакованного архива."
            );
        }
    }

    private static void verifyJavaRuntime(
            Path installedJavaHome,
            Path installedLock
    ) throws Exception {
        if (!Files.isRegularFile(installedLock)) {
            throw new IOException(
                    "Отсутствует манифест приватной Java 21."
            );
        }
        byte[] embedded = readResourceBytes(
                "/bootstrap-java-lock.tsv",
                4 * 1024 * 1024
        );
        if (Files.size(installedLock) != embedded.length
                || !sha256(installedLock).equals(
                sha256Bytes(embedded)
        )) {
            throw new IOException(
                    "Манифест приватной Java 21 не совпадает с установщиком."
            );
        }
        verifyLockedDirectory(installedJavaHome, loadJavaLock(), null);
        requireJava21X64(
                installedJavaHome.resolve("bin").resolve("java.exe")
        );
        if (!Files.isRegularFile(
                installedJavaHome.resolve("bin").resolve("javaw.exe")
        )) {
            throw new IOException(
                    "В приватной Java 21 отсутствует javaw.exe."
            );
        }
    }

    private static void requireJava21X64(Path javaExecutable)
            throws IOException {
        String error = java21ProbeError(javaExecutable);
        if (error != null) {
            throw new IOException(
                    "Требуется точная 64-битная Java 21: " + error
            );
        }
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
            final Process running = process;
            final StringBuilder output = new StringBuilder();
            final IOException[] readError = new IOException[]{null};
            final boolean[] tooLarge = new boolean[]{false};
            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    running.getInputStream(),
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
            }, "gto-prism-java21-probe");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                readerThread.join(2000L);
                return "проверка java.exe превысила 15 секунд";
            }
            readerThread.join(2000L);
            if (readerThread.isAlive()) {
                return "не удалось дочитать вывод java.exe";
            }
            if (readError[0] != null) {
                return "ошибка чтения java.exe: "
                        + readError[0].getMessage();
            }
            if (tooLarge[0]) {
                return "java.exe вернула слишком большой вывод";
            }
            if (process.exitValue() != 0) {
                return "java.exe завершилась с кодом "
                        + process.exitValue();
            }
            String details = output.toString();
            if (!details.matches(
                    "(?s).*java\\.specification\\.version\\s*=\\s*21"
                            + "(?:\\s|$).*"
            )) {
                return "не подтверждена specification version 21";
            }
            if (!details.matches(
                    "(?s).*sun\\.arch\\.data\\.model\\s*=\\s*64"
                            + "(?:\\s|$).*"
            )) {
                return "не подтверждена 64-битная архитектура";
            }
            return null;
        } catch (Exception error) {
            return error.getClass().getSimpleName() + ": "
                    + error.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void extractPayload(Path game) throws Exception {
        Path jarPath = ownJarPath();
        int files = 0;
        long extracted = 0L;
        Set<String> paths = new HashSet<String>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            byte[] buffer = new byte[1024 * 128];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("payload/")) {
                    continue;
                }
                String relative = entry.getName().substring(
                        "payload/".length()
                );
                if (relative.isEmpty()) {
                    continue;
                }
                relative = normalizeArchivePath(
                        relative,
                        entry.isDirectory()
                );
                if (relative.isEmpty()
                        || !isSafeWindowsArchivePath(relative)) {
                    throw new IOException(
                            "Небезопасный путь payload: " + entry.getName()
                    );
                }
                String key = relative.toLowerCase(Locale.ROOT);
                if (!paths.add(key)) {
                    throw new IOException(
                            "Повторяющийся путь payload: " + relative
                    );
                }
                Path destination = safeResolve(game, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                files++;
                if (files > 5000) {
                    throw new IOException("Payload содержит слишком много файлов.");
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input =
                             new BufferedInputStream(jar.getInputStream(entry));
                     OutputStream output = Files.newOutputStream(destination)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        extracted += read;
                        if (extracted > 750L * 1024L * 1024L) {
                            throw new IOException(
                                    "Распакованный payload слишком велик."
                            );
                        }
                    }
                }
            }
        }
        if (files < 10) {
            throw new IOException("Payload сборки выглядит неполным.");
        }
    }

    private static void extractResourceFile(
            String resource,
            Path destination
    ) throws IOException {
        InputStream stream =
                GtoPrismInstaller.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException(
                    "В установщике отсутствует ресурс: " + resource
            );
        }
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-"
                        + UUID.randomUUID().toString()
        );
        try (InputStream input = new BufferedInputStream(stream)) {
            Files.copy(
                    input,
                    temporary,
                    StandardCopyOption.REPLACE_EXISTING
            );
            moveAtomically(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<DownloadEntry> loadDownloads()
            throws IOException {
        List<DownloadEntry> entries = new ArrayList<DownloadEntry>();
        Set<String> paths = new HashSet<String>();
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
                    throw new IOException(
                            "Некорректная строка downloads.tsv: " + line
                    );
                }
                String destination = fields[0];
                requireManifestPath(destination, "downloads.tsv");
                String key = destination.toLowerCase(Locale.ROOT);
                if (!paths.add(key)) {
                    throw new IOException(
                            "Повторяющийся путь в downloads.tsv: "
                                    + destination
                    );
                }
                long size;
                try {
                    size = Long.parseLong(fields[1]);
                } catch (NumberFormatException error) {
                    throw new IOException(
                            "Некорректный размер в downloads.tsv: " + line
                    );
                }
                String hash = fields[2].toUpperCase(Locale.ROOT);
                requireHashAndSize(hash, size, "downloads.tsv");
                String url = fields[3];
                requireAllowedInitialUrl(url);
                entries.add(new DownloadEntry(
                        destination,
                        size,
                        hash,
                        url,
                        fields[6]
                ));
            }
        }
        if (entries.size() != 171) {
            throw new IOException(
                    "Ожидалась 171 загрузка, найдено: " + entries.size()
            );
        }
        return entries;
    }

    private static List<LockedEntry> loadClientLock()
            throws IOException {
        return loadLock("/client-lock.tsv", 177, 177);
    }

    private static List<LockedEntry> loadPrismLock()
            throws IOException {
        return loadLock("/prism-lock.tsv", 75, 75);
    }

    private static List<LockedEntry> loadJavaLock()
            throws IOException {
        return loadLock("/bootstrap-java-lock.tsv", 50, 2000);
    }

    private static List<LockedEntry> loadLock(
            String resource,
            int minimumCount,
            int maximumCount
    ) throws IOException {
        List<LockedEntry> entries = new ArrayList<LockedEntry>();
        Set<String> paths = new HashSet<String>();
        try (BufferedReader reader = resourceReader(resource)) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("path\t")) {
                throw new IOException("Повреждён " + resource + ".");
            }
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) {
                    throw new IOException(
                            "Некорректная строка " + resource + ": " + line
                    );
                }
                requireManifestPath(fields[0], resource);
                String key = fields[0].toLowerCase(Locale.ROOT);
                if (!paths.add(key)) {
                    throw new IOException(
                            "Повторяющийся путь в " + resource + ": "
                                    + fields[0]
                    );
                }
                long size;
                try {
                    size = Long.parseLong(fields[1]);
                } catch (NumberFormatException error) {
                    throw new IOException(
                            "Некорректный размер в " + resource + ": " + line
                    );
                }
                String hash = fields[2].toUpperCase(Locale.ROOT);
                requireHashAndSize(hash, size, resource);
                entries.add(new LockedEntry(fields[0], size, hash));
            }
        }
        if (entries.size() < minimumCount
                || entries.size() > maximumCount) {
            throw new IOException(
                    "Некорректное число файлов в " + resource + ": "
                            + entries.size()
            );
        }
        return entries;
    }

    private static BufferedReader resourceReader(String resource)
            throws IOException {
        InputStream stream =
                GtoPrismInstaller.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException(
                    "В установщике отсутствует ресурс: " + resource
            );
        }
        return new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );
    }

    private static void requireManifestPath(
            String path,
            String source
    ) throws IOException {
        if (path == null
                || path.isEmpty()
                || path.indexOf('\\') >= 0
                || !isSafeWindowsArchivePath(path)) {
            throw new IOException(
                    "Небезопасный путь в " + source + ": " + path
            );
        }
    }

    private static void requireHashAndSize(
            String hash,
            long size,
            String source
    ) throws IOException {
        if (size < 0L
                || !hash.matches("[A-F0-9]{64}")) {
            throw new IOException(
                    "Некорректный размер или SHA-256 в " + source
            );
        }
    }

    private static void requireAllowedInitialUrl(String value)
            throws IOException {
        try {
            URL url = new URL(value);
            requireAllowedDownloadUrl(url);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Некорректный URL: " + value, error);
        }
    }

    private static void downloadWithRetries(
            DownloadEntry entry,
            Path destination,
            Progress progress
    ) throws Exception {
        if (matches(destination, entry.size, entry.sha256)) {
            progress.log("Уже проверен: " + entry.destination);
            return;
        }
        Exception lastError = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                progress.log(
                        "Скачивание: " + entry.name
                                + (attempt > 1
                                ? " (попытка " + attempt + ")"
                                : "")
                );
                download(entry, destination);
                if (!matches(destination, entry.size, entry.sha256)) {
                    throw new IOException(
                            "Контрольная сумма не совпала: "
                                    + entry.destination
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
                        + (lastError != null
                        ? lastError.getMessage()
                        : "неизвестная ошибка"),
                lastError
        );
    }

    private static void download(
            DownloadEntry entry,
            Path destination
    ) throws Exception {
        Files.createDirectories(destination.getParent());
        Path partial = partPath(destination);
        Files.deleteIfExists(partial);

        URL current = new URL(entry.url);
        HttpURLConnection connection = null;
        for (int redirect = 0; redirect < 6; redirect++) {
            requireAllowedDownloadUrl(current);
            connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty(
                    "Accept",
                    "application/octet-stream,*/*"
            );
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException(
                            "Перенаправление без адреса: " + entry.url
                    );
                }
                current = new URL(current, location);
                connection = null;
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException(
                        "HTTP " + status + " для " + entry.url
                );
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > entry.size) {
                connection.disconnect();
                throw new IOException(
                        "Сервер вернул слишком большой файл: "
                                + entry.destination
                );
            }
            break;
        }
        if (connection == null
                || connection.getResponseCode()
                != HttpURLConnection.HTTP_OK) {
            throw new IOException(
                    "Слишком много перенаправлений: " + entry.url
            );
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0L;
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
        if (count != entry.size || !actualHash.equals(entry.sha256)) {
            Files.deleteIfExists(partial);
            throw new IOException(
                    "Не совпал размер или SHA-256: " + entry.destination
            );
        }
        moveAtomically(partial, destination);
    }

    private static void requireAllowedDownloadUrl(URL url)
            throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException(
                    "Разрешены только HTTPS-загрузки: " + url
            );
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("edge.forgecdn.net")
                && !host.endsWith(".forgecdn.net")
                && !host.equals("github.com")
                && !host.equals("release-assets.githubusercontent.com")) {
            throw new IOException(
                    "Запрещённый узел загрузки: " + url.getHost()
            );
        }
    }

    private static Path partPath(Path destination) {
        return destination.resolveSibling(
                destination.getFileName() + ".part"
        );
    }

    private static void verifyClient(Path root) throws Exception {
        verifyClient(root, true);
    }

    private static void verifyClientForUpdate(Path root)
            throws Exception {
        verifyClient(root, false);
    }

    private static void verifyClient(
            Path root,
            boolean requireExactManagedConfigs
    ) throws Exception {
        List<LockedEntry> lock = loadClientLock();
        Set<String> expectedMods = new HashSet<String>();
        for (LockedEntry entry : lock) {
            if (!requireExactManagedConfigs
                    && entry.path.startsWith("config/")) {
                continue;
            }
            Path file = safeResolve(root, entry.path);
            if (!matches(file, entry.size, entry.sha256)) {
                throw new IOException(
                        "Файл сборки не совпадает с эталоном: " + entry.path
                );
            }
            if (entry.path.startsWith("mods/")) {
                expectedMods.add(
                        entry.path.substring("mods/".length())
                                .toLowerCase(Locale.ROOT)
                );
            }
        }

        Path mods = root.resolve("mods");
        rejectUnsafeLinks(mods, null);
        if (!Files.isDirectory(mods)) {
            throw new IOException("Отсутствует папка mods.");
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods)) {
            for (Path file : files) {
                BasicFileAttributes attributes = Files.readAttributes(
                        file,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException(
                            "Ссылки запрещены в mods: " + file
                    );
                }
                if (!attributes.isRegularFile()) {
                    continue;
                }
                String name = file.getFileName().toString()
                        .toLowerCase(Locale.ROOT);
                if (!expectedMods.contains(name)) {
                    throw new IOException(
                            "Обнаружен лишний мод: mods/"
                                    + file.getFileName()
                                    + ". Удалите его вручную либо выберите "
                                    + "новую папку; установщик не удаляет "
                                    + "неизвестные файлы."
                    );
                }
            }
        }
        if (requireExactManagedConfigs) {
            requireGtoEasy(
                    root.resolve("config").resolve("gtocore.yaml")
            );
            requireVanillaNormalDefaults(
                    root.resolve("config")
                            .resolve("defaultoptions-common.toml")
            );
        }
    }

    private static void verifyCompleteInstall(Path root) throws Exception {
        verifyPrismDistribution(prismRoot(root));
        verifyJavaRuntime(javaHome(root), javaLockPath(root));
        verifyInstanceMetadata(instanceRoot(root));
        verifyClient(gameRoot(root));
        verifyInstalledClientLock(root);
        if (!Files.isRegularFile(root.resolve("PLAY-GTO-LICENSED.bat"))
                || !Files.isRegularFile(
                root.resolve("README-LICENSED.txt")
        )) {
            throw new IOException(
                    "Отсутствуют файлы запуска или инструкция."
            );
        }
    }

    private static LinkedHashMap<String, String> createInstanceSettings() {
        LinkedHashMap<String, String> settings =
                new LinkedHashMap<String, String>();
        settings.put("ConfigVersion", "1.3");
        settings.put("InstanceType", "OneSix");
        settings.put("name", PACK_NAME);
        settings.put("ManagedPack", "false");
        settings.put("AutomaticJava", "false");
        settings.put("OverrideJavaLocation", "true");
        settings.put(
                "JavaPath",
                "java/gto-temurin-21/bin/javaw.exe"
        );
        settings.put("IgnoreJavaCompatibility", "true");
        settings.put("OverrideMemory", "true");
        settings.put("MinMemAlloc", "4096");
        settings.put("MaxMemAlloc", "10240");
        settings.put("PermGen", "128");
        return settings;
    }

    private static void writeCleanPrismSettings(Path data)
            throws IOException {
        String launcherSettings = "[General]\n"
                + "ConfigVersion=1.3\n"
                + "Language=ru\n"
                + "AutomaticJavaDownload=false\n"
                + "AutomaticJavaSwitch=false\n"
                + "IgnoreJavaWizard=true\n"
                + "SelectedInstance=" + INSTANCE_ID + "\n"
                + "InstanceDir=instances\n"
                + "JavaDir=java\n"
                + "MinMemAlloc=4096\n"
                + "MaxMemAlloc=10240\n"
                + "PermGen=128\n";
        String updaterSettings = "[General]\n"
                + "auto_check=false\n"
                + "update_interval=86400\n"
                + "allow_beta=false\n";
        writeUtf8Atomically(
                data.resolve("prismlauncher.cfg"),
                launcherSettings
        );
        writeUtf8Atomically(
                data.resolve("prismlauncher_update.cfg"),
                updaterSettings
        );
    }

    private static void writeEmptyAccountsForClean(Path data)
            throws IOException {
        Path destination = data.resolve("accounts.json");
        Files.write(
                destination,
                "{\"accounts\":[],\"formatVersion\":3}\n"
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private static void verifyCleanPrismSettings(Path data)
            throws IOException {
        Path launcher = data.resolve("prismlauncher.cfg");
        Path updater = data.resolve("prismlauncher_update.cfg");
        if (!Files.isRegularFile(launcher)
                || !Files.isRegularFile(updater)) {
            throw new IOException(
                    "Отсутствуют настройки portable Prism."
            );
        }
        String launcherText = new String(
                Files.readAllBytes(launcher),
                StandardCharsets.UTF_8
        );
        if (!hasSingleIniSetting(
                launcherText,
                "General",
                "IgnoreJavaWizard",
                "true"
        )
                || !hasSingleIniSetting(
                launcherText,
                "General",
                "AutomaticJavaDownload",
                "false"
        )
                || !hasSingleIniSetting(
                launcherText,
                "General",
                "AutomaticJavaSwitch",
                "false"
        )
                || !hasSingleIniSetting(
                launcherText,
                "General",
                "SelectedInstance",
                INSTANCE_ID
        )
                || !hasSingleIniSetting(
                launcherText,
                "General",
                "MinMemAlloc",
                "4096"
        )
                || !hasSingleIniSetting(
                launcherText,
                "General",
                "MaxMemAlloc",
                "10240"
        )) {
            throw new IOException(
                    "Некорректные начальные настройки Prism Launcher."
            );
        }
        String updaterText = new String(
                Files.readAllBytes(updater),
                StandardCharsets.UTF_8
        );
        if (!hasSingleIniSetting(
                updaterText,
                "General",
                "auto_check",
                "false"
        )) {
            throw new IOException(
                    "Автоматическая проверка обновлений Prism "
                            + "должна быть отключена для закреплённой версии."
            );
        }
    }

    private static void writeFreshInstanceMetadata(Path instance)
            throws Exception {
        Files.createDirectories(instance.resolve("minecraft"));
        StringBuilder config = new StringBuilder("[General]\n");
        for (Map.Entry<String, String> setting
                : INSTANCE_SETTINGS.entrySet()) {
            config.append(setting.getKey())
                    .append('=')
                    .append(setting.getValue())
                    .append('\n');
        }
        writeUtf8Atomically(
                instance.resolve("instance.cfg"),
                config.toString()
        );
        writeUtf8Atomically(
                instance.resolve("mmc-pack.json"),
                mmcPackJson()
        );
        verifyInstanceMetadata(instance);
    }

    private static void prepareUpdatedInstanceMetadata(
            Path installedInstance,
            Path stagedInstance
    ) throws Exception {
        Files.createDirectories(stagedInstance.resolve("minecraft"));
        Path installedConfig = installedInstance.resolve("instance.cfg");
        String current = Files.isRegularFile(installedConfig)
                ? new String(
                Files.readAllBytes(installedConfig),
                StandardCharsets.UTF_8
        )
                : "";
        String updated = patchInstanceConfig(current);
        writeUtf8Atomically(
                stagedInstance.resolve("instance.cfg"),
                updated
        );
        writeUtf8Atomically(
                stagedInstance.resolve("mmc-pack.json"),
                mmcPackJson()
        );
        verifyInstanceMetadata(stagedInstance);
    }

    private static String patchInstanceConfig(String text)
            throws IOException {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> input = new ArrayList<String>(
                Arrays.asList(normalized.split("\n", -1))
        );
        List<String> output = new ArrayList<String>();
        Set<String> written = new HashSet<String>();
        boolean inGeneral = false;
        boolean foundGeneral = false;
        boolean flushedGeneral = false;

        for (String line : input) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inGeneral && !flushedGeneral) {
                    appendMissingInstanceSettings(output, written);
                    flushedGeneral = true;
                }
                inGeneral = trimmed.equals("[General]");
                if (inGeneral) {
                    if (foundGeneral) {
                        throw new IOException(
                                "В instance.cfg несколько секций [General]."
                        );
                    }
                    foundGeneral = true;
                }
                output.add(line);
                continue;
            }
            if (inGeneral
                    && !trimmed.isEmpty()
                    && !trimmed.startsWith("#")
                    && !trimmed.startsWith(";")) {
                int separator = line.indexOf('=');
                if (separator >= 0) {
                    String key = line.substring(0, separator).trim();
                    String value = INSTANCE_SETTINGS.get(key);
                    if (value != null) {
                        if (written.add(key)) {
                            output.add(key + "=" + value);
                        }
                        continue;
                    }
                }
            }
            output.add(line);
        }
        if (!foundGeneral) {
            if (!output.isEmpty()
                    && !output.get(output.size() - 1).isEmpty()) {
                output.add("");
            }
            output.add("[General]");
            appendMissingInstanceSettings(output, written);
        } else if (inGeneral && !flushedGeneral) {
            appendMissingInstanceSettings(output, written);
        }
        return joinLines(output);
    }

    private static void appendMissingInstanceSettings(
            List<String> output,
            Set<String> written
    ) {
        for (Map.Entry<String, String> setting
                : INSTANCE_SETTINGS.entrySet()) {
            if (written.add(setting.getKey())) {
                output.add(
                        setting.getKey() + "=" + setting.getValue()
                );
            }
        }
    }

    private static String joinLines(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result.append('\n');
            }
            result.append(lines.get(index));
        }
        if (result.length() == 0
                || result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
        return result.toString();
    }

    private static String mmcPackJson() {
        return "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"components\": [\n"
                + "    {\n"
                + "      \"uid\": \"net.minecraft\",\n"
                + "      \"version\": \"" + MINECRAFT_VERSION + "\",\n"
                + "      \"important\": true\n"
                + "    },\n"
                + "    {\n"
                + "      \"uid\": \"net.minecraftforge\",\n"
                + "      \"version\": \"" + FORGE_VERSION + "\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
    }

    private static void verifyInstanceMetadata(Path instance)
            throws Exception {
        Path config = instance.resolve("instance.cfg");
        if (!Files.isRegularFile(config)) {
            throw new IOException("Отсутствует instance.cfg.");
        }
        String text = new String(
                Files.readAllBytes(config),
                StandardCharsets.UTF_8
        );
        for (Map.Entry<String, String> setting
                : INSTANCE_SETTINGS.entrySet()) {
            if (!hasSingleIniSetting(
                    text,
                    "General",
                    setting.getKey(),
                    setting.getValue()
            )) {
                throw new IOException(
                        "Некорректная настройка Prism: "
                                + setting.getKey()
                );
            }
        }

        Path pack = instance.resolve("mmc-pack.json");
        if (!Files.isRegularFile(pack)) {
            throw new IOException("Отсутствует mmc-pack.json.");
        }
        String packText = new String(
                Files.readAllBytes(pack),
                StandardCharsets.UTF_8
        );
        if (!packText.equals(mmcPackJson())) {
            throw new IOException(
                    "mmc-pack.json не совпадает с закреплённым профилем."
            );
        }
        if (!Files.isDirectory(instance.resolve("minecraft"))) {
            throw new IOException(
                    "Отсутствует игровая папка экземпляра Prism."
            );
        }
    }

    private static boolean hasSingleIniSetting(
            String text,
            String section,
            String key,
            String expected
    ) {
        boolean inSection = false;
        int sectionCount = 0;
        int matches = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSection = trimmed.equals("[" + section + "]");
                if (inSection) {
                    sectionCount++;
                }
                continue;
            }
            if (!inSection
                    || trimmed.isEmpty()
                    || trimmed.startsWith("#")
                    || trimmed.startsWith(";")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 0
                    || !line.substring(0, separator).trim().equals(key)) {
                continue;
            }
            if (!line.substring(separator + 1).trim().equals(expected)) {
                return false;
            }
            matches++;
        }
        return sectionCount == 1 && matches == 1;
    }

    private static List<String> prepareConfigUpdates(
            Path installedGame,
            Path stagedGame,
            Progress progress
    ) throws Exception {
        List<String> updates = new ArrayList<String>();
        Path installedGto =
                installedGame.resolve("config").resolve("gtocore.yaml");
        if (!Files.isRegularFile(installedGto)) {
            throw new IOException(
                    "В существующей установке отсутствует "
                            + "config/gtocore.yaml. Обновление остановлено "
                            + "до любых изменений."
            );
        }
        String currentGto = new String(
                Files.readAllBytes(installedGto),
                StandardCharsets.UTF_8
        );
        if (hasExistingWorld(installedGame)) {
            requireGtoEasy(installedGto);
            progress.log(
                    "Найдены миры GTO Easy; их режим оставлен без изменений."
            );
        } else {
            String updatedGto = replaceGtoDifficultyWithEasy(currentGto);
            if (updatedGto == null) {
                throw new IOException(
                        "Не удалось однозначно прочитать "
                                + "gamePlay.difficulty в gtocore.yaml. "
                                + "Обновление остановлено до любых изменений."
                );
            }
            Path stagedGto =
                    stagedGame.resolve("config").resolve("gtocore.yaml");
            writeUtf8Atomically(stagedGto, updatedGto);
            requireGtoEasy(stagedGto);
            if (!updatedGto.equals(currentGto)) {
                updates.add("config/gtocore.yaml");
            }
        }

        Path installedDefaults = installedGame
                .resolve("config")
                .resolve("defaultoptions-common.toml");
        String currentDefaults;
        if (Files.isRegularFile(installedDefaults)) {
            currentDefaults = new String(
                    Files.readAllBytes(installedDefaults),
                    StandardCharsets.UTF_8
            );
        } else {
            currentDefaults = readPayloadText(
                    "config/defaultoptions-common.toml",
                    1024 * 1024
            );
        }
        String withDifficulty = replaceActiveSetting(
                currentDefaults,
                "defaultDifficulty",
                "\"NORMAL\""
        );
        String updatedDefaults = withDifficulty != null
                ? replaceActiveSetting(
                withDifficulty,
                "lockDifficulty",
                "false"
        )
                : null;
        if (updatedDefaults == null) {
            throw new IOException(
                    "Не удалось однозначно обновить "
                            + "defaultoptions-common.toml."
            );
        }
        Path stagedDefaults = stagedGame
                .resolve("config")
                .resolve("defaultoptions-common.toml");
        writeUtf8Atomically(stagedDefaults, updatedDefaults);
        requireVanillaNormalDefaults(stagedDefaults);
        if (!Files.isRegularFile(installedDefaults)
                || !updatedDefaults.equals(currentDefaults)) {
            updates.add("config/defaultoptions-common.toml");
        }
        return updates;
    }

    private static void verifyUpdatedConfigFile(
            Path stagedGame,
            String relative
    ) throws IOException {
        if (relative.equals("config/gtocore.yaml")) {
            requireGtoEasy(safeResolve(stagedGame, relative));
        } else if (relative.equals(
                "config/defaultoptions-common.toml"
        )) {
            requireVanillaNormalDefaults(
                    safeResolve(stagedGame, relative)
            );
        } else {
            throw new IOException(
                    "Неизвестный управляемый config: " + relative
            );
        }
    }

    private static void verifyProspectiveManagedConfigs(
            Path installedGame,
            Path stagedGame,
            List<String> updates
    ) throws Exception {
        Set<String> updatedPaths = new HashSet<String>(updates);
        for (LockedEntry entry : loadClientLock()) {
            if (!entry.path.startsWith("config/")) {
                continue;
            }
            Path prospective = updatedPaths.contains(entry.path)
                    ? safeResolve(stagedGame, entry.path)
                    : safeResolve(installedGame, entry.path);
            if (!matches(prospective, entry.size, entry.sha256)) {
                throw new IOException(
                        "Управляемый config отличается от версии сборки "
                                + "не только целевыми настройками сложности: "
                                + entry.path + ". Чтобы не затереть другие "
                                + "изменения, обновление остановлено до "
                                + "любых изменений."
                );
            }
        }
    }

    private static void guardExistingWorld(Path game) throws Exception {
        Path gto = game.resolve("config").resolve("gtocore.yaml");
        if (!Files.isRegularFile(gto)) {
            throw new IOException(
                    "Отсутствует config/gtocore.yaml. Обновление остановлено "
                            + "до любых изменений."
            );
        }
        String text = new String(
                Files.readAllBytes(gto),
                StandardCharsets.UTF_8
        );
        int difficultyCount = countNestedSetting(
                text,
                "gamePlay",
                "difficulty"
        );
        if (difficultyCount != 1) {
            throw new IOException(
                    "Не удалось однозначно определить режим GTO. "
                            + "Обновление остановлено до любых изменений."
            );
        }
        if (hasExistingWorld(game)) {
            try {
                requireGtoEasy(gto);
            } catch (IOException error) {
                throw new IOException(
                        "Найден мир, созданный при GTO Normal/Expert либо "
                                + "повреждённой конфигурации. Его нельзя "
                                + "автоматически понизить до Easy. "
                                + "Выберите новую пустую папку; текущая "
                                + "установка не изменена.",
                        error
                );
            }
        }
    }

    private static boolean hasExistingWorld(Path game) throws IOException {
        Path saves = game.resolve("saves");
        if (!Files.exists(saves, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        BasicFileAttributes rootAttributes = Files.readAttributes(
                saves,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!rootAttributes.isDirectory()
                || rootAttributes.isSymbolicLink()
                || rootAttributes.isOther()) {
            throw new IOException(
                    "Папка saves является небезопасной ссылкой."
            );
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(saves, 2)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException(
                            "Ссылки запрещены внутри saves: " + path
                    );
                }
                if (attributes.isRegularFile()) {
                    String name = path.getFileName().toString();
                    if (name.equals("level.dat")
                            || name.equals("level.dat_old")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String replaceGtoDifficultyWithEasy(String text) {
        int directIndent = directChildIndentation(text, "gamePlay");
        if (directIndent < 1) {
            return null;
        }
        int position = 0;
        boolean inSection = false;
        int matchStart = -1;
        int matchEnd = -1;
        String replacement = null;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position
                    && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indent = line.length()
                    - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indent == 0) {
                    inSection = trimmed.equals("gamePlay:");
                } else if (inSection
                        && indent == directIndent
                        && trimmed.startsWith("difficulty:")) {
                    if (matchStart >= 0) {
                        return null;
                    }
                    String value = trimmed.substring(
                            "difficulty:".length()
                    ).trim();
                    if (value.isEmpty()) {
                        return null;
                    }
                    matchStart = position;
                    matchEnd = contentEnd;
                    String suffix = "";
                    int comment = value.indexOf('#');
                    if (comment >= 0) {
                        suffix = " " + value.substring(comment).trim();
                    }
                    replacement = line.substring(0, indent)
                            + "difficulty: Easy" + suffix;
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
            if (contentEnd > position
                    && text.charAt(contentEnd - 1) == '\r') {
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

    private static void requireGtoEasy(Path config)
            throws IOException {
        if (!Files.isRegularFile(config)) {
            throw new IOException("Отсутствует config/gtocore.yaml.");
        }
        requireGtoEasyText(new String(
                Files.readAllBytes(config),
                StandardCharsets.UTF_8
        ));
    }

    private static void requireGtoEasyText(String text)
            throws IOException {
        if (!hasSingleNestedSetting(
                text,
                "gamePlay",
                "difficulty",
                "Easy"
        )) {
            throw new IOException("Режим сборки GTO должен быть Easy.");
        }
    }

    private static void requireVanillaNormalDefaults(Path config)
            throws IOException {
        if (!Files.isRegularFile(config)) {
            throw new IOException(
                    "Отсутствует config/defaultoptions-common.toml."
            );
        }
        requireVanillaNormalDefaultsText(new String(
                Files.readAllBytes(config),
                StandardCharsets.UTF_8
        ));
    }

    private static void requireVanillaNormalDefaultsText(String text)
            throws IOException {
        if (!hasSingleActiveSetting(
                text,
                "defaultDifficulty",
                "\"NORMAL\""
        )
                || !hasSingleActiveSetting(
                text,
                "lockDifficulty",
                "false"
        )) {
            throw new IOException(
                    "Ванильная сложность должна быть NORMAL "
                            + "и не заблокирована."
            );
        }
    }

    private static boolean hasSingleNestedSetting(
            String text,
            String section,
            String key,
            String expectedValue
    ) {
        int directIndent = directChildIndentation(text, section);
        if (directIndent < 1) {
            return false;
        }
        int position = 0;
        boolean inSection = false;
        int matches = 0;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position
                    && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indent = line.length()
                    - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indent == 0) {
                    inSection = trimmed.equals(section + ":");
                } else if (inSection
                        && indent == directIndent
                        && trimmed.startsWith(key + ":")) {
                    String value = trimmed.substring(
                            (key + ":").length()
                    ).trim();
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

    private static int countNestedSetting(
            String text,
            String section,
            String key
    ) {
        int directIndent = directChildIndentation(text, section);
        if (directIndent < 1) {
            return 0;
        }
        int position = 0;
        boolean inSection = false;
        int matches = 0;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position
                    && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indent = line.length()
                    - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indent == 0) {
                    inSection = trimmed.equals(section + ":");
                } else if (inSection
                        && indent == directIndent
                        && trimmed.startsWith(key + ":")
                        && !trimmed.substring((key + ":").length())
                        .trim().isEmpty()) {
                    matches++;
                }
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        return matches;
    }

    private static int directChildIndentation(
            String text,
            String section
    ) {
        int position = 0;
        boolean inSection = false;
        int sectionCount = 0;
        int minimumIndent = Integer.MAX_VALUE;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int rawEnd = newline >= 0 ? newline : text.length();
            int contentEnd = rawEnd;
            if (contentEnd > position
                    && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(position, contentEnd);
            String trimmed = line.trim();
            int indent = line.length()
                    - stripLeadingWhitespace(line).length();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (indent == 0) {
                    inSection = trimmed.equals(section + ":");
                    if (inSection) {
                        sectionCount++;
                    }
                } else if (inSection) {
                    minimumIndent = Math.min(minimumIndent, indent);
                }
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        return sectionCount == 1
                && minimumIndent != Integer.MAX_VALUE
                ? minimumIndent
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

    private static void writeUtf8Atomically(
            Path destination,
            String text
    ) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-"
                        + UUID.randomUUID().toString()
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

    private static String readPayloadText(
            String relative,
            int maximumBytes
    ) throws IOException {
        return new String(
                readResourceBytes("/payload/" + relative, maximumBytes),
                StandardCharsets.UTF_8
        );
    }

    private static byte[] readResourceBytes(
            String resource,
            int maximumBytes
    ) throws IOException {
        InputStream stream =
                GtoPrismInstaller.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException(
                    "В установщике отсутствует ресурс: " + resource
            );
        }
        try (InputStream input = new BufferedInputStream(stream);
             java.io.ByteArrayOutputStream output =
                     new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (output.size() + read > maximumBytes) {
                    throw new IOException(
                            "Ресурс слишком велик: " + resource
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void selfCheck() throws Exception {
        List<DownloadEntry> downloads = loadDownloads();
        List<LockedEntry> client = loadClientLock();
        List<LockedEntry> prism = loadPrismLock();
        loadJavaLock();

        Map<String, DownloadEntry> downloadsByPath =
                new HashMap<String, DownloadEntry>();
        for (DownloadEntry download : downloads) {
            downloadsByPath.put(
                    download.destination.toLowerCase(Locale.ROOT),
                    download
            );
        }
        Set<String> clientPaths = new HashSet<String>();
        for (LockedEntry locked : client) {
            String key = locked.path.toLowerCase(Locale.ROOT);
            clientPaths.add(key);
            DownloadEntry remote = downloadsByPath.get(key);
            if (remote != null) {
                if (remote.size != locked.size
                        || !remote.sha256.equals(locked.sha256)) {
                    throw new IOException(
                            "downloads.tsv расходится с client-lock.tsv: "
                                    + locked.path
                    );
                }
            } else {
                verifyResource(
                        "/payload/" + locked.path,
                        locked.size,
                        locked.sha256
                );
            }
        }
        if (!clientPaths.equals(downloadsByPath.keySet())) {
            Set<String> extraDownloads =
                    new HashSet<String>(downloadsByPath.keySet());
            extraDownloads.removeAll(clientPaths);
            if (!extraDownloads.isEmpty()) {
                throw new IOException(
                        "downloads.tsv содержит путь вне client lock: "
                                + extraDownloads.iterator().next()
                );
            }
        }

        LockedEntry prismExe = lockMap(prism).get(
                "prismlauncher.exe"
        );
        if (prismExe == null
                || prismExe.size != PRISM_EXE_SIZE
                || !prismExe.sha256.equals(PRISM_EXE_SHA256)) {
            throw new IOException(
                    "prism-lock.tsv не закрепляет ожидаемый "
                            + "prismlauncher.exe " + PRISM_VERSION
            );
        }
        requireGtoEasyText(readPayloadText(
                "config/gtocore.yaml",
                2 * 1024 * 1024
        ));
        requireVanillaNormalDefaultsText(readPayloadText(
                "config/defaultoptions-common.toml",
                2 * 1024 * 1024
        ));
        readResourceBytes(
                "/README-LICENSED.txt",
                2 * 1024 * 1024
        );
        byte[] play = readResourceBytes(
                "/PLAY-GTO-LICENSED.bat",
                1024 * 1024
        );
        for (byte value : play) {
            int unsigned = value & 0xFF;
            if (unsigned != 9
                    && unsigned != 10
                    && unsigned != 13
                    && (unsigned < 32 || unsigned > 126)) {
                throw new IOException(
                        "PLAY-GTO-LICENSED.bat должен быть ASCII."
                );
            }
        }
        inspectPayloadEntries();
        selfCheckUpdateTransaction();
    }

    private static void selfCheckUpdateTransaction() throws Exception {
        Path sandbox = Files.createTempDirectory(
                "gto-prism-transaction-self-check-"
        );
        try {
            byte[] oldValue = "old-value".getBytes(StandardCharsets.UTF_8);
            byte[] newValue = "new-value".getBytes(StandardCharsets.UTF_8);
            Path target = sandbox.resolve("install");
            Path destination = target.resolve("config").resolve("value.txt");
            Path source = sandbox.resolve("staged-value.txt");
            Files.createDirectories(destination.getParent());
            Files.write(destination, oldValue);
            Files.write(source, newValue);

            UpdateTransaction successful =
                    new UpdateTransaction(sandbox, target);
            Path successfulBackupRoot = successful.backupRoot;
            successful.replaceFile(source, destination);
            if (!Arrays.equals(
                    Files.readAllBytes(destination),
                    newValue
            )) {
                throw new IOException(
                        "Transaction self-check did not replace a file."
                );
            }
            successful.rollback();
            if (!Arrays.equals(
                    Files.readAllBytes(destination),
                    oldValue
            ) || Files.exists(
                    successfulBackupRoot,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                throw new IOException(
                        "Transaction self-check did not restore a file."
                );
            }

            Path runtime = target.resolve("runtime");
            Path runtimeFile = runtime.resolve("old-runtime.txt");
            Files.createDirectories(runtime);
            Files.write(runtimeFile, oldValue);
            UpdateTransaction interrupted =
                    new UpdateTransaction(sandbox, target);
            boolean replaceFailed = false;
            try {
                interrupted.replaceDirectory(runtime, runtime);
            } catch (Exception expected) {
                replaceFailed = true;
            }
            if (!replaceFailed) {
                throw new IOException(
                        "Transaction self-check could not inject "
                                + "an interrupted directory replacement."
                );
            }
            interrupted.rollback();
            if (!Arrays.equals(
                    Files.readAllBytes(runtimeFile),
                    oldValue
            )) {
                throw new IOException(
                        "Transaction self-check lost an interrupted "
                                + "directory replacement."
                );
            }

            Path failingSource = sandbox.resolve("failing-value.txt");
            Files.write(failingSource, newValue);
            UpdateTransaction failing =
                    new UpdateTransaction(sandbox, target);
            failing.replaceFile(failingSource, destination);
            ReplacedPath failingItem = failing.replaced.get(0);
            Path recoveryEvidence =
                    failing.backupRoot.resolve("recovery-evidence.bin");
            moveAtomically(failingItem.backup, recoveryEvidence);
            boolean rollbackFailed = false;
            try {
                failing.rollback();
            } catch (IOException expected) {
                rollbackFailed = expected.getMessage() != null
                        && expected.getMessage().contains(
                        failing.backupRoot.toString()
                );
            }
            if (!rollbackFailed
                    || !Files.isDirectory(
                    failing.backupRoot,
                    LinkOption.NOFOLLOW_LINKS
            )
                    || !Arrays.equals(
                    Files.readAllBytes(recoveryEvidence),
                    oldValue
            )
                    || !Arrays.equals(
                    Files.readAllBytes(destination),
                    newValue
            )) {
                throw new IOException(
                        "Transaction self-check did not preserve recovery "
                                + "data after an incomplete rollback."
                );
            }
        } finally {
            deleteTree(sandbox);
        }
    }

    private static void inspectPayloadEntries() throws Exception {
        Path jarPath = ownJarPath();
        Set<String> seen = new HashSet<String>();
        int files = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("payload/")) {
                    continue;
                }
                String relative = entry.getName().substring(
                        "payload/".length()
                );
                if (relative.isEmpty()) {
                    continue;
                }
                relative = normalizeArchivePath(
                        relative,
                        entry.isDirectory()
                );
                if (relative.isEmpty()
                        || !isSafeWindowsArchivePath(relative)
                        || !seen.add(
                        relative.toLowerCase(Locale.ROOT)
                )) {
                    throw new IOException(
                            "Некорректный путь в payload: "
                                    + entry.getName()
                    );
                }
                if (!entry.isDirectory()) {
                    files++;
                }
            }
        }
        if (files < 10 || files > 5000) {
            throw new IOException(
                    "Некорректное число файлов payload: " + files
            );
        }
    }

    private static Path ownJarPath() throws Exception {
        CodeSource source =
                GtoPrismInstaller.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IOException(
                    "Не удалось определить файл установщика."
            );
        }
        URI location = source.getLocation().toURI();
        Path jarPath = Paths.get(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "Проверку нужно запускать из собранного JAR: " + jarPath
            );
        }
        return jarPath;
    }

    private static void verifyResource(
            String resource,
            long expectedSize,
            String expectedHash
    ) throws Exception {
        InputStream stream =
                GtoPrismInstaller.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException(
                    "В payload отсутствует закреплённый файл: " + resource
            );
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0L;
        try (InputStream input = new BufferedInputStream(stream)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    count += read;
                    if (count > expectedSize) {
                        throw new IOException(
                                "Ресурс payload слишком велик: " + resource
                        );
                    }
                }
            }
        }
        if (count != expectedSize
                || !toHex(digest.digest()).equals(expectedHash)) {
            throw new IOException(
                    "Не совпадает закреплённый ресурс payload: " + resource
            );
        }
    }

    private static Map<String, LockedEntry> lockMap(
            List<LockedEntry> entries
    ) {
        Map<String, LockedEntry> result =
                new HashMap<String, LockedEntry>();
        for (LockedEntry entry : entries) {
            result.put(
                    entry.path.toLowerCase(Locale.ROOT),
                    entry
            );
        }
        return result;
    }

    private static Set<String> expectedDirectoryKeys(
            List<LockedEntry> entries
    ) {
        Set<String> result = new HashSet<String>();
        for (LockedEntry entry : entries) {
            String path = entry.path.replace('\\', '/');
            int separator = path.lastIndexOf('/');
            while (separator > 0) {
                path = path.substring(0, separator);
                result.add(path.toLowerCase(Locale.ROOT));
                separator = path.lastIndexOf('/');
            }
        }
        return result;
    }

    private static void verifyLockedDirectory(
            Path root,
            List<LockedEntry> lock,
            final String skippedTopLevel
    ) throws Exception {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Отсутствует проверяемая папка: " + root
            );
        }
        final Set<String> expected = new HashSet<String>();
        for (LockedEntry entry : lock) {
            expected.add(entry.path.toLowerCase(Locale.ROOT));
            Path file = safeResolve(root, entry.path);
            if (!matches(file, entry.size, entry.sha256)) {
                throw new IOException(
                        "Не совпадает закреплённый файл: " + entry.path
                );
            }
        }

        final Set<String> actual = new HashSet<String>();
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.walkFileTree(
                normalizedRoot,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (attributes.isSymbolicLink()
                                || attributes.isOther()) {
                            throw new IOException(
                                    "Ссылки запрещены: " + directory
                            );
                        }
                        if (!directory.equals(normalizedRoot)
                                && skippedTopLevel != null
                                && directory.getParent().equals(
                                normalizedRoot
                        )
                                && directory.getFileName().toString()
                                .equalsIgnoreCase(skippedTopLevel)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (attributes.isSymbolicLink()
                                || attributes.isOther()
                                || !attributes.isRegularFile()) {
                            throw new IOException(
                                    "Некорректный файл: " + file
                            );
                        }
                        String relative = normalizedRoot.relativize(file)
                                .toString()
                                .replace('\\', '/')
                                .toLowerCase(Locale.ROOT);
                        if (!actual.add(relative)) {
                            throw new IOException(
                                    "Повторяющийся путь: " + relative
                            );
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
        );
        if (!actual.equals(expected)) {
            Set<String> extra = new HashSet<String>(actual);
            extra.removeAll(expected);
            Set<String> missing = new HashSet<String>(expected);
            missing.removeAll(actual);
            throw new IOException(
                    "В проверяемой папке есть лишние или пропущенные файлы"
                            + (!extra.isEmpty()
                            ? "; лишний: " + extra.iterator().next()
                            : "")
                            + (!missing.isEmpty()
                            ? "; отсутствует: "
                            + missing.iterator().next()
                            : "")
            );
        }
    }

    private static boolean matches(
            Path file,
            long size,
            String sha256
    ) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        return !attributes.isSymbolicLink()
                && !attributes.isOther()
                && Files.size(file) == size
                && sha256(file).equals(sha256);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02X", value & 0xFF));
        }
        return result.toString();
    }

    private static String sha256Bytes(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(bytes));
    }

    private static String embeddedClientLockHash() throws Exception {
        return sha256Bytes(
                readResourceBytes("/client-lock.tsv", 4 * 1024 * 1024)
        );
    }

    private static void verifyInstalledClientLock(Path root)
            throws Exception {
        Path installedLock = root.resolve(CLIENT_LOCK_FILE);
        if (!Files.isRegularFile(
                installedLock,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException(
                    "Отсутствует provenance-манифест установленного клиента."
            );
        }
        byte[] embedded = readResourceBytes(
                "/client-lock.tsv",
                4 * 1024 * 1024
        );
        if (Files.size(installedLock) != embedded.length
                || !sha256(installedLock).equals(
                sha256Bytes(embedded)
        )) {
            throw new IOException(
                    "Provenance-манифест клиента не совпадает "
                            + "с текущей версией установщика."
            );
        }
    }

    private static Path safeResolve(Path root, String relative)
            throws IOException {
        String normalizedRelative =
                relative.replace('/', File.separatorChar);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(normalizedRelative)
                .toAbsolutePath()
                .normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException(
                    "Путь выходит за пределы папки: " + relative
            );
        }
        return result;
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

    private static void rejectManagedLinks(Path root) throws IOException {
        rejectPathComponents(root, prismRoot(root));
        rejectPathComponents(root, userData(root));
        rejectPathComponents(root, instanceRoot(root));
        rejectPathComponents(root, gameRoot(root));
        rejectUnsafeLinks(
                prismRoot(root),
                "UserData"
        );
        rejectUnsafeLinks(javaHome(root), null);
        rejectUnsafeLinks(gameRoot(root).resolve("mods"), null);
        rejectUnsafeLinks(gameRoot(root).resolve("resourcepacks"), null);
        rejectUnsafeLinks(gameRoot(root).resolve("shaderpacks"), null);
        rejectUnsafeLinks(gameRoot(root).resolve("config"), null);
    }

    private static void rejectPathComponents(
            Path root,
            Path child
    ) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IOException("Небезопасный путь: " + child);
        }
        Path current = normalizedRoot;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            rejectSingleLink(current);
        }
        Path relative = normalizedRoot.relativize(normalizedChild);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectSingleLink(current);
            }
        }
    }

    private static void rejectSingleLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException(
                    "Символические ссылки и точки повторного анализа "
                            + "запрещены: " + path
            );
        }
    }

    private static void rejectUnsafeLinks(
            final Path root,
            final String skippedTopLevel
    ) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.walkFileTree(
                normalizedRoot,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (attributes.isSymbolicLink()
                                || attributes.isOther()) {
                            throw new IOException(
                                    "Ссылки запрещены: " + directory
                            );
                        }
                        if (!directory.equals(normalizedRoot)
                                && skippedTopLevel != null
                                && directory.getParent().equals(
                                normalizedRoot
                        )
                                && directory.getFileName().toString()
                                .equalsIgnoreCase(skippedTopLevel)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (attributes.isSymbolicLink()
                                || attributes.isOther()) {
                            throw new IOException(
                                    "Ссылки запрещены: " + file
                            );
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }

    private static void ensurePrismClosed(Path target)
            throws IOException {
        if (isProcessRunning("prismlauncher.exe")
                || isProcessRunning("prismlauncher_updater.exe")
                || isProcessRunning("prismlauncher_filelink.exe")) {
            throw new IOException(
                    "Закройте все окна Prism Launcher и повторите обновление."
            );
        }
    }

    private static boolean isProcessRunning(String imageName)
            throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.trim().isEmpty()) {
            systemRoot = System.getenv("WINDIR");
        }
        if (systemRoot == null || systemRoot.trim().isEmpty()) {
            throw new IOException(
                    "Не удалось найти системный tasklist.exe."
            );
        }
        Path tasklist = Paths.get(systemRoot)
                .resolve("System32")
                .resolve("tasklist.exe")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(
                tasklist,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException(
                    "Не найден системный tasklist.exe: " + tasklist
            );
        }
        Process process = new ProcessBuilder(
                tasklist.toString(),
                "/FI",
                "IMAGENAME eq " + imageName,
                "/FO",
                "CSV",
                "/NH"
        ).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null
                    && output.length() < 64 * 1024) {
                output.append(line).append('\n');
            }
        }
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(
                        "Не удалось проверить, закрыт ли Prism Launcher."
                );
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Проверка процессов прервана.",
                    error
            );
        }
        return process.exitValue() == 0
                && output.toString().toLowerCase(Locale.ROOT)
                .contains("\"" + imageName.toLowerCase(Locale.ROOT) + "\"");
    }

    private static void verifyOneModCanBeUpdated(Path game)
            throws Exception {
        for (LockedEntry entry : loadClientLock()) {
            if (entry.path.startsWith("mods/")) {
                verifyFileCanBeUpdated(
                        safeResolve(game, entry.path),
                        "Minecraft"
                );
                return;
            }
        }
        throw new IOException("В client lock отсутствуют моды.");
    }

    private static void verifyFileCanBeUpdated(
            Path file,
            String owner
    ) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.READ,
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
                        owner + " использует файлы сборки. "
                                + "Закройте его и повторите."
                );
            }
            lock.release();
        } catch (IOException error) {
            throw new IOException(
                    owner + " или антивирус удерживает файл "
                            + file.getFileName()
                            + ". Закройте Minecraft/Prism и повторите.",
                    error
            );
        }
    }

    private static void requireWindowsX64() throws IOException {
        String os = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "")
                .toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            throw new IOException(
                    "Этот one-click установщик предназначен для Windows."
            );
        }
        if (!(arch.equals("amd64")
                || arch.equals("x86_64")
                || arch.equals("x64"))) {
            throw new IOException(
                    "Требуется 64-битная Windows x64."
            );
        }
    }

    private static void copyTree(
            final Path source,
            final Path destination
    ) throws IOException {
        final Path normalizedSource = source.toAbsolutePath().normalize();
        final Path normalizedDestination =
                destination.toAbsolutePath().normalize();
        Files.walkFileTree(
                normalizedSource,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (attributes.isSymbolicLink()
                                || attributes.isOther()) {
                            throw new IOException(
                                    "Нельзя копировать ссылку: " + directory
                            );
                        }
                        Path relative =
                                normalizedSource.relativize(directory);
                        Files.createDirectories(
                                normalizedDestination.resolve(relative)
                        );
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        if (!attributes.isRegularFile()
                                || attributes.isSymbolicLink()
                                || attributes.isOther()) {
                            throw new IOException(
                                    "Нельзя копировать специальный файл: "
                                            + file
                            );
                        }
                        Path relative = normalizedSource.relativize(file);
                        Path target =
                                normalizedDestination.resolve(relative);
                        Files.createDirectories(target.getParent());
                        Path temporary = target.resolveSibling(
                                target.getFileName() + ".gto-copy-"
                                        + UUID.randomUUID().toString()
                                        + ".tmp"
                        );
                        try {
                            Files.copy(
                                    file,
                                    temporary,
                                    StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.COPY_ATTRIBUTES
                            );
                            moveAtomically(temporary, target);
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }

    private static void moveAtomically(
            Path source,
            Path destination
    ) throws IOException {
        Files.createDirectories(destination.getParent());
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

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
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
                }
        );
    }

    private static void writeMarker(Path root) throws Exception {
        String text = PACK_NAME + "\n"
                + "installer=licensed-prism\n"
                + "pack-version=" + PACK_VERSION + "\n"
                + "minecraft=" + MINECRAFT_VERSION + "\n"
                + "forge=" + FORGE_VERSION + "\n"
                + "prism=" + PRISM_VERSION + "\n"
                + "instance-id=" + INSTANCE_ID + "\n"
                + "client-lock-file=" + CLIENT_LOCK_FILE + "\n"
                + "client-lock-sha256=" + embeddedClientLockHash() + "\n"
                + "client-verification=passed\n"
                + "prism-verification=passed\n"
                + "java-runtime=" + GAME_JAVA_MAJOR + "-x64\n"
                + "java-runtime-verification=passed\n"
                + "pack-mode-default=GTO-Easy\n"
                + "vanilla-difficulty-default=NORMAL\n"
                + "existing-world-mode=preserved\n";
        writeUtf8Atomically(root.resolve(MARKER_FILE), text);
    }

    private static void writeRecoveryMarker(Path root)
            throws IOException {
        String text = PACK_NAME + "\n"
                + "installer=licensed-prism\n"
                + "target-version=" + PACK_VERSION + "\n"
                + "status=install-in-progress\n";
        writeUtf8Atomically(root.resolve(RECOVERY_FILE), text);
    }

    private static boolean hasSupportedMarker(Path root) {
        try {
            verifySupportedMarker(root);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void verifySupportedMarker(Path root)
            throws Exception {
        try {
            verifyMarker(root);
            return;
        } catch (Exception currentError) {
            for (String previous : PREVIOUS_PACK_VERSIONS) {
                if (hasPersistedMarkerVersion(root, previous)) {
                    return;
                }
            }
            throw currentError;
        }
    }

    private static boolean hasPersistedMarkerVersion(
            Path root,
            String version
    ) throws Exception {
        Path marker = root.resolve(MARKER_FILE);
        Path clientLock = root.resolve(CLIENT_LOCK_FILE);
        if (!Files.isRegularFile(
                marker,
                LinkOption.NOFOLLOW_LINKS
        )
                || !Files.isRegularFile(
                clientLock,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return false;
        }
        List<String> lines = Files.readAllLines(
                marker,
                StandardCharsets.UTF_8
        );
        List<String> common = Arrays.asList(
                PACK_NAME,
                "installer=licensed-prism",
                "pack-version=" + version,
                "minecraft=" + MINECRAFT_VERSION,
                "forge=" + FORGE_VERSION,
                "instance-id=" + INSTANCE_ID,
                "client-lock-file=" + CLIENT_LOCK_FILE,
                "client-verification=passed",
                "pack-mode-default=GTO-Easy",
                "vanilla-difficulty-default=NORMAL"
        );
        if (!lines.containsAll(common)) {
            return false;
        }
        String expectedHash = singleMarkerValue(
                lines,
                "client-lock-sha256="
        );
        return expectedHash != null
                && expectedHash.matches("[A-F0-9]{64}")
                && sha256(clientLock).equals(expectedHash);
    }

    private static String singleMarkerValue(
            List<String> lines,
            String prefix
    ) {
        String result = null;
        for (String line : lines) {
            if (!line.startsWith(prefix)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = line.substring(prefix.length());
        }
        return result;
    }

    private static boolean hasRecoveryMarker(Path root) {
        Path marker = root.resolve(RECOVERY_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(
                    marker,
                    StandardCharsets.UTF_8
            );
            return lines.contains(PACK_NAME)
                    && lines.contains("installer=licensed-prism")
                    && lines.contains(
                    "target-version=" + PACK_VERSION
            )
                    && lines.contains("status=install-in-progress");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void verifyMarker(Path root) throws Exception {
        Path marker = root.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Отсутствует маркер лицензированной установки."
            );
        }
        List<String> lines = Files.readAllLines(
                marker,
                StandardCharsets.UTF_8
        );
        List<String> required = Arrays.asList(
                PACK_NAME,
                "installer=licensed-prism",
                "pack-version=" + PACK_VERSION,
                "minecraft=" + MINECRAFT_VERSION,
                "forge=" + FORGE_VERSION,
                "prism=" + PRISM_VERSION,
                "instance-id=" + INSTANCE_ID,
                "client-lock-file=" + CLIENT_LOCK_FILE,
                "client-lock-sha256=" + embeddedClientLockHash(),
                "client-verification=passed",
                "prism-verification=passed",
                "java-runtime=" + GAME_JAVA_MAJOR + "-x64",
                "java-runtime-verification=passed",
                "pack-mode-default=GTO-Easy",
                "vanilla-difficulty-default=NORMAL",
                "existing-world-mode=preserved"
        );
        if (!lines.containsAll(required)) {
            throw new IOException(
                    "Маркер установки повреждён или относится к другой версии."
            );
        }
        verifyInstalledClientLock(root);
    }

    private static void launchPrism(Path root) throws IOException {
        Path executable = prismRoot(root).resolve("prismlauncher.exe");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Не найден prismlauncher.exe.");
        }
        try {
            verifyInstanceMetadata(instanceRoot(root));
        } catch (Exception error) {
            throw new IOException(
                    "Профиль GTO не прошёл проверку перед запуском.",
                    error
            );
        }
        ProcessBuilder builder = new ProcessBuilder(
                executable.toString(),
                "--show",
                INSTANCE_ID
        );
        builder.directory(prismRoot(root).toFile());
        builder.environment().remove("PRISMLAUNCHER_DATA_DIR");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("CLASSPATH");
        builder.start();
    }

    private static String launchInstructions(Path target) {
        return "Готово. Сборка, Prism Launcher и приватная Java 21 "
                + "проверены.\n\n"
                + "При первом запуске добавьте лицензионную учётную запись:\n"
                + "кнопка аккаунта → Manage Accounts → Add Microsoft.\n\n"
                + "Затем выберите «" + PACK_NAME + "» и нажмите Launch.\n"
                + "В дальнейшем запускайте PLAY-GTO-LICENSED.bat из:\n"
                + target;
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

        private SwingProgress(
                JTextArea logArea,
                JProgressBar progressBar
        ) {
            this.logArea = logArea;
            this.progressBar = progressBar;
        }

        @Override
        public void log(final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    logArea.append(
                            message + System.lineSeparator()
                    );
                    logArea.setCaretPosition(
                            logArea.getDocument().getLength()
                    );
                }
            });
        }

        @Override
        public void update(
                final int percent,
                final String label
        ) {
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

        private LockedEntry(
                String path,
                long size,
                String sha256
        ) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private enum InstallationKind {
        CLEAN,
        RECOVER,
        UPDATE
    }

    private static final class ReplacedPath {
        private final Path destination;
        private final Path backup;
        private final boolean hadPrevious;

        private ReplacedPath(
                Path destination,
                Path backup,
                boolean hadPrevious
        ) {
            this.destination = destination;
            this.backup = backup;
            this.hadPrevious = hadPrevious;
        }
    }

    private static final class UpdateTransaction {
        private final Path target;
        private final Path backupRoot;
        private final List<ReplacedPath> replaced =
                new ArrayList<ReplacedPath>();
        private boolean finished;

        private UpdateTransaction(Path parent, Path target)
                throws IOException {
            this.target = target.toAbsolutePath().normalize();
            this.backupRoot = parent.resolve(
                    "." + target.getFileName() + ".gto-update-backup-"
                            + UUID.randomUUID().toString()
            ).toAbsolutePath().normalize();
            if (!backupRoot.getParent().equals(
                    parent.toAbsolutePath().normalize()
            )) {
                throw new IOException(
                        "Небезопасный путь резервной копии."
                );
            }
            Files.createDirectories(backupRoot);
        }

        private void replaceFile(Path source, Path destination)
                throws Exception {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Нет подготовленного файла: " + source
                );
            }
            Path normalizedDestination =
                    checkedDestination(destination);
            Path backup = backupFor(normalizedDestination);
            boolean hadPrevious = Files.exists(
                    normalizedDestination,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (hadPrevious) {
                rejectSingleLink(normalizedDestination);
                if (!Files.isRegularFile(
                        normalizedDestination,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    throw new IOException(
                            "Ожидался обычный файл: "
                                    + normalizedDestination
                    );
                }
                Files.createDirectories(backup.getParent());
                Files.copy(
                        normalizedDestination,
                        backup,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            }
            replaced.add(new ReplacedPath(
                    normalizedDestination,
                    backup,
                    hadPrevious
            ));
            moveAtomically(source, normalizedDestination);
        }

        private void replaceDirectory(Path source, Path destination)
                throws Exception {
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Нет подготовленной папки: " + source
                );
            }
            replaceDirectoryByMovingOld(source, destination);
        }

        private void replaceDirectoryByMovingOld(
                Path source,
                Path destination
        )
                throws Exception {
            Path normalizedDestination =
                    checkedDestination(destination);
            Path backup = backupFor(normalizedDestination);
            boolean hadPrevious = Files.exists(
                    normalizedDestination,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (hadPrevious) {
                rejectSingleLink(normalizedDestination);
                Files.createDirectories(backup.getParent());
                moveAtomically(normalizedDestination, backup);
            }
            replaced.add(new ReplacedPath(
                    normalizedDestination,
                    backup,
                    hadPrevious
            ));
            moveAtomically(source, normalizedDestination);
        }

        private Path checkedDestination(Path destination)
                throws IOException {
            Path normalizedDestination =
                    destination.toAbsolutePath().normalize();
            if (!normalizedDestination.startsWith(target)
                    || normalizedDestination.equals(target)) {
                throw new IOException(
                        "Транзакция вышла за пределы установки: "
                                + destination
                );
            }
            return normalizedDestination;
        }

        private Path backupFor(Path destination) throws IOException {
            Path relative = target.relativize(destination);
            Path backup = backupRoot.resolve(relative)
                    .toAbsolutePath()
                    .normalize();
            if (!backup.startsWith(backupRoot)
                    || backup.equals(backupRoot)) {
                throw new IOException(
                        "Небезопасный путь резервной копии: " + backup
                );
            }
            return backup;
        }

        private void rollback() throws Exception {
            if (finished) {
                return;
            }
            Exception failure = null;
            for (int index = replaced.size() - 1;
                    index >= 0;
                    index--) {
                ReplacedPath item = replaced.get(index);
                try {
                    if (item.hadPrevious) {
                        if (!Files.exists(
                                item.backup,
                                LinkOption.NOFOLLOW_LINKS
                        )) {
                            throw new IOException(
                                    "Rollback backup is missing: "
                                            + item.backup
                            );
                        }
                        deleteTree(item.destination);
                        moveAtomically(item.backup, item.destination);
                    } else {
                        deleteTree(item.destination);
                    }
                } catch (Exception error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            finished = true;
            if (failure != null) {
                throw new IOException(
                        "Automatic rollback was incomplete. "
                                + "The recovery backup was preserved at: "
                                + backupRoot,
                        failure
                );
            }
            deleteTree(backupRoot);
        }

        private void commit() throws IOException {
            if (finished) {
                return;
            }
            finished = true;
            deleteTree(backupRoot);
        }
    }

    private static final class CliOptions {
        private final Path target;
        private final boolean nonInteractive;
        private final boolean noLaunch;
        private final boolean selfCheck;

        private CliOptions(
                Path target,
                boolean nonInteractive,
                boolean noLaunch,
                boolean selfCheck
        ) {
            this.target = target;
            this.nonInteractive = nonInteractive;
            this.noLaunch = noLaunch;
            this.selfCheck = selfCheck;
        }

        private static CliOptions parse(String[] args) {
            Path target = null;
            boolean nonInteractive = false;
            boolean noLaunch = false;
            boolean selfCheck = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--target".equals(argument)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException(
                                "После --target требуется путь."
                        );
                    }
                    target = Paths.get(args[++index])
                            .toAbsolutePath()
                            .normalize();
                } else if ("--non-interactive".equals(argument)) {
                    nonInteractive = true;
                } else if ("--no-launch".equals(argument)) {
                    noLaunch = true;
                } else if ("--self-check".equals(argument)) {
                    selfCheck = true;
                } else if ("--help".equals(argument)
                        || "-h".equals(argument)) {
                    printUsage();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException(
                            "Неизвестный аргумент: " + argument
                    );
                }
            }
            if (selfCheck && (target != null || nonInteractive)) {
                throw new IllegalArgumentException(
                        "--self-check нельзя сочетать с установкой."
                );
            }
            return new CliOptions(
                    target,
                    nonInteractive,
                    noLaunch,
                    selfCheck
            );
        }
    }
}
