package qupath.ext.tiatoolbox.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.core.BridgeManager;
import qupath.ext.tiatoolbox.core.InferenceRequest;
import qupath.ext.tiatoolbox.core.InferenceResponse;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.core.ResultImporter;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Backs {@code tiatoolbox_control.fxml}. Wires UI state, prefs, and the bridge. */
public class TIAController {

    private static final Logger logger = LoggerFactory.getLogger(TIAController.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    @FXML private TextField pythonField;
    @FXML private Button browseButton;
    @FXML private ChoiceBox<ModelInfo> modelChoice;
    @FXML private ChoiceBox<String> deviceChoice;
    @FXML private Spinner<Integer> batchSpinner;
    @FXML private Label modelDescription;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button runButton;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private BridgeManager bridge;
    private final AtomicReference<Task<Integer>> currentTask = new AtomicReference<>();

    public void setQuPath(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @FXML
    private void initialize() {
        modelChoice.setItems(FXCollections.observableArrayList(ModelInfo.loadBundled()));
        modelChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                modelDescription.setText(sel == null ? "" : sel.description()));
        modelChoice.getSelectionModel().selectFirst();

        deviceChoice.setItems(FXCollections.observableArrayList("cpu", "cuda", "mps"));
        deviceChoice.getSelectionModel().select(TIAPrefs.device.get());
        TIAPrefs.device.bind(deviceChoice.getSelectionModel().selectedItemProperty());

        batchSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 256, TIAPrefs.batchSize.get()));
        TIAPrefs.batchSize.bind(batchSpinner.valueProperty());

        pythonField.setText(TIAPrefs.pythonExecutable.get());
        TIAPrefs.pythonExecutable.bind(pythonField.textProperty());

        runButton.disableProperty().bind(
                Bindings.isNull(modelChoice.getSelectionModel().selectedItemProperty())
                        .or(Bindings.isEmpty(pythonField.textProperty())));
    }

    @FXML
    private void onBrowsePython() {
        var startDir = pythonField.getText().isBlank() ? null : new File(pythonField.getText()).getParentFile();
        File chosen = FileChoosers.promptForFile(window(), RES.getString("ui.python-exe"), startDir);
        if (chosen != null) {
            pythonField.setText(chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onCancel() {
        var task = currentTask.get();
        if (task != null) {
            task.cancel(true);
        }
    }

    @FXML
    private void onRun() {
        var imageData = qupath.getViewer().getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.no-image"));
            return;
        }
        var wsiPath = filePathOf(imageData.getServer().getURIs().stream().findFirst().orElse(null));
        if (wsiPath == null) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.no-image-path"));
            return;
        }
        var python = pythonField.getText().strip();
        if (python.isEmpty()) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.python-not-set"));
            return;
        }
        var model = modelChoice.getSelectionModel().getSelectedItem();
        if (model == null) {
            return;
        }

        Path saveDir;
        try {
            saveDir = Files.createTempDirectory("tiatoolbox-" + UUID.randomUUID());
        } catch (Exception e) {
            Dialogs.showErrorMessage(RES.getString("title"), e);
            return;
        }

        if (bridge == null) {
            bridge = new BridgeManager(Path.of(python));
        }

        var request = new InferenceRequest(
                model.engine(), model.name(), wsiPath.toAbsolutePath().toString(),
                saveDir.toAbsolutePath().toString(),
                deviceChoice.getValue(), batchSpinner.getValue(), 0, model.classes());

        var task = inferenceTask(imageData, model, request);
        currentTask.set(task);

        runButton.disableProperty().unbind();
        runButton.disableProperty().set(true);
        cancelButton.setDisable(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        var th = new Thread(task, "tiatoolbox-run");
        th.setDaemon(true);
        th.start();
    }

    private Task<Integer> inferenceTask(
            qupath.lib.images.ImageData<java.awt.image.BufferedImage> imageData,
            ModelInfo model,
            InferenceRequest request) {

        var listener = new FxProgressListener();
        return new Task<>() {
            @Override
            protected Integer call() throws Exception {
                updateMessage(MessageFormat.format(RES.getString("ui.status.starting"), model.name()));
                Platform.runLater(() -> statusLabel.textProperty().bind(messageProperty()));

                listener.bindStatus((s) -> updateMessage(s));
                listener.bindHeartbeat((sec) -> updateMessage(
                        String.format(RES.getString("ui.status.heartbeat"), sec)));

                updateMessage(RES.getString("ui.status.connecting"));
                var runner = bridge.runner();

                updateMessage(MessageFormat.format(RES.getString("ui.status.running"), model.name()));
                var responseJson = runner.runInference(request.toJson(), listener);

                if (isCancelled()) return 0;

                var response = InferenceResponse.fromJson(responseJson);
                if (!response.ok()) {
                    throw new RuntimeException(response.message() == null ? "Inference failed" : response.message());
                }

                updateMessage(RES.getString("ui.status.importing"));
                return ResultImporter.importGeoJson(imageData, response.geojson());
            }

            @Override
            protected void succeeded() {
                resetButtons();
                statusLabel.textProperty().unbind();
                statusLabel.setText(RES.getString("ui.status.done") + " (+" + getValue() + " objects)");
                progressBar.setProgress(1.0);
            }

            @Override
            protected void failed() {
                resetButtons();
                statusLabel.textProperty().unbind();
                var ex = getException();
                logger.error("Inference failed", ex);
                statusLabel.setText(String.format(RES.getString("ui.status.error"),
                        ex == null ? "unknown" : ex.getMessage()));
                progressBar.setProgress(0.0);
                Dialogs.showErrorMessage(RES.getString("title"), ex);
            }

            @Override
            protected void cancelled() {
                resetButtons();
                statusLabel.textProperty().unbind();
                statusLabel.setText("Cancelled.");
                progressBar.setProgress(0.0);
            }
        };
    }

    private void resetButtons() {
        currentTask.set(null);
        cancelButton.setDisable(true);
        runButton.disableProperty().bind(
                Bindings.isNull(modelChoice.getSelectionModel().selectedItemProperty())
                        .or(Bindings.isEmpty(pythonField.textProperty())));
    }

    private Window window() {
        return runButton.getScene().getWindow();
    }

    private static Path filePathOf(URI uri) {
        if (uri == null) return null;
        try {
            return Path.of(uri);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tear down the Python sidecar when the dialog is closed. */
    public void shutdown() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    /**
     * Bridges Py4J-thread callbacks into a JavaFX-safe property channel.
     * Listener methods are invoked from a Python-side thread; we forward to
     * lambdas the surrounding {@link Task} can use with {@code updateMessage}.
     */
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
