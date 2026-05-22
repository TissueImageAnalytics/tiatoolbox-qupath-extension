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
        var root = RuntimePaths.runtimeRoot();
        Files.createDirectories(root);
        Files.createDirectories(RuntimePaths.logsDir());

        log.accept("Runtime directory: " + root);

        extractUvBinary();
        extractSidecarSources();
        writeProjectFile();
        runUvSync();

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

    private void writeProjectFile() throws IOException {
        var contents = readResourceText(RES_BASE + "pyproject.toml");
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
