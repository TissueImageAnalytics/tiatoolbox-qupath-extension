package qupath.ext.tiatoolbox.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.TIAToolbox;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.install.RuntimePaths;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @FXML private HBox runtimeMissingBox;
    @FXML private ComboBox<ModelInfo> modelChoice;
    @FXML private Label modelFilterIcon;
    @FXML private Button modelInfoButton;
    @FXML private Button modelClearButton;
    @FXML private ChoiceBox<String> deviceChoice;
    @FXML private Spinner<Integer> batchSpinner;
    @FXML private CheckBox artifactCheckBox;
    @FXML private TextField artifactField;
    @FXML private Button artifactBrowseButton;
    @FXML private Button trainModelButton;
    @FXML private CheckBox autoMaskCheckBox;
    @FXML private RadioButton scopeCurrent;
    @FXML private RadioButton scopeProject;
    @FXML private Label modelDescription;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button runButton;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private RuntimeInstallCommand runtimeInstallCommand;
    private TrainingCommand trainingCommand;
    private final AtomicReference<Task<Integer>> currentTask = new AtomicReference<>();

    /** Full, unfiltered model list; the ComboBox shows a FilteredList view of it. */
    private final ObservableList<ModelInfo> allModels =
            FXCollections.observableArrayList(ModelInfo.loadBundled());

    public void setQuPath(QuPathGUI qupath) {
        this.qupath = qupath;
        this.runtimeInstallCommand = new RuntimeInstallCommand(qupath, this::refreshRuntimeBanner);
        this.trainingCommand = new TrainingCommand(qupath);
        // Disable "All project images" when no project is open. The binding
        // tracks live changes so opening a project mid-dialog enables it.
        scopeProject.disableProperty().bind(qupath.projectProperty().isNull());
    }

    @FXML
    private void initialize() {
        installModelFilter();
        modelChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                updateModelDescription());
        modelChoice.getSelectionModel().selectFirst();

        deviceChoice.setItems(FXCollections.observableArrayList("cpu", "cuda", "mps"));
        deviceChoice.getSelectionModel().select(TIAPrefs.device.get());
        TIAPrefs.device.bind(deviceChoice.getSelectionModel().selectedItemProperty());

        batchSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 256, TIAPrefs.batchSize.get()));
        TIAPrefs.batchSize.bind(batchSpinner.valueProperty());

        artifactField.disableProperty().bind(artifactCheckBox.selectedProperty().not());
        artifactBrowseButton.disableProperty().bind(artifactCheckBox.selectedProperty().not());
        modelChoice.disableProperty().bind(artifactCheckBox.selectedProperty());
        artifactCheckBox.selectedProperty().addListener((obs, old, selected) -> updateModelDescription());
        artifactField.textProperty().addListener((obs, old, text) -> updateModelDescription());

        refreshRuntimeBanner();
        updateModelDescription();
    }

    /**
     * Wire the editable ComboBox so typing in its editor filters the dropdown
     * by Google-style multi-token substring match across each model's
     * <em>name</em>, <em>description</em>, and <em>task</em> (case-insensitive).
     * Typing {@code "nuclei classification"} keeps a model only if both
     * tokens appear somewhere across those three fields.
     *
     * <p>JavaFX has no native filtering ComboBox, so we back it with a
     * {@link FilteredList} and re-evaluate the predicate from the editor's
     * text property. We detect "selection echoes" (editor text being set to
     * a model's display name as a side effect of selection) and skip
     * filtering in that case, so picking an item doesn't reopen or narrow
     * the popup.</p>
     */
    private void installModelFilter() {
        var filtered = new FilteredList<>(allModels, m -> true);
        modelChoice.setItems(filtered);

        modelChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(ModelInfo model) {
                return model == null ? "" : model.toString();
            }

            @Override
            public ModelInfo fromString(String text) {
                if (text == null || text.isBlank()) {
                    return modelChoice.getSelectionModel().getSelectedItem();
                }
                for (var m : allModels) {
                    if (m.toString().equalsIgnoreCase(text.trim())) {
                        return m;
                    }
                }
                return modelChoice.getSelectionModel().getSelectedItem();
            }
        });

        TextField editor = modelChoice.getEditor();
        // Make space on the left so typed text and the prompt don't sit
        // under the overlaid funnel icon (see FXML modelFilterIcon).
        editor.setStyle("-fx-padding: 4 6 4 22;");
        // Show the clear-button only when there's something in the editor.
        // managed mirrors visible so the layout doesn't reserve space when
        // the button is hidden.
        modelClearButton.visibleProperty().bind(
                editor.textProperty().isNotEmpty());
        modelClearButton.managedProperty().bind(
                modelClearButton.visibleProperty());
        // Filter as the user types. Selection-driven "echo" updates (where
        // the editor text is set to a model's display name) are detected below
        // and ignored so choosing an item doesn't re-filter or reopen the popup.
        editor.textProperty().addListener((obs, old, text) -> {
            // Selection echo guard: if the editor text exactly equals any
            // model's display name, treat this as a selection round-trip
            // (not a filter intent) regardless of which order selection
            // model and editor text were updated in.
            final String value = text == null ? "" : text;
            for (var m : allModels) {
                if (m.toString().equals(value)) {
                    return;
                }
            }
            // Tokenise the query on whitespace; every token must match
            // somewhere across name + description + task. This is the
            // "google-style" behaviour: "nuclei classification" finds
            // models whose haystack contains both words, in any order.
            final String[] tokens = value.trim().toLowerCase().split("\\s+");
            filtered.setPredicate(m -> {
                if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
                    return true;
                }
                String haystack = (nullToEmpty(m.name()) + " "
                                 + nullToEmpty(m.description()) + " "
                                 + nullToEmpty(m.task())).toLowerCase();
                for (String tok : tokens) {
                    if (!tok.isEmpty() && !haystack.contains(tok)) {
                        return false;
                    }
                }
                return true;
            });
            // Showing the popup from inside the editor's text listener while
            // a key is still being processed can leave the editor's caret /
            // selection in a transient state ("start must be <= end" on
            // Backspace, JDK-8228055 family). Defer the show() to the next
            // pulse so the keystroke fully resolves first.
            if (!value.isBlank()) {
                Platform.runLater(() -> {
                    if (!modelChoice.isShowing()) {
                        modelChoice.show();
                    }
                });
            }
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Wired to the in-field clear button. Empties the editor (which kicks
     * the filter listener to reset the predicate to "show all"), and
     * deselects any currently-selected model so the user's next click on a
     * dropdown item registers as a fresh selection.
     */
    @FXML
    private void onClearModelFilter() {
        modelChoice.getSelectionModel().clearSelection();
        modelChoice.getEditor().clear();
        modelChoice.getEditor().requestFocus();
    }

    /**
     * Show the selected model's full summary text in an information dialog.
     * Backed by QuPath's {@link Dialogs#showMessageDialog(String, String)} so
     * it matches the rest of the UI and avoids a ControlsFX dependency.
     */
    @FXML
    private void onShowModelInfo() {
        if (artifactCheckBox != null && artifactCheckBox.isSelected()) {
            Dialogs.showMessageDialog(RES.getString("title"), artifactDescription());
            return;
        }
        var model = modelChoice.getSelectionModel().getSelectedItem();
        if (model == null) {
            Dialogs.showMessageDialog(RES.getString("title"),
                    RES.getString("ui.model.info-none"));
            return;
        }
        var sb = new StringBuilder();
        sb.append(model.name()).append("\n")
          .append(RES.getString("ui.engine")).append(": ").append(model.task()).append("\n\n")
          .append(model.description());
        if (model.classes() != null && !model.classes().isEmpty()) {
            sb.append("\n\n").append(RES.getString("ui.model.info-classes")).append(": ")
              .append(String.join(", ", model.classes()));
        }
        Dialogs.showMessageDialog(RES.getString("title"), sb.toString());
    }

    /** Show or hide the "runtime not installed" banner and wire Run availability. */
    private void refreshRuntimeBanner() {
        var runtimeReady = RuntimePaths.installedPython() != null;
        runtimeMissingBox.setVisible(!runtimeReady);
        runtimeMissingBox.setManaged(!runtimeReady);

        runButton.disableProperty().unbind();
        if (!runtimeReady) {
            runButton.setDisable(true);
        } else {
            runButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
                if (artifactCheckBox != null && artifactCheckBox.isSelected()) {
                    var path = artifactField == null || artifactField.getText() == null
                            ? ""
                            : artifactField.getText().trim();
                    return path.isBlank();
                }
                return modelChoice.getSelectionModel().getSelectedItem() == null;
            }, artifactCheckBox.selectedProperty(),
                    artifactField.textProperty(),
                    modelChoice.getSelectionModel().selectedItemProperty()));
        }
    }

    @FXML
    private void onOpenInstaller() {
        if (runtimeInstallCommand == null) return;
        runtimeInstallCommand.run();
        // The installer runs asynchronously; re-check banner state next time
        // the user reopens the dialog. As a courtesy, refresh now too — the
        // banner will continue to show until the install completes.
        refreshRuntimeBanner();
    }

    @FXML
    private void onTrainModel() {
        if (trainingCommand == null) return;
        trainingCommand.run();
    }

    @FXML
    private void onBrowseArtifact() {
        var chooser = new DirectoryChooser();
        chooser.setTitle(RES.getString("ui.artifact.browse-title"));
        var initialDirectory = artifactInitialDirectory();
        if (initialDirectory != null && Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }
        var directory = chooser.showDialog(runButton.getScene().getWindow());
        if (directory != null) {
            artifactField.setText(directory.toPath().toString());
            artifactCheckBox.setSelected(true);
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
        if (RuntimePaths.installedPython() == null) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.python-not-set"));
            refreshRuntimeBanner();
            return;
        }
        var useArtifact = artifactCheckBox.isSelected();
        var model = modelChoice.getSelectionModel().getSelectedItem();
        if (!useArtifact && model == null) {
            return;
        }
        var artifactPath = selectedArtifactPath();
        if (useArtifact && artifactPath == null) {
            Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.artifact-not-set"));
            return;
        }
        if (useArtifact && !Files.isRegularFile(artifactPath)) {
            Dialogs.showErrorMessage(
                    RES.getString("title"),
                    MessageFormat.format(
                            RES.getString("ui.artifact.description.missing"),
                            artifactPath));
            return;
        }
        var batch = resolveScope();
        if (batch == null || batch.isEmpty()) {
            return;
        }

        var builder = TIAToolbox.builder()
                .device(deviceChoice.getValue())
                .batchSize(batchSpinner.getValue())
                .autoGetMask(autoMaskCheckBox.isSelected());
        if (useArtifact) {
            builder.artifactPath(artifactPath.toString());
        } else {
            builder.model(model.name());
        }
        var runner = builder.build();

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
                String lastFailure = null;
                updateProgress(0, entries.size());
                for (int i = 0; i < entries.size(); i++) {
                    if (isCancelled()) break;
                    var entry = entries.get(i);
                    prefix[0] = entries.size() == 1
                            ? ""
                            : String.format("[%d/%d] %s — ", i + 1, entries.size(), entry.name());
                    var label = artifactCheckBox.isSelected()
                            ? selectedArtifactLabel()
                            : model.name();
                    updateMessage(prefix[0] + MessageFormat.format(
                            RES.getString("ui.status.running"), label));
                    try {
                        var imageData = entry.load();
                        totalAdded += runner.run(imageData, listener);
                        entry.save(imageData);
                    } catch (Exception e) {
                        logger.warn("Inference failed on {}", entry.name(), e);
                        failed++;
                        lastFailure = e.getMessage();
                    }
                    updateProgress(i + 1, entries.size());
                }
                if (failed > 0) {
                    updateMessage(String.format(
                            "Completed with %d failure(s). Last error: %s",
                            failed,
                            lastFailure == null || lastFailure.isBlank() ? "unknown" : lastFailure));
                }
                return totalAdded;
            }

            @Override
            protected void succeeded() {
                resetButtons();
                statusLabel.textProperty().unbind();
                var finalMessage = getMessage();
                if (finalMessage != null && finalMessage.startsWith("Completed with ")) {
                    statusLabel.setText(finalMessage + " (+" + getValue() + " objects)");
                } else {
                    statusLabel.setText(RES.getString("ui.status.done") + " (+" + getValue() + " objects)");
                }
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
        refreshRuntimeBanner();
    }

    private Path artifactInitialDirectory() {
        var existingText = artifactField.getText() == null ? "" : artifactField.getText().trim();
        if (!existingText.isBlank()) {
            var existingPath = Path.of(existingText);
            var parent = Files.isDirectory(existingPath) ? existingPath : existingPath.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }

        var projectBase = projectBaseDirectory();
        if (projectBase == null) {
            return null;
        }

        var trainingRoot = projectBase.resolve("tiatoolbox-training");
        if (Files.isDirectory(trainingRoot)) {
            return trainingRoot;
        }
        return Files.isDirectory(projectBase) ? projectBase : null;
    }

    private Path projectBaseDirectory() {
        if (qupath == null || qupath.getProject() == null || qupath.getProject().getPath() == null) {
            return null;
        }
        var projectPath = qupath.getProject().getPath();
        var base = Files.isDirectory(projectPath) ? projectPath : projectPath.getParent();
        return base == null ? null : base;
    }

    private Path selectedArtifactPath() {
        var text = artifactField.getText() == null ? "" : artifactField.getText().trim();
        if (text.isBlank()) {
            return null;
        }
        var path = Path.of(text);
        return Files.isDirectory(path) ? path.resolve("training_artifact.json") : path;
    }

    private String selectedArtifactLabel() {
        var text = artifactField.getText() == null ? "" : artifactField.getText().trim();
        if (text.isBlank()) {
            return "training artifact";
        }
        var path = Path.of(text);
        if (Files.isDirectory(path)) {
            var name = path.getFileName();
            return name == null ? text : name.toString();
        }
        var parent = path.getParent();
        var name = parent == null ? path.getFileName() : parent.getFileName();
        return name == null ? text : name.toString();
    }

    private void updateModelDescription() {
        if (artifactCheckBox != null && artifactCheckBox.isSelected()) {
            modelDescription.setText(artifactDescription());
            return;
        }
        var model = modelChoice == null ? null : modelChoice.getSelectionModel().getSelectedItem();
        modelDescription.setText(model == null ? "" : model.description());
    }

    private String artifactDescription() {
        var text = artifactField.getText() == null ? "" : artifactField.getText().trim();
        if (text.isBlank()) {
            return RES.getString("ui.artifact.description.empty");
        }

        var path = selectedArtifactPath();
        if (!Files.isRegularFile(path)) {
            return MessageFormat.format(RES.getString("ui.artifact.description.missing"), path);
        }

        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var model = object(root, "model");
            var metadata = object(root, "metadata");
            var training = object(root, "training");
            var classDict = object(root, "class_dict");

            var description = string(model, "description", "Training artifact");
            var task = string(root, "task_type", "unknown");
            var classes = classDict == null ? "" : sortedClassLabels(classDict);
            var patchSize = string(metadata, "patch_size", "?");
            var stride = string(metadata, "stride", "?");
            var mpp = string(metadata, "mpp", "?");
            var bestEpoch = string(training, "best_epoch", "?");
            var bestValue = string(training, "best_monitor_value", "?");

            return MessageFormat.format(
                    RES.getString("ui.artifact.description"),
                    description,
                    task,
                    classes,
                    patchSize,
                    stride,
                    mpp,
                    bestEpoch,
                    bestValue);
        } catch (Exception e) {
            logger.debug("Could not read training artifact {}", text, e);
            return MessageFormat.format(RES.getString("ui.artifact.description.invalid"), text);
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        if (root == null) return null;
        var value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject root, String key, String fallback) {
        if (root == null) return fallback;
        var value = root.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (value.isJsonPrimitive()) return value.getAsJsonPrimitive().getAsString();
        return fallback;
    }

    private static String sortedClassLabels(JsonObject classDict) {
        return classDict.entrySet().stream()
                .sorted((a, b) -> Integer.compare(parseInt(a.getKey()), parseInt(b.getKey())))
                .map(entry -> valueString(entry.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String valueString(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsJsonPrimitive().getAsString();
        return element.toString();
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
