package qupath.ext.tiatoolbox.core;

/**
 * Callbacks invoked by the Python sidecar while inference is running. Every
 * call comes in on a Py4J worker thread, never the JavaFX application thread —
 * implementations must marshal UI updates back onto FX themselves.
 */
public interface ProgressListener {

    /** Coarse status update (e.g. "Loading model"). */
    void onStatus(String message);

    /** Periodic heartbeat with elapsed seconds since {@code .run()} started. */
    void onHeartbeat(int elapsedSeconds);
}
