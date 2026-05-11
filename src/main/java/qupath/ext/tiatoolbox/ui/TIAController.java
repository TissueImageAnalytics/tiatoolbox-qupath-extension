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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.TIAToolbox;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.core.PythonDetector;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backs {@code tiatoolbox_control.fxml}. Wires UI state, prefs, and the
 * inference call. The heavy lifting now lives in {@link TIAToolbox}; this
 * controller only collects parameters and binds JavaFX progress updates.
 */
public class TIAController {

    private static final Logger logger = LoggerFactory.getLogger(TIAController.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    @FXML private TextField pythonField;
    @FXML private Button browseButton;
    @FXML private ChoiceBox<ModelInfo> modelChoice;
    @FXML private ChoiceBox<String> deviceChoice;
    @FXML private Spinner<Integer> batchSpinner;
    @FXML private RadioButton scopeCurrent;
    @FXML private RadioButton scopeProject;
    @FXML private Label modelDescription;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button runButton;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private final AtomicReference<Task<Integer>> currentTask = new AtomicReference<>();

    public void setQuPath(QuPathGUI qupath) {
        this.qupath = qupath;
        // Disable "All project images" when no project is open. The binding
        // tracks live changes so opening a project mid-dialog enables it.
        scopeProject.disableProperty().bind(qupath.projectProperty().isNull());
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
        if (pythonField.getText() == null || pythonField.getText().isBlank()) {
            var detected = PythonDetector.detect();
            if (detected != null) {
                pythonField.setText(detected.toString());
            }
        }
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
        if (pythonField.getText().strip().isEmpty()) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.python-not-set"));
            return;
        }
        var model = modelChoice.getSelectionModel().getSelectedItem();
        if (model == null) {
            return;
        }
        var batch = resolveScope();
        if (batch == null || batch.isEmpty()) {
            return;
        }

        var runner = TIAToolbox.builder()
                .model(model.name())
                .device(deviceChoice.getValue())
                .batchSize(batchSpinner.getValue())
                .build();

        var task = inferenceTask(batch, model, runner);
        currentTask.set(task);

        runButton.disableProperty().unbind();
        runButton.disableProperty().set(true);
        cancelButton.setDisable(false);
        progressBar.setProgress(0.0);

        var th = new Thread(task, "tiatoolbox-run");
        th.setDaemon(true);
        th.start();
    }

    /**
     * Build the work list from the selected scope radio. Shows an error dialog
     * and returns {@code null} on a misconfigured scope (no image / no project)
     * so the caller can bail without running.
     */
    private List<BatchEntry> resolveScope() {
        if (scopeCurrent.isSelected()) {
            var imageData = qupath.getViewer().getImageData();
            if (imageData == null) {
                Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.no-image"));
                return null;
            }
            return List.of(BatchEntry.forActiveViewer(imageData));
        }
        var project = qupath.getProject();
        if (project == null || project.getImageList().isEmpty()) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.no-project"));
            return null;
        }
        var entries = new ArrayList<BatchEntry>(project.getImageList().size());
        for (var entry : project.getImageList()) {
            entries.add(BatchEntry.forProject(entry));
        }
        return entries;
    }

    private Task<Integer> inferenceTask(List<BatchEntry> entries, ModelInfo model, TIAToolbox runner) {

        var listener = new FxProgressListener();
        return new Task<>() {
            @Override
            protected Integer call() {
                Platform.runLater(() -> statusLabel.textProperty().bind(messageProperty()));

                // Each image prefixes the per-Python listener messages with
                // its [N/M] label, so heartbeat updates stay readable.
                final String[] prefix = {""};
                listener.bindStatus(s -> updateMessage(prefix[0] + s));
                listener.bindHeartbeat(sec -> updateMessage(
                        prefix[0] + String.format(RES.getString("ui.status.heartbeat"), sec)));

                int totalAdded = 0;
                int failed = 0;
                updateProgress(0, entries.size());
                for (int i = 0; i < entries.size(); i++) {
                    if (isCancelled()) break;
                    var entry = entries.get(i);
                    prefix[0] = entries.size() == 1
                            ? ""
                            : String.format("[%d/%d] %s — ", i + 1, entries.size(), entry.name());
                    updateMessage(prefix[0] + MessageFormat.format(
                            RES.getString("ui.status.running"), model.name()));
                    try {
                        var imageData = entry.load();
                        totalAdded += runner.run(imageData, listener);
                        entry.save(imageData);
                    } catch (Exception e) {
                        logger.warn("Inference failed on {}", entry.name(), e);
                        failed++;
                    }
                    updateProgress(i + 1, entries.size());
                }
                if (failed > 0) {
                    updateMessage(String.format("Completed with %d failure(s).", failed));
                }
                return totalAdded;
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

    /**
     * One unit of batch work: a way to load an {@link ImageData}, run on it,
     * and (for project entries) save the modified hierarchy back. The active
     * viewer's image needs no save — its hierarchy is already live.
     */
    private interface BatchEntry {
        String name();
        ImageData<BufferedImage> load() throws Exception;
        void save(ImageData<BufferedImage> data) throws Exception;

        static BatchEntry forActiveViewer(ImageData<BufferedImage> imageData) {
            var name = imageData.getServer().getMetadata().getName();
            return new BatchEntry() {
                @Override public String name() { return name; }
                @Override public ImageData<BufferedImage> load() { return imageData; }
                @Override public void save(ImageData<BufferedImage> data) { /* live in viewer */ }
            };
        }

        static BatchEntry forProject(ProjectImageEntry<BufferedImage> entry) {
            return new BatchEntry() {
                @Override public String name() { return entry.getImageName(); }
                @Override public ImageData<BufferedImage> load() throws Exception {
                    return entry.readImageData();
                }
                @Override public void save(ImageData<BufferedImage> data) throws Exception {
                    entry.saveImageData(data);
                }
            };
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
