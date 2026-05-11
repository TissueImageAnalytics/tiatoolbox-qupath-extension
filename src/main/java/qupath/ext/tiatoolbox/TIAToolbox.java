package qupath.ext.tiatoolbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.core.BridgeManager;
import qupath.ext.tiatoolbox.core.InferenceRequest;
import qupath.ext.tiatoolbox.core.InferenceResponse;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.core.ResultImporter;
import qupath.ext.tiatoolbox.ui.ModelInfo;
import qupath.ext.tiatoolbox.ui.TIAPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Public scripting API for the TIAToolbox QuPath extension.
 *
 * <p>This is the single entry point used by both the GUI dialog and Groovy
 * scripts; both code paths build a {@link TIAToolbox} via {@link #builder()}
 * and call one of the {@code run(...)} methods. The Python sidecar is owned
 * as a JVM-wide singleton — first call starts it, subsequent calls reuse it,
 * a shutdown hook tears it down on JVM exit.
 *
 * <h3>Example (Groovy)</h3>
 * <pre>{@code
 * import qupath.ext.tiatoolbox.TIAToolbox
 *
 * TIAToolbox.builder()
 *     .model("resnet18-kather100k")
 *     .device("cpu")
 *     .batchSize(8)
 *     .build()
 *     .run()                          // active image in the current viewer
 * }</pre>
 */
public final class TIAToolbox {

    private static final Logger logger = LoggerFactory.getLogger(TIAToolbox.class);

    // -- Bridge singleton -----------------------------------------------------

    private static final Object BRIDGE_LOCK = new Object();
    private static BridgeManager BRIDGE;
    private static volatile boolean shutdownHookRegistered;

    /**
     * Close the Python sidecar if one is running. Called from a JVM shutdown
     * hook; users normally don't need to invoke this directly.
     */
    public static void closeBridge() {
        synchronized (BRIDGE_LOCK) {
            if (BRIDGE != null) {
                try {
                    BRIDGE.close();
                } catch (Exception e) {
                    logger.debug("BridgeManager.close failed (ignored)", e);
                }
                BRIDGE = null;
            }
        }
    }

    private static BridgeManager acquireBridge(Path pythonExe) {
        synchronized (BRIDGE_LOCK) {
            if (BRIDGE != null && !pythonExe.equals(BRIDGE.pythonExe())) {
                logger.info("Python executable changed ({} → {}); restarting sidecar",
                        BRIDGE.pythonExe(), pythonExe);
                try {
                    BRIDGE.close();
                } catch (Exception ignored) { }
                BRIDGE = null;
            }
            if (BRIDGE == null) {
                BRIDGE = new BridgeManager(pythonExe);
                registerShutdownHookOnce();
            }
            return BRIDGE;
        }
    }

    private static void registerShutdownHookOnce() {
        if (shutdownHookRegistered) return;
        Runtime.getRuntime().addShutdownHook(
                new Thread(TIAToolbox::closeBridge, "tiatoolbox-bridge-shutdown"));
        shutdownHookRegistered = true;
    }

    // -- Public factories -----------------------------------------------------

    /** Builder for a configured {@link TIAToolbox} runner. */
    public static Builder builder() {
        return new Builder();
    }

    /** The curated model list bundled with the extension. */
    public static List<ModelInfo> getModels() {
        return ModelInfo.loadBundled();
    }

    // -- Builder --------------------------------------------------------------

    public static final class Builder {
        private String model;
        private String engine;
        private String device = TIAPrefs.device.get();
        private int batchSize = TIAPrefs.batchSize.get();
        private int numWorkers = 0;
        private List<String> classes;
        private String pythonExecutable;

        private Builder() {}

        /**
         * Pretrained tiatoolbox model name (e.g. {@code resnet18-kather100k}).
         * If the name appears in the extension's bundled {@code models.json},
         * the engine and class labels are auto-filled.
         */
        public Builder model(String name) {
            this.model = name;
            for (var info : getModels()) {
                if (info.name().equals(name)) {
                    this.engine = info.engine();
                    this.classes = info.classes();
                    break;
                }
            }
            return this;
        }

        /**
         * Tiatoolbox engine key — one of {@code patch_predictor},
         * {@code semantic_segmentor}, {@code multi_task_segmentor}. Only
         * required for models that aren't in {@code models.json}.
         */
        public Builder engine(String engine) { this.engine = engine; return this; }

        /** Inference device: {@code "cpu"}, {@code "cuda"}, {@code "mps"}. */
        public Builder device(String device) { this.device = device; return this; }

        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
        public Builder numWorkers(int numWorkers) { this.numWorkers = numWorkers; return this; }

        /** Override the human-readable label list for the model's classes. */
        public Builder classes(List<String> classes) { this.classes = classes; return this; }

        /**
         * Override the Python executable. Defaults to
         * {@link TIAPrefs#pythonExecutable} (set via the GUI dialog).
         */
        public Builder pythonExecutable(String pythonExecutable) {
            this.pythonExecutable = pythonExecutable;
            return this;
        }

        public TIAToolbox build() {
            if (model == null || model.isBlank())
                throw new IllegalStateException("model is required");
            if (engine == null || engine.isBlank())
                throw new IllegalStateException(
                        "engine is required (use .model(<known>) or .engine(<name>))");
            return new TIAToolbox(this);
        }
    }

    // -- Instance state -------------------------------------------------------

    private final String model;
    private final String engine;
    private final String device;
    private final int batchSize;
    private final int numWorkers;
    private final List<String> classes;
    private final String pythonExecutableOverride;

    private TIAToolbox(Builder b) {
        this.model = b.model;
        this.engine = b.engine;
        this.device = b.device;
        this.batchSize = b.batchSize;
        this.numWorkers = b.numWorkers;
        this.classes = b.classes;
        this.pythonExecutableOverride = b.pythonExecutable;
    }

    // -- Run entry points -----------------------------------------------------

    /** Run on the active viewer's image. Returns the number of objects added. */
    public int run() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null)
            throw new IllegalStateException("No image is open in the active viewer.");
        return run(imageData);
    }

    /** Run on the given image. Returns the number of objects added. */
    public int run(ImageData<BufferedImage> imageData) {
        return run(imageData, NoOpListener.INSTANCE);
    }

    /**
     * Run on the given image, reporting progress through {@code listener}.
     * Used by the GUI to bridge progress events to the JavaFX status label.
     */
    public int run(ImageData<BufferedImage> imageData, ProgressListener listener) {
        if (imageData == null)
            throw new IllegalArgumentException("imageData must not be null");

        var wsiPath = filePathOf(imageData);
        if (wsiPath == null)
            throw new IllegalStateException(
                    "Image has no file path on disk; cannot run inference.");

        var pythonPath = resolvePythonExe();

        Path saveDir;
        try {
            saveDir = Files.createTempDirectory("tiatoolbox-" + UUID.randomUUID());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp save directory", e);
        }

        var request = new InferenceRequest(
                engine, model,
                wsiPath.toAbsolutePath().toString(),
                saveDir.toAbsolutePath().toString(),
                device, batchSize, numWorkers, classes);

        var bridge = acquireBridge(pythonPath);

        // Serialise concurrent inference calls — the Python engine isn't
        // designed to handle two .run() invocations at once.
        synchronized (bridge) {
            try {
                var runner = bridge.runner();
                var responseJson = runner.runInference(request.toJson(), listener);
                var response = InferenceResponse.fromJson(responseJson);
                if (!response.ok()) {
                    var msg = response.message() == null ? "Inference failed" : response.message();
                    throw new RuntimeException(msg);
                }
                return ResultImporter.importGeoJson(imageData, response.geojson());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -- Helpers --------------------------------------------------------------

    private Path resolvePythonExe() {
        var p = pythonExecutableOverride != null
                ? pythonExecutableOverride
                : TIAPrefs.pythonExecutable.get();
        if (p == null || p.isBlank())
            throw new IllegalStateException(
                    "Python executable not configured. Set it in the TIAToolbox dialog "
                            + "or via .pythonExecutable(...) on the builder.");
        return Path.of(p);
    }

    private static Path filePathOf(ImageData<BufferedImage> imageData) {
        URI uri = imageData.getServer().getURIs().stream().findFirst().orElse(null);
        if (uri == null) return null;
        try { return Path.of(uri); } catch (Exception e) { return null; }
    }

    private static final class NoOpListener implements ProgressListener {
        static final NoOpListener INSTANCE = new NoOpListener();
        @Override public void onStatus(String message) { }
        @Override public void onHeartbeat(int elapsedSeconds) { }
    }
}
