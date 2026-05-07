package qupath.ext.tiatoolbox.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import py4j.ClientServer;
import py4j.Protocol;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Owns the lifecycle of the Python sidecar process and the Py4J
 * {@link ClientServer} that talks to it. One instance per extension load —
 * lazily started on first {@link #runner()} call, shared by subsequent runs.
 */
public final class BridgeManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BridgeManager.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final Path pythonExe;
    private Process process;
    private ClientServer clientServer;
    private TiaRunner runner;

    public BridgeManager(Path pythonExe) {
        this.pythonExe = pythonExe;
    }

    public synchronized TiaRunner runner() throws Exception {
        if (runner != null && process != null && process.isAlive()) {
            return runner;
        }
        closeQuietly();

        var started = PythonLauncher.start(pythonExe);
        this.process = started.process();
        int pythonPort = started.port();

        this.clientServer = new ClientServer.ClientServerBuilder()
                .javaPort(0)
                .pythonPort(pythonPort)
                .build();
        // Py4J's ClientServer.getPythonServerEntryPoint(Class[]) builds the
        // proxy with the *system* classloader, which can't see TiaRunner —
        // QuPath loads our jar in a child classloader. Bypass it and use the
        // Gateway directly with the right ClassLoader.
        var gateway = clientServer.getJavaServer().getGateway();
        var loader = TiaRunner.class.getClassLoader();
        var proxy = (TiaRunner) gateway.createProxy(
                loader,
                new Class[]{TiaRunner.class},
                Protocol.ENTRY_POINT_OBJECT_ID);

        // Sanity-check the channel before handing the proxy to callers.
        var pong = proxy.ping();
        if (!"pong".equals(pong)) {
            close();
            throw new IllegalStateException("Unexpected ping response: " + pong);
        }
        logger.info("Bridge ready (python pid {}, port {})", process.pid(), pythonPort);
        this.runner = proxy;
        return runner;
    }

    @Override
    public synchronized void close() {
        try {
            if (runner != null) {
                try {
                    runner.shutdown();
                } catch (Exception e) {
                    logger.debug("shutdown() call failed (ignored)", e);
                }
            }
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (clientServer != null) {
            try {
                clientServer.shutdown();
            } catch (Exception e) {
                logger.debug("ClientServer.shutdown failed (ignored)", e);
            }
            clientServer = null;
        }
        if (process != null) {
            try {
                if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        runner = null;
    }
}
