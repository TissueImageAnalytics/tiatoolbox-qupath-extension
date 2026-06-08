package qupath.ext.tiatoolbox.install;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Sets up the bundled Python runtime: extracts the uv binary and sidecar
 * source from JAR resources, writes {@code pyproject.toml}, then runs
 * {@code uv sync} against it.
 *
 * <p>This is pure logic — no JavaFX. A {@code Consumer<String>} accepts
 * progress lines so the caller (CLI test harness or FX wizard) can route
 * them wherever it wants.
 */
public final class RuntimeInstaller {

    /** Root path of the JAR resources holding runtime assets. */
    private static final String RES_BASE = "qupath/ext/tiatoolbox/runtime/";

    /** Python version uv should provision into the venv. */
    private static final String TARGET_PYTHON = "3.11";

    /** Source override written into site-packages for editable local clones. */
    private static final String LOCAL_TIA_PTH = "qupath_tiatoolbox_local_tiatoolbox.pth";

    private final Consumer<String> log;
    private volatile Process process;

    public RuntimeInstaller(Consumer<String> log) {
        this.log = Objects.requireNonNull(log);
    }

    /**
     * Install (or repair) the runtime. Returns the path to the venv's Python
     * executable on success; throws on any failure.
     */
    public Path install() throws IOException, InterruptedException {
        return install(RuntimeInstallOptions.defaultInstall());
    }

    /**
     * Install (or repair) the runtime with the supplied options. Returns the
     * path to the venv's Python executable on success; throws on any failure.
     */
    public Path install(RuntimeInstallOptions options) throws IOException, InterruptedException {
        options = Objects.requireNonNull(options);
        var root = RuntimePaths.runtimeRoot();
        Files.createDirectories(root);
        Files.createDirectories(RuntimePaths.logsDir());

        log.accept("Runtime directory: " + root);
        if (options.useLocalTiatoolboxClone()) {
            log.accept("TIAToolbox source: " + options.localTiatoolboxClone()
                    + (options.editableLocalClone() ? " (editable source override)" : ""));
            validateLocalTiatoolboxClone(options.localTiatoolboxClone());
        }

        extractUvBinary();
        extractSidecarSources();
        writeProjectFile(options);
        runUvSync();
        configureLocalTiatoolboxOverride(options);

        var python = RuntimePaths.venvPython();
        if (!Files.isExecutable(python)) {
            throw new IOException("uv sync completed but venv python is missing at " + python);
        }
        log.accept("Done. Runtime is ready at " + root);
        return python;
    }

    /** Request cancellation of the running uv process, if any. */
    public void cancel() {
        var p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    // -- Steps ----------------------------------------------------------------

    private void extractUvBinary() throws IOException {
        var resourceName = uvResourceName();
        var target = RuntimePaths.uvBinary();
        log.accept("Extracting uv binary (" + resourceName + ")…");
        try (var in = openResource(resourceName)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!RuntimePaths.isWindows()) {
            target.toFile().setExecutable(true, true);
        }
    }

    private void extractSidecarSources() throws IOException {
        var sidecarDir = RuntimePaths.sidecarDir();
        if (Files.isDirectory(sidecarDir)) {
            // Sidecar source rarely changes between extension versions, but
            // delete-and-re-extract keeps things simple and avoids stale .py.
            deleteRecursive(sidecarDir);
        }
        log.accept("Extracting sidecar source…");
        var manifest = readResourceText(RES_BASE + "sidecar/MANIFEST");
        var lines = manifest.lines().filter(s -> !s.isBlank()).toList();
        for (var rel : lines) {
            var dst = sidecarDir.resolve(rel);
            Files.createDirectories(dst.getParent());
            try (var in = openResource("sidecar/" + rel)) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeProjectFile(RuntimeInstallOptions options) throws IOException {
        var contents = readResourceText(RES_BASE + "pyproject.toml");
        if (options.useLocalTiatoolboxClone() && !options.editableLocalClone()) {
            contents = withLocalTiatoolboxSource(contents, options);
        }
        Files.writeString(RuntimePaths.projectFile(), contents, StandardCharsets.UTF_8);
        log.accept("Wrote pyproject.toml");
    }

    private void runUvSync() throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.add(RuntimePaths.uvBinary().toString());
        cmd.add("sync");
        cmd.add("--python");
        cmd.add(TARGET_PYTHON);
        cmd.add("--project");
        cmd.add(RuntimePaths.runtimeRoot().toString());
        cmd.add("--reinstall-package");
        cmd.add("qupath-tiatoolbox");

        log.accept("Running: " + String.join(" ", cmd));

        var pb = new ProcessBuilder(cmd);
        pb.environment().remove("PYTHONHOME");
        pb.environment().remove("PYTHONPATH");
        pb.environment().put("PYTHONNOUSERSITE", "1");
        pb.environment().put("UV_CACHE_DIR", RuntimePaths.uvCacheDir().toString());
        // uv installs Python via python-build-standalone — store it under our root.
        pb.environment().put("UV_PYTHON_INSTALL_DIR",
                RuntimePaths.runtimeRoot().resolve("python").toString());
        // Suppress interactive prompts.
        pb.environment().put("UV_NO_PROGRESS", "1");
        pb.directory(RuntimePaths.runtimeRoot().toFile());
        pb.redirectErrorStream(true);

        var proc = pb.start();
        process = proc;
        try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
            }
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("uv sync exited with code " + code);
            }
        } catch (InterruptedException e) {
            cancel();
            try {
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } finally {
                Thread.currentThread().interrupt();
            }
            throw e;
        } finally {
            process = null;
        }
    }

    private void configureLocalTiatoolboxOverride(RuntimeInstallOptions options)
            throws IOException, InterruptedException {
        var sitePackages = sitePackagesDir();
        var pth = sitePackages.resolve(LOCAL_TIA_PTH);

        if (!options.useLocalTiatoolboxClone() || !options.editableLocalClone()) {
            Files.deleteIfExists(pth);
            return;
        }

        Files.createDirectories(sitePackages);
        var source = options.localTiatoolboxClone().toString();
        var contents = "import sys; p = " + pythonString(source)
                + "; sys.path.remove(p) if p in sys.path else None; sys.path.insert(0, p)"
                + System.lineSeparator();
        Files.writeString(pth, contents, StandardCharsets.UTF_8);
        log.accept("Installed editable TIAToolbox source override: " + pth);
    }

    private Path sitePackagesDir() throws IOException, InterruptedException {
        var cmd = java.util.List.of(
                RuntimePaths.venvPython().toString(),
                "-c",
                "import sysconfig; print(sysconfig.get_paths()[\"purelib\"])"
        );
        var pb = new ProcessBuilder(cmd);
        pb.environment().remove("PYTHONHOME");
        pb.environment().remove("PYTHONPATH");
        pb.environment().put("PYTHONNOUSERSITE", "1");
        pb.directory(RuntimePaths.runtimeRoot().toFile());
        pb.redirectErrorStream(true);

        var proc = pb.start();
        try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            var output = reader.lines().toList();
            var code = proc.waitFor();
            if (code != 0 || output.isEmpty() || output.get(0).isBlank()) {
                throw new IOException("Could not locate venv site-packages: "
                        + String.join("\n", output));
            }
            return Path.of(output.get(0).trim());
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    // -- Helpers --------------------------------------------------------------

    private static String uvResourceName() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "uv-win-x86_64.exe";
        }
        if (os.contains("mac")) {
            return arch.contains("aarch") || arch.contains("arm")
                    ? "uv-macos-arm64"
                    : "uv-macos-x86_64";
        }
        return "uv-linux-x86_64";
    }

    private static InputStream openResource(String name) throws IOException {
        var path = RES_BASE + name;
        var in = RuntimeInstaller.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Missing JAR resource: " + path);
        }
        return in;
    }

    private static String readResourceText(String fullPath) throws IOException {
        try (var in = RuntimeInstaller.class.getClassLoader().getResourceAsStream(fullPath)) {
            if (in == null) throw new IOException("Missing JAR resource: " + fullPath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void validateLocalTiatoolboxClone(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException("Local TIAToolbox clone is not a directory: " + path);
        }
        if (!Files.isRegularFile(path.resolve("pyproject.toml"))) {
            throw new IOException("Local TIAToolbox clone must contain pyproject.toml: " + path);
        }
    }

    private static String withLocalTiatoolboxSource(String contents, RuntimeInstallOptions options) {
        var sourceLine = "tiatoolbox = { path = "
                + tomlString(options.localTiatoolboxClone().toString())
                + (options.editableLocalClone() ? ", editable = true" : "")
                + " }";
        var lines = contents.split("\\R", -1);
        var out = new StringBuilder(contents.length() + sourceLine.length() + 32);
        var inSources = false;
        var sawSources = false;
        var sourceWritten = false;

        for (var line : lines) {
            var trimmed = line.trim();
            var startsTable = trimmed.startsWith("[") && !trimmed.startsWith("#");

            if (inSources && startsTable && !trimmed.equals("[tool.uv.sources]")) {
                if (!sourceWritten) {
                    out.append(sourceLine).append(System.lineSeparator());
                    sourceWritten = true;
                }
                inSources = false;
            }

            if (trimmed.equals("[tool.uv.sources]")) {
                inSources = true;
                sawSources = true;
            }

            if (inSources && isTomlKey(line, "tiatoolbox")) {
                if (!sourceWritten) {
                    out.append(sourceLine).append(System.lineSeparator());
                    sourceWritten = true;
                }
                continue;
            }

            out.append(line).append(System.lineSeparator());
        }

        if (sawSources && inSources && !sourceWritten) {
            out.append(sourceLine).append(System.lineSeparator());
        } else if (!sawSources) {
            out.append(System.lineSeparator())
                    .append("[tool.uv.sources]").append(System.lineSeparator())
                    .append(sourceLine).append(System.lineSeparator());
        }

        return out.toString();
    }

    private static boolean isTomlKey(String line, String key) {
        var trimmed = line.trim();
        if (!trimmed.startsWith(key) || trimmed.length() == key.length()) {
            return false;
        }
        var rest = trimmed.substring(key.length());
        return rest.startsWith("=")
                || (!rest.isEmpty() && Character.isWhitespace(rest.charAt(0))
                && rest.trim().startsWith("="));
    }

    private static String tomlString(String value) {
        var out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String pythonString(String value) {
        var out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            var entries = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (var p : entries) {
                Files.deleteIfExists(p);
            }
        }
    }
}
