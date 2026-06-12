package qupath.ext.tiatoolbox.core;

/**
 * Java view of the Python sidecar's entry point. Method names must match the
 * Python {@code TIATask} class verbatim — Py4J resolves calls by name.
 */
public interface TiaRunner {

    /** Reachability check; returns {@code "pong"}. */
    String ping();

    /**
     * Run one engine on one WSI. Blocks until inference finishes.
     *
     * @param requestJson serialised {@link InferenceRequest}
     * @param listener    progress listener, called from the Python thread
     * @return JSON response string ({@code {"status":"ok","geojson":[...]}}
     *         or {@code {"status":"error",...}})
     */
    String runInference(String requestJson, ProgressListener listener);

    /** Run one QuPath project training request. */
    String runTraining(String requestJson, ProgressListener listener);

    /** Request best-effort cancellation of the active training job. */
    void cancelTraining();

    /** Ask the sidecar to exit. The Process should terminate shortly after. */
    void shutdown();
}
