package qupath.ext.tiatoolbox.install;

import qupath.lib.gui.prefs.PathPrefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves filesystem locations for the bundled Python runtime. The runtime
 * lives under the QuPath user directory so it survives extension upgrades and
 * is naturally scoped to the user account.
 */
public final class RuntimePaths {

    /** Folder name under the QuPath user directory. */
    public static final String RUNTIME_DIR_NAME = "tiatoolbox-runtime";

    /** Folder name under the QuPath user directory for inference outputs. */
    public static final String RESULTS_DIR_NAME = "tiatoolbox-results";

    private RuntimePaths() {}

    /** Root directory: {@code <QuPath user dir>/tiatoolbox-runtime/}. */
    public static Path runtimeRoot() {
        return userDir().resolve(RUNTIME_DIR_NAME);
    }

    /** Root for inference outputs: {@code <QuPath user dir>/tiatoolbox-results/}. */
    public static Path resultsRoot() {
        return userDir().resolve(RESULTS_DIR_NAME);
    }

    /** The {@code pyproject.toml} written from the bundled template. */
    public static Path projectFile() {
        return runtimeRoot().resolve("pyproject.toml");
    }

    /** Sidecar source extracted from JAR resources. */
    public static Path sidecarDir() {
        return runtimeRoot().resolve("sidecar");
    }

    /** uv-managed virtual environment. */
    public static Path venvDir() {
        return runtimeRoot().resolve(".venv");
    }

    /** Cache uv uses for wheels and Python downloads. */
    public static Path uvCacheDir() {
        return runtimeRoot().resolve("uv-cache");
    }

    /** Where to extract the bundled uv binary for execution. */
    public static Path uvBinary() {
        return runtimeRoot().resolve(isWindows() ? "uv.exe" : "uv");
    }

    /** Install logs (stdout/stderr of uv sync). */
    public static Path logsDir() {
        return runtimeRoot().resolve("logs");
    }

    /** Python interpreter inside the venv. */
    public static Path venvPython() {
        return venvDir().resolve(isWindows() ? "Scripts/python.exe" : "bin/python");
    }

    /** Installed runtime Python, or {@code null} if the runtime is not ready. */
    public static Path installedPython() {
        var python = venvPython();
        return Files.isExecutable(python) ? python : null;
    }

    /**
     * Resolve the QuPath user directory. Prefer the value the user configured
     * in QuPath Preferences; fall back to the platform default.
     */
    private static Path userDir() {
        var configured = PathPrefs.userPathProperty().get();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return PathPrefs.getDefaultQuPathUserDirectory();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
