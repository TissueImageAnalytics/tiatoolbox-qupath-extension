package qupath.ext.tiatoolbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.core.BridgeManager;
import qupath.ext.tiatoolbox.core.ImageServerCoordinates;
import qupath.ext.tiatoolbox.core.InferenceRequest;
import qupath.ext.tiatoolbox.core.InferenceResponse;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.core.ResultImporter;
import qupath.ext.tiatoolbox.core.TrainingRequest;
import qupath.ext.tiatoolbox.core.TrainingResponse;
import qupath.ext.tiatoolbox.install.RuntimePaths;
import qupath.ext.tiatoolbox.ui.ModelInfo;
import qupath.ext.tiatoolbox.ui.TIAPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        private String artifactPath;
        private boolean autoGetMask = true;

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
         * {@code semantic_segmentor}, {@code multi_task_segmentor},
         * {@code nucleus_detector}. Only required for models that aren't in
         * {@code models.json}.
         */
        public Builder engine(String engine) { this.engine = engine; return this; }

        /** Inference device: {@code "cpu"}, {@code "cuda"}, {@code "mps"}. */
        public Builder device(String device) { this.device = device; return this; }

        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
        public Builder numWorkers(int numWorkers) { this.numWorkers = numWorkers; return this; }

        /** Whether WSI-mode inference should auto-generate a tissue mask. */
        public Builder autoGetMask(boolean autoGetMask) {
            this.autoGetMask = autoGetMask;
            return this;
        }

        /** Override the human-readable label list for the model's classes. */
        public Builder classes(List<String> classes) { this.classes = classes; return this; }

        /** Use a training artifact manifest instead of a bundled model name. */
        public Builder artifactPath(String artifactPath) {
            this.artifactPath = artifactPath;
            if (artifactPath != null && !artifactPath.isBlank()) {
                this.engine = "patch_predictor";
                this.model = artifactRunName(artifactPath);
            }
            return this;
        }

        private static String artifactRunName(String artifactPath) {
            try {
                var path = Path.of(artifactPath.trim());
                var fileName = path.getFileName();
                if (fileName != null
                        && "training_artifact.json".equals(fileName.toString())
                        && path.getParent() != null
                        && path.getParent().getFileName() != null) {
                    return path.getParent().getFileName().toString();
                }
                if (fileName != null && !fileName.toString().isBlank()) {
                    return fileName.toString();
                }
            } catch (RuntimeException ignored) {
                // Fall back to a generic name; validation happens before inference.
            }
            return "training-artifact";
        }

        /**
         * Override the bundled runtime Python. Most users should install the
         * runtime from the GUI and leave this unset.
         */
        public Builder pythonExecutable(String pythonExecutable) {
            this.pythonExecutable = pythonExecutable;
            return this;
        }

        public TIAToolbox build() {
            if ((model == null || model.isBlank()) && (artifactPath == null || artifactPath.isBlank()))
                throw new IllegalStateException("model is required");
            if (engine == null || engine.isBlank())
                throw new IllegalStateException(
                        "engine is required (use .model(<known>) or .engine(<name>))");
            return new TIAToolbox(this);
        }
    }

    // -- Instance state -------------------------------------------------------

    private static final DateTimeFormatter RUN_DIR_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String model;
    private final String engine;
    private final String device;
    private final int batchSize;
    private final int numWorkers;
    private final List<String> classes;
    private final String pythonExecutableOverride;
    private final String artifactPath;
    private final boolean autoGetMask;

    /** This run's output folder, created lazily on the first {@code run(...)}. */
    private Path runResultsDir;
    /** Per-image subfolder names already used this run (avoids clobbering). */
    private final Set<String> usedSubdirNames = new HashSet<>();

    private TIAToolbox(Builder b) {
        this.model = b.model;
        this.engine = b.engine;
        this.device = b.device;
        this.batchSize = b.batchSize;
        this.numWorkers = b.numWorkers;
        this.classes = b.classes;
        this.pythonExecutableOverride = b.pythonExecutable;
        this.artifactPath = b.artifactPath;
        this.autoGetMask = b.autoGetMask;
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

        var saveDir = imageSaveDir(wsiPath);

        var request = new InferenceRequest(
                engine, model,
                wsiPath.toAbsolutePath().toString(),
                saveDir.toAbsolutePath().toString(),
                device, batchSize, numWorkers, classes, artifactPath, autoGetMask,
                visibleBoundsFor(imageData));

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

    // -- Results location -----------------------------------------------------

    /**
     * The folder this runner writes its outputs into, or {@code null} if it has
     * not run yet. One folder per runner (i.e. per run); each image lands in a
     * subfolder named after the slide.
     */
    public Path resultsDir() {
        return runResultsDir;
    }

    /**
     * Resolve a per-image output subfolder under this run's results folder,
     * creating the parent run folder on first use. Each image gets its own
     * subfolder so a batch never clobbers earlier results (the engine wipes its
     * {@code save_dir} when {@code overwrite=true}).
     */
    private synchronized Path imageSaveDir(Path wsiPath) {
        if (runResultsDir == null) {
            var stamp = LocalDateTime.now().format(RUN_DIR_TIMESTAMP);
            var dir = resultsRoot().resolve("tiatoolbox_" + sanitize(model) + "_" + stamp);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create results directory: " + dir, e);
            }
            runResultsDir = dir;
        }

        // Name the subfolder after the slide (matching the .json written inside),
        // disambiguating with a counter if two slides share a name this run.
        var base = sanitize(stemOf(wsiPath));
        var name = base;
        for (int n = 2; !usedSubdirNames.add(name); n++) {
            name = base + "_" + n;
        }
        return runResultsDir.resolve(name);
    }

    /**
     * Store results with the active project when one is open; otherwise fall
     * back to the QuPath user directory for isolated slides and scripts.
     */
    private static Path resultsRoot() {
        try {
            var project = QP.getProject();
            if (project != null && project.getPath() != null) {
                var projectPath = project.getPath();
                var base = Files.isDirectory(projectPath) ? projectPath : projectPath.getParent();
                if (base != null) {
                    return base.resolve(RuntimePaths.RESULTS_DIR_NAME);
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Could not resolve project results directory; using user results directory", e);
        }
        return RuntimePaths.resultsRoot();
    }

    /** Filename stem (drops the final extension), matching tiatoolbox's naming. */
    private static String stemOf(Path wsiPath) {
        var name = wsiPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Make a string safe to use as a folder name across platforms. */
    private static String sanitize(String s) {
        var cleaned = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "output" : cleaned;
    }

    // -- Helpers --------------------------------------------------------------

    private static InferenceRequest.VisibleBounds visibleBoundsFor(ImageData<BufferedImage> imageData) {
        var region = ImageServerCoordinates.displayRegionInFullSlideCoordinates(imageData.getServer());
        if (!region.bounded()) {
            return null;
        }
        return new InferenceRequest.VisibleBounds(
                region.x(),
                region.y(),
                region.width(),
                region.height());
    }

    private Path resolvePythonExe() {
        if (pythonExecutableOverride != null && !pythonExecutableOverride.isBlank()) {
            return Path.of(pythonExecutableOverride);
        }
        var p = RuntimePaths.installedPython();
        if (p == null)
            throw new IllegalStateException(
                    "Python runtime not installed. Use Extensions → TIAToolbox → "
                            + "Install Python runtime…");
        return p;
    }

    /** Run a project training request through the shared Python sidecar. */
    public static TrainingResponse train(TrainingRequest request, ProgressListener listener) {
        var python = RuntimePaths.installedPython();
        if (python == null) {
            throw new IllegalStateException(
                    "Python runtime not installed. Use Extensions → TIAToolbox → "
                            + "Install Python runtime…");
        }
        var bridge = acquireBridge(python);
        synchronized (bridge) {
            try {
                var responseJson = bridge.runner().runTraining(request.toJson(), listener);
                var response = TrainingResponse.fromJson(responseJson);
                if (!response.ok()) {
                    var msg = response.message() == null ? "Training failed" : response.message();
                    throw new RuntimeException(msg);
                }
                return response;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Request best-effort cancellation of active training. */
    public static void cancelTraining() {
        synchronized (BRIDGE_LOCK) {
            if (BRIDGE == null) return;
            try {
                BRIDGE.runner().cancelTraining();
            } catch (Exception e) {
                logger.debug("cancelTraining failed", e);
            }
        }
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
