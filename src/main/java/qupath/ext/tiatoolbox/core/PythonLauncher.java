package qupath.ext.tiatoolbox.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spawns the {@code qupath_tiatoolbox} Python module as a subprocess and waits
 * for it to print its {@code READY port=N} line on stdout.
 */
public final class PythonLauncher {

    private static final Logger logger = LoggerFactory.getLogger(PythonLauncher.class);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

    private PythonLauncher() {}

    public record Started(Process process, int port) {}

    /**
     * Start {@code <pythonExe> -m qupath_tiatoolbox --python-port 0} and parse
     * the bound port from stdout. The returned {@link Process} owns both stdout
     * and stderr drains — caller must ultimately {@code destroy()} it.
     */
    public static Started start(Path pythonExe) throws IOException, InterruptedException {
        if (!Files.isExecutable(pythonExe)) {
            throw new IOException("Python executable not found or not executable: " + pythonExe);
        }
        var cmd = List.of(pythonExe.toString(), "-m", "qupath_tiatoolbox", "--python-port", "0");
        logger.info("Starting Python sidecar: {}", cmd);

        var pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        var proc = pb.start();

        var portRef = new AtomicReference<Integer>();
        var stdoutDrain = new Thread(() -> drainStdout(proc, portRef), "tiatoolbox-py-stdout");
        var stderrDrain = new Thread(() -> drainStderr(proc), "tiatoolbox-py-stderr");
        stdoutDrain.setDaemon(true);
        stderrDrain.setDaemon(true);
        stdoutDrain.start();
        stderrDrain.start();

        var deadline = Instant.now().plus(READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Integer p = portRef.get();
            if (p != null) {
                logger.info("Python sidecar READY on port {}", p);
                return new Started(proc, p);
            }
            if (!proc.isAlive()) {
                throw new IOException("Python sidecar exited before READY (code " + proc.exitValue() + ")");
            }
            Thread.sleep(100);
        }
        proc.destroy();
        throw new IOException("Python sidecar did not become ready within " + READY_TIMEOUT);
    }

    private static void drainStdout(Process proc, AtomicReference<Integer> portRef) {
        try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("READY port=")) {
                    portRef.compareAndSet(null, Integer.parseInt(line.substring("READY port=".length()).trim()));
                } else {
                    logger.info("[python] {}", line);
                }
            }
        } catch (IOException e) {
            logger.debug("stdout drain ended", e);
        }
    }

    private static void drainStderr(Process proc) {
        try (var r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                logger.info("[python] {}", line);
            }
        } catch (IOException e) {
            logger.debug("stderr drain ended", e);
        }
    }
}
