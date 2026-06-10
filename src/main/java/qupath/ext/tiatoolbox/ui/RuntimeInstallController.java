package qupath.ext.tiatoolbox.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.TIAToolbox;
import qupath.ext.tiatoolbox.install.RuntimeInstallOptions;
import qupath.ext.tiatoolbox.install.RuntimeInstaller;
import qupath.ext.tiatoolbox.install.RuntimePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backs {@code runtime_install.fxml}. Runs {@link RuntimeInstaller} on a
 * background task, streams the log into the dialog, and re-enables the
 * Install button on completion so the user can repair or refresh the env.
 */
public class RuntimeInstallController {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeInstallController.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    @FXML private Label pathLabel;
    @FXML private VBox statusBox;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private TitledPane logPane;
    @FXML private TextArea logArea;
    @FXML private CheckBox localCloneCheckBox;
    @FXML private TextField localCloneField;
    @FXML private Button browseLocalCloneButton;
    @FXML private CheckBox editableCheckBox;
    @FXML private Button installButton;
    @FXML private Button closeButton;

    private final AtomicReference<Task<Path>> currentTask = new AtomicReference<>();
    private final AtomicReference<RuntimeInstaller> currentInstaller = new AtomicReference<>();

    @FXML
    private void initialize() {
        pathLabel.setText(RuntimePaths.runtimeRoot().toString());
        localCloneField.disableProperty().bind(localCloneCheckBox.selectedProperty().not());
        browseLocalCloneButton.disableProperty().bind(localCloneCheckBox.selectedProperty().not());
        editableCheckBox.disableProperty().bind(localCloneCheckBox.selectedProperty().not());
        localCloneCheckBox.selectedProperty().addListener((obs, old, selected) -> {
            if (selected && localCloneField.getText().isBlank()) {
                localCloneField.setText(RES.getString("runtime.install.local-clone.default"));
            }
        });

        // If a previous install left a working venv, surface that immediately.
        var python = RuntimePaths.installedPython();
        if (python != null) {
            setInfoStatusStyle();
            statusLabel.setText(MessageFormat.format(
                    RES.getString("runtime.install.status.already"), python));
            installButton.setText(RES.getString("runtime.install.button.reinstall"));
        }
    }

    @FXML
    private void onInstall() {
        RuntimeInstallOptions options;
        try {
            options = installOptions();
        } catch (IllegalArgumentException e) {
            statusLabel.textProperty().unbind();
            clearStatusStyle();
            statusLabel.setText(String.format(
                    RES.getString("runtime.install.status.failed"), e.getMessage()));
            return;
        }

        logArea.clear();
        logPane.setExpanded(true);
        statusLabel.textProperty().unbind();
        clearStatusStyle();
        statusLabel.setText(RES.getString("runtime.install.status.running"));
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        installButton.setDisable(true);
        // Keep Close as 'Cancel' while running so the user can abort.
        closeButton.setText(RES.getString("runtime.install.button.cancel"));

        var task = installTask(options);
        currentTask.set(task);
        var th = new Thread(task, "tiatoolbox-runtime-install");
        th.setDaemon(true);
        th.start();
    }

    @FXML
    private void onBrowseLocalClone() {
        var chooser = new DirectoryChooser();
        chooser.setTitle(RES.getString("runtime.install.local-clone.path"));
        initialLocalCloneDirectory().ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));

        var window = closeButton.getScene() == null ? null : closeButton.getScene().getWindow();
        var selected = chooser.showDialog(window);
        if (selected != null) {
            localCloneField.setText(selected.toPath().toString());
            localCloneCheckBox.setSelected(true);
        }
    }

    @FXML
    private void onClose() {
        var task = currentTask.get();
        if (task != null && task.isRunning()) {
            var installer = currentInstaller.get();
            if (installer != null) {
                installer.cancel();
            }
            task.cancel(true);
            return;
        }
        var stage = (Stage) closeButton.getScene().getWindow();
        stage.close();
    }

    private Task<Path> installTask(RuntimeInstallOptions options) {
        return new Task<>() {
            @Override
            protected Path call() throws Exception {
                java.util.function.Consumer<String> sink = line -> Platform.runLater(() -> {
                    logArea.appendText(line);
                    logArea.appendText("\n");
                });
                var installer = new RuntimeInstaller(sink);
                currentInstaller.set(installer);
                try {
                    TIAToolbox.closeBridge();
                    return installer.install(options);
                } finally {
                    currentInstaller.compareAndSet(installer, null);
                }
            }

            @Override
            protected void succeeded() {
                progressBar.setProgress(1.0);
                statusLabel.textProperty().unbind();
                setInfoStatusStyle();
                statusLabel.setText(MessageFormat.format(
                        RES.getString("runtime.install.status.done"), getValue()));
                installButton.setDisable(false);
                installButton.setText(RES.getString("runtime.install.button.reinstall"));
                closeButton.setText(RES.getString("runtime.install.button.close"));
                currentTask.set(null);
                currentInstaller.set(null);
            }

            @Override
            protected void failed() {
                progressBar.setProgress(0.0);
                statusLabel.textProperty().unbind();
                clearStatusStyle();
                var ex = getException();
                logger.error("Runtime install failed", ex);
                statusLabel.setText(String.format(
                        RES.getString("runtime.install.status.failed"),
                        ex == null ? "unknown" : ex.getMessage()));
                installButton.setDisable(false);
                closeButton.setText(RES.getString("runtime.install.button.close"));
                currentTask.set(null);
                currentInstaller.set(null);
                if (ex != null) {
                    logArea.appendText("\n" + ex + "\n");
                }
            }

            @Override
            protected void cancelled() {
                progressBar.setProgress(0.0);
                statusLabel.textProperty().unbind();
                clearStatusStyle();
                statusLabel.setText(RES.getString("runtime.install.status.cancelled"));
                installButton.setDisable(false);
                closeButton.setText(RES.getString("runtime.install.button.close"));
                currentTask.set(null);
                currentInstaller.set(null);
            }
        };
    }

    private RuntimeInstallOptions installOptions() {
        if (!localCloneCheckBox.isSelected()) {
            return RuntimeInstallOptions.defaultInstall();
        }
        var text = localCloneField.getText() == null ? "" : localCloneField.getText().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Choose a local TIAToolbox clone or enter a git URL.");
        }
        return RuntimeInstallOptions.fromTiatoolboxSource(text, editableCheckBox.isSelected());
    }

    private java.util.Optional<Path> initialLocalCloneDirectory() {
        var text = localCloneField.getText() == null ? "" : localCloneField.getText().trim();
        if (!text.isBlank()) {
            try {
                var path = Path.of(text);
                if (Files.isDirectory(path)) {
                    return java.util.Optional.of(path);
                }
                var parent = path.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return java.util.Optional.of(parent);
                }
            } catch (RuntimeException ignored) {
                // Fall back to the default chooser location.
            }
        }
        return java.util.Optional.empty();
    }

    private void setInfoStatusStyle() {
        statusBox.setStyle("-fx-background-color: #fff7e0; -fx-border-color: #d68a00; "
                + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 8 10 8 10;");
        statusLabel.setStyle("-fx-text-fill: #3f2a00;");
    }

    private void clearStatusStyle() {
        statusBox.setStyle("");
        statusLabel.setStyle("");
    }
}
