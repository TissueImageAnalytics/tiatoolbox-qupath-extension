package qupath.ext.tiatoolbox.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.TIAToolbox;
import qupath.ext.tiatoolbox.core.ProgressListener;
import qupath.ext.tiatoolbox.install.RuntimePaths;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
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
    @FXML private RadioButton scopeCurrent;
    @FXML private RadioButton scopeProject;
    @FXML private Label modelDescription;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Hyperlink resultsLink;
    @FXML private Button runButton;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private RuntimeInstallCommand runtimeInstallCommand;
    private final AtomicReference<Task<Integer>> currentTask = new AtomicReference<>();
    /** Results folder of the most recent run, for the "Open results folder" link. */
    private Path lastResultsDir;

    /** Full, unfiltered model list; the ComboBox shows a FilteredList view of it. */
    private final ObservableList<ModelInfo> allModels =
            FXCollections.observableArrayList(ModelInfo.loadBundled());

    public void setQuPath(QuPathGUI qupath) {
        this.qupath = qupath;
        this.runtimeInstallCommand = new RuntimeInstallCommand(qupath, this::refreshRuntimeBanner);
        // Disable "All project images" when no project is open. The binding
        // tracks live changes so opening a project mid-dialog enables it.
        scopeProject.disableProperty().bind(qupath.projectProperty().isNull());
    }

    @FXML
    private void initialize() {
        installModelFilter();
        modelChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                modelDescription.setText(sel == null ? "" : sel.description()));
        modelChoice.getSelectionModel().selectFirst();

        deviceChoice.setItems(FXCollections.observableArrayList("cpu", "cuda", "mps"));
        deviceChoice.getSelectionModel().select(TIAPrefs.device.get());
        TIAPrefs.device.bind(deviceChoice.getSelectionModel().selectedItemProperty());

        batchSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 256, TIAPrefs.batchSize.get()));
        TIAPrefs.batchSize.bind(batchSpinner.valueProperty());

        refreshRuntimeBanner();
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
        // Typing over a selected model's full name crashes the ComboBox skin
        // ("start must be <= end", JDK-8228055). Clear the editor first so the
        // keystroke lands in an empty field. Filter runs before the skin.
        editor.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))) {
                return;
            }
            var selected = modelChoice.getSelectionModel().getSelectedItem();
            if (selected != null && selected.toString().equals(editor.getText())) {
                editor.clear();
            }
        });
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
     * Show the selected model's full summary text in an information dialog.
     * Backed by QuPath's {@link Dialogs#showMessageDialog(String, String)} so
     * it matches the rest of the UI and avoids a ControlsFX dependency.
     */
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
    @FXML
    private void onShowModelInfo() {
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
            runButton.disableProperty().bind(
                    Bindings.isNull(modelChoice.getSelectionModel().selectedItemProperty()));
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
        hideResultsLink();

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
                showResultsLink(runner.resultsDir());
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

    /** Open the most recent run's results folder in the system file browser. */
    @FXML
    private void onOpenResults() {
        resultsLink.setVisited(false);
        if (lastResultsDir != null) {
            GuiTools.browseDirectory(lastResultsDir.toFile());
        }
    }

    /** Reveal the "Open results folder" link, pointing at {@code dir}. */
    private void showResultsLink(Path dir) {
        lastResultsDir = dir;
        boolean show = dir != null && dir.toFile().isDirectory();
        if (show) {
            resultsLink.setText(MessageFormat.format(
                    RES.getString("ui.results.open"), dir.getFileName().toString()));
            if (resultsLink.getTooltip() != null) {
                resultsLink.getTooltip().setText(
                        RES.getString("ui.results.open-tip") + "\n" + dir.toAbsolutePath());
            }
        }
        resultsLink.setVisited(false);
        resultsLink.setVisible(show);
        resultsLink.setManaged(show);
    }

    private void hideResultsLink() {
        resultsLink.setVisible(false);
        resultsLink.setManaged(false);
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
