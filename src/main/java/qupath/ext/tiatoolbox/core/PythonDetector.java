package qupath.ext.tiatoolbox.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the Python interpreter belonging to the {@code tiatoolbox-qupath}
 * conda environment without launching any subprocess. Returns the first
 * existing executable found in the standard conda install locations across
 * Linux, macOS, and Windows.
 */
public final class PythonDetector {

    private static final Logger logger = LoggerFactory.getLogger(PythonDetector.class);

    /** Canonical conda env name expected by the extension. */
    public static final String ENV_NAME = "tiatoolbox-qupath";

    private PythonDetector() {}

    /** First valid candidate path, or {@code null} if none exist. */
    public static Path detect() {
        for (Path p : candidates()) {
            if (Files.isExecutable(p)) {
                logger.info("Detected Python for {}: {}", ENV_NAME, p);
                return p;
            }
        }
        logger.debug("No '{}' conda env found in standard locations", ENV_NAME);
        return null;
    }

    /** Candidate paths in priority order. Exposed for tests. */
    static List<Path> candidates() {
        var home = Path.of(System.getProperty("user.home"));
        var list = new ArrayList<Path>();

        // Linux + macOS conda flavours under $HOME.
        list.add(home.resolve("anaconda3/envs/" + ENV_NAME + "/bin/python"));
        list.add(home.resolve("miniconda3/envs/" + ENV_NAME + "/bin/python"));
        list.add(home.resolve("miniforge3/envs/" + ENV_NAME + "/bin/python"));
        list.add(home.resolve("mambaforge/envs/" + ENV_NAME + "/bin/python"));

        // macOS Homebrew Cask installs.
        list.add(home.resolve("opt/anaconda3/envs/" + ENV_NAME + "/bin/python"));
        list.add(Path.of("/opt/homebrew/Caskroom/miniforge/base/envs/"
                + ENV_NAME + "/bin/python"));
        list.add(Path.of("/opt/anaconda3/envs/" + ENV_NAME + "/bin/python"));

        // Sibling of whatever env is currently active.
        var prefix = System.getenv("CONDA_PREFIX");
        if (prefix != null && !prefix.isBlank()) {
            var active = Path.of(prefix);
            if (active.getFileName() != null
                    && active.getParent() != null
                    && "envs".equals(active.getParent().getFileName().toString())) {
                list.add(active.getParent().resolve(ENV_NAME + "/bin/python"));
            }
        }

        // Windows.
        list.add(home.resolve("anaconda3/envs/" + ENV_NAME + "/python.exe"));
        list.add(home.resolve("miniconda3/envs/" + ENV_NAME + "/python.exe"));
        list.add(home.resolve("miniforge3/envs/" + ENV_NAME + "/python.exe"));

        return list;
    }
}
