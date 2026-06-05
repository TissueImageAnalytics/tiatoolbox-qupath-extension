package qupath.ext.tiatoolbox.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.TIAToolbox;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.core.TrainingRequest;
import qupath.ext.tiatoolbox.core.TrainingResponse;
import qupath.ext.tiatoolbox.install.RuntimePaths;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Controller for v1 patch-classifier training. */
public class TrainingController {

    private static final Logger logger = LoggerFactory.getLogger(TrainingController.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FXML private Label projectLabel;
    @FXML private VBox classBox;
    @FXML private ChoiceBox<String> backboneChoice;
    @FXML private ChoiceBox<String> deviceChoice;
    @FXML private Spinner<Integer> epochsSpinner;
    @FXML private Spinner<Integer> batchSpinner;
    @FXML private Spinner<Integer> patchSizeSpinner;
    @FXML private Spinner<Integer> strideSpinner;
    @FXML private Spinner<Double> mppSpinner;
    @FXML private Spinner<Integer> maxPatchesSpinner;
    @FXML private Spinner<Integer> seedSpinner;
    @FXML private Spinner<Double> validationSpinner;
    @FXML private TextField runNameField;
    @FXML private TextField learningRateField;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button refreshButton;
    @FXML private Button trainButton;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private final AtomicReference<Task<TrainingResponse>> currentTask = new AtomicReference<>();

    public void setQuPath(QuPathGUI qupath) {
        this.qupath = qupath;
        refreshProjectClasses();
    }

    @FXML
    private void initialize() {
        backboneChoice.setItems(FXCollections.observableArrayList(
                "CNNModel:resnet18",
                "CNNModel:resnet34",
                "CNNModel:resnet50",
                "CNNModel:densenet121",
                "CNNModel:mobilenet_v3_small",
                "TimmModel:efficientnet_b0"
        ));
        backboneChoice.getSelectionModel().selectFirst();

        deviceChoice.setItems(FXCollections.observableArrayList("auto", "cpu", "cuda", "mps"));
        deviceChoice.getSelectionModel().select("auto");

        epochsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1000, 1));
        batchSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 256, 8));
        patchSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(32, 2048, 224, 16));
        strideSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(32, 2048, 224, 16));
        mppSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.05, 20.0, 0.5, 0.05));
        maxPatchesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100000, 250));
        seedSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, Integer.MAX_VALUE, 1));
        validationSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 0.9, 0.2, 0.05));
        learningRateField.setText("0.0001");
    }

    @FXML
    private void onRefresh() {
        refreshProjectClasses();
    }

    @FXML
    private void onCancel() {
        var task = currentTask.get();
        if (task != null) {
            TIAToolbox.cancelTraining();
            task.cancel(true);
        }
    }

    @FXML
    private void onTrain() {
        if (RuntimePaths.installedPython() == null) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.python-not-set"));
            return;
        }
        TrainingRequest request;
        try {
            request = buildTrainingRequest();
        } catch (Exception e) {
            Dialogs.showErrorMessage(RES.getString("training.title"), e);
            return;
        }

        var task = trainingTask(request);
        currentTask.set(task);
        setRunning(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        var th = new Thread(task, "tiatoolbox-training");
        th.setDaemon(true);
        th.start();
    }

    private void refreshProjectClasses() {
        classBox.getChildren().clear();
        if (qupath == null || qupath.getProject() == null) {
            projectLabel.setText(RES.getString("training.no-project"));
            trainButton.setDisable(true);
            return;
        }
        projectLabel.setText(qupath.getProject().getName());
        try {
            var classes = discoverProjectClasses();
            for (var name : classes) {
                var cb = new CheckBox(name);
                cb.setSelected(true);
                classBox.getChildren().add(cb);
            }
            trainButton.setDisable(classes.size() < 2);
            statusLabel.setText(MessageFormat.format(
                    RES.getString("training.status.classes"), classes.size()));
        } catch (Exception e) {
            logger.error("Failed to scan project classes", e);
            statusLabel.setText(String.format(RES.getString("ui.status.error"), e.getMessage()));
            trainButton.setDisable(true);
        }
    }

    private List<String> discoverProjectClasses() throws Exception {
        var project = qupath.getProject();
        var names = new java.util.TreeSet<String>();
        for (var entry : project.getImageList()) {
            var imageData = entry.readImageData();
            for (var obj : imageData.getHierarchy().getAnnotationObjects()) {
                var pc = obj.getPathClass();
                if (pc != null && pc.getName() != null && obj.hasROI()) {
                    names.add(pc.getName());
                }
            }
        }
        return new ArrayList<>(names);
    }

    private TrainingRequest buildTrainingRequest() throws Exception {
        var project = qupath.getProject();
        if (project == null || project.getImageList().isEmpty()) {
            throw new IllegalStateException(RES.getString("error.no-project"));
        }

        var selectedClasses = selectedClasses();
        if (selectedClasses.size() < 2) {
            throw new IllegalStateException(RES.getString("training.error.classes"));
        }

        var runDir = resolveRunDirectory();
        var annotationDir = runDir.resolve("annotations");
        Files.createDirectories(annotationDir);

        var candidates = new ArrayList<ExportedSlide>();
        for (var entry : project.getImageList()) {
            var imageData = entry.readImageData();
            var wsiPath = filePathOf(imageData);
            if (wsiPath == null) {
                logger.warn("Skipping {} because it has no local file path", entry.getImageName());
                continue;
            }
            var objects = filteredAnnotations(imageData, selectedClasses);
            if (objects.isEmpty()) {
                continue;
            }
            var geojson = annotationDir.resolve(safeName(entry.getImageName()) + ".geojson");
            PathIO.exportObjectsAsGeoJSON(
                    geojson,
                    objects,
                    PathIO.GeoJsonExportOptions.FEATURE_COLLECTION,
                    PathIO.GeoJsonExportOptions.EXCLUDE_MEASUREMENTS);
            candidates.add(new ExportedSlide(entry, wsiPath, geojson));
        }

        if (candidates.size() < 2) {
            throw new IllegalStateException(RES.getString("training.error.slides"));
        }

        var slides = splitSlides(candidates, selectedClasses);
        var mapping = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < selectedClasses.size(); i++) {
            mapping.put(selectedClasses.get(i), i);
        }

        var spec = parseBackbone(backboneChoice.getValue());
        var mpp = mppSpinner.getValue();
        if (mpp == null || mpp <= 0) {
            throw new IllegalStateException(RES.getString("training.error.mpp"));
        }
        var request = new TrainingRequest(
                slides,
                selectedClasses,
                mapping,
                spec,
                new TrainingRequest.Options(
                        deviceChoice.getValue(),
                        epochsSpinner.getValue(),
                        batchSpinner.getValue(),
                        Double.parseDouble(learningRateField.getText().trim()),
                        patchSizeSpinner.getValue(),
                        strideSpinner.getValue(),
                        mpp,
                        validationSpinner.getValue(),
                        maxPatchesSpinner.getValue(),
                        seedSpinner.getValue(),
                        0.01,
                        10),
                runDir.toAbsolutePath().toString());
        Files.writeString(runDir.resolve("training_request.json"),
                request.toJson(), StandardCharsets.UTF_8);
        return request;
    }

    private List<String> selectedClasses() {
        return classBox.getChildren().stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(CheckBox::isSelected)
                .map(CheckBox::getText)
                .collect(Collectors.toList());
    }

    private List<PathObject> filteredAnnotations(
            ImageData<BufferedImage> imageData,
            List<String> selectedClasses) {
        var selected = new java.util.HashSet<>(selectedClasses);
        return imageData.getHierarchy().getAnnotationObjects().stream()
                .filter(PathObject::hasROI)
                .filter(obj -> obj.getPathClass() != null)
                .filter(obj -> selected.contains(obj.getPathClass().getName()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<TrainingRequest.Slide> splitSlides(
            List<ExportedSlide> candidates,
            List<String> selectedClasses) {
        var shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, new java.util.Random(seedSpinner.getValue()));
        int valCount = (int)Math.round(shuffled.size() * validationSpinner.getValue());
        if (validationSpinner.getValue() > 0 && valCount == 0 && shuffled.size() > 1) {
            valCount = 1;
        }
        if (valCount >= shuffled.size()) {
            valCount = shuffled.size() - 1;
        }
        var valSet = new java.util.HashSet<ExportedSlide>(shuffled.subList(0, valCount));
        return candidates.stream()
                .map(slide -> new TrainingRequest.Slide(
                        slide.entry.getImageName(),
                        slide.wsiPath.toAbsolutePath().toString(),
                        slide.geojson.toAbsolutePath().toString(),
                        valSet.contains(slide) ? "val" : "train"))
                .collect(Collectors.toList());
    }

    private TrainingRequest.ModelSpec parseBackbone(String value) {
        var parts = value.split(":", 2);
        var type = parts.length > 0 ? parts[0] : "CNNModel";
        var backbone = parts.length > 1 ? parts[1] : "resnet18";
        return new TrainingRequest.ModelSpec(type, backbone, false);
    }

    private Path trainingRoot() {
        var projectPath = qupath.getProject().getPath();
        var base = Files.isDirectory(projectPath) ? projectPath : projectPath.getParent();
        if (base == null) {
            base = Path.of(System.getProperty("user.home"));
        }
        return base.resolve("tiatoolbox-training");
    }

    private Path resolveRunDirectory() {
        var trainingRoot = trainingRoot();
        var name = runNameField.getText() == null ? "" : runNameField.getText().trim();
        var runName = safeRunName(name);
        if (runName.isBlank()) {
            runName = "run-" + RUN_ID_FORMAT.format(LocalDateTime.now());
        }
        var runDir = trainingRoot.resolve(runName);
        if (!Files.exists(runDir)) {
            return runDir;
        }

        var timestamp = RUN_ID_FORMAT.format(LocalDateTime.now());
        runDir = trainingRoot.resolve(runName + "-" + timestamp);
        for (int i = 2; Files.exists(runDir); i++) {
            runDir = trainingRoot.resolve(runName + "-" + timestamp + "-" + i);
        }
        return runDir;
    }

    private Task<TrainingResponse> trainingTask(TrainingRequest request) {
        var listener = new FxProgressListener();
        return new Task<>() {
            @Override
            protected TrainingResponse call() {
                Platform.runLater(() -> statusLabel.textProperty().bind(messageProperty()));
                listener.bindStatus(this::updateMessage);
                listener.bindHeartbeat(sec -> updateMessage(
                        String.format(RES.getString("ui.status.heartbeat"), sec)));
                return TIAToolbox.train(request, listener);
            }

            @Override
            protected void succeeded() {
                setRunning(false);
                statusLabel.textProperty().unbind();
                var response = getValue();
                statusLabel.setText(MessageFormat.format(
                        RES.getString("training.status.done"),
                        response.artifact(),
                        response.train_samples(),
                        response.val_samples()));
                progressBar.setProgress(1.0);
            }

            @Override
            protected void failed() {
                setRunning(false);
                statusLabel.textProperty().unbind();
                var ex = getException();
                logger.error("Training failed", ex);
                statusLabel.setText(String.format(RES.getString("ui.status.error"),
                        ex == null ? "unknown" : ex.getMessage()));
                progressBar.setProgress(0.0);
                Dialogs.showErrorMessage(RES.getString("training.title"), ex);
            }

            @Override
            protected void cancelled() {
                setRunning(false);
                statusLabel.textProperty().unbind();
                statusLabel.setText(RES.getString("training.status.cancelled"));
                progressBar.setProgress(0.0);
            }
        };
    }

    private void setRunning(boolean running) {
        refreshButton.setDisable(running);
        trainButton.setDisable(running);
        cancelButton.setDisable(!running);
        currentTask.set(running ? currentTask.get() : null);
    }

    private static Path filePathOf(ImageData<BufferedImage> imageData) {
        URI uri = imageData.getServer().getURIs().stream().findFirst().orElse(null);
        if (uri == null) return null;
        try { return Path.of(uri); } catch (Exception e) { return null; }
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String safeRunName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("^[. ]+|[. ]+$", "");
    }

    private record ExportedSlide(
            ProjectImageEntry<BufferedImage> entry,
            Path wsiPath,
            Path geojson
    ) {}

    private static final class FxProgressListener implements ProgressListener {
        private final SimpleObjectProperty<java.util.function.Consumer<String>> statusSink =
                new SimpleObjectProperty<>(s -> {});
        private final SimpleObjectProperty<java.util.function.Consumer<Integer>> heartbeatSink =
                new SimpleObjectProperty<>(s -> {});

        void bindStatus(java.util.function.Consumer<String> sink) { statusSink.set(sink); }
        void bindHeartbeat(java.util.function.Consumer<Integer> sink) { heartbeatSink.set(sink); }

        @Override public void onStatus(String message) {
            try { statusSink.get().accept(message); } catch (Exception ignored) {}
        }
        @Override public void onHeartbeat(int elapsedSeconds) {
            try { heartbeatSink.get().accept(elapsedSeconds); } catch (Exception ignored) {}
        }
    }
}
