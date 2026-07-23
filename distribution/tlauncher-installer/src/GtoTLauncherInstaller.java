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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class GtoTLauncherInstaller {
    private static final String PACK_NAME = "GregTech Odyssey — Friends Edition";
    private static final String PACK_VERSION = "1.0.1";
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION = "47.4.20";
    private static final String MARKER_FILE = "GTO-FRIENDS-INSTALLED.txt";
    private static final String USER_AGENT =
            "GTO-Friends-TLauncher-Installer/1.0.1 "
                    + "(https://github.com/ChiefSparrow/gregtech-odyssey-friends)";

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
        validateTarget(target);

        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Не удалось определить родительскую папку: " + target);
        }
        Files.createDirectories(parent);

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
            progress.log("Проверка временной сборки пройдена.");

            progress.update(92, "Копирование");
            Files.createDirectories(target);
            Files.deleteIfExists(target.resolve(MARKER_FILE));
            copyTree(staging, target);

            progress.update(97, "Финальная проверка");
            verifyClient(target);
            writeMarker(target);
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
        if (hasValidMarker(target)) {
            return;
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

    private static boolean hasValidMarker(Path target) {
        Path marker = target.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            return lines.contains(PACK_NAME)
                    && lines.contains("pack-version=" + PACK_VERSION)
                    && lines.contains("minecraft=" + MINECRAFT_VERSION)
                    && lines.contains("forge=" + FORGE_VERSION)
                    && lines.contains("client-verification=passed");
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
        try {
            if (!directoryHasFiles(normalMinecraft.resolve("mods"))
                    && !directoryHasFiles(normalMinecraft.resolve("config"))) {
                return normalMinecraft;
            }
        } catch (IOException ignored) {
        }
        return root.resolve(".gto-friends-" + PACK_VERSION);
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
                    && !host.endsWith(".forgecdn.net")) {
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
                Files.copy(
                        file,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
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
            writer.write("client-verification=passed");
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

    private static String launchInstructions(Path target) {
        return "Готово. Файлы проверены по SHA-256.\n\n"
                + "1. Откройте настройки TLauncher.\n"
                + "2. Укажите игровую папку:\n" + target + "\n"
                + "3. Выберите Forge " + MINECRAFT_VERSION + "-" + FORGE_VERSION
                + " без значка TL.\n"
                + "4. Выделите 8192–12288 МБ RAM (рекомендуется 10240).\n"
                + "5. Введите постоянный ник и запускайте игру.";
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
