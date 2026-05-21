package qupath.ext.tiatoolbox.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.util.ResourceBundle;

/** Builds and shows the Python runtime install wizard. One stage per load. */
public final class RuntimeInstallCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeInstallCommand.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    private final QuPathGUI qupath;
    private final Runnable onHidden;
    private Stage stage;

    public RuntimeInstallCommand(QuPathGUI qupath) {
        this(qupath, null);
    }

    public RuntimeInstallCommand(QuPathGUI qupath, Runnable onHidden) {
        this.qupath = qupath;
        this.onHidden = onHidden;
    }

    @Override
    public void run() {
        try {
            if (stage == null) {
                buildStage();
            }
            stage.show();
            stage.toFront();
        } catch (Exception e) {
            logger.error("Failed to show runtime install dialog", e);
        }
    }

    private void buildStage() throws Exception {
        var url = RuntimeInstallCommand.class.getResource("runtime_install.fxml");
        if (url == null) {
            throw new IllegalStateException("Missing FXML: runtime_install.fxml");
        }
        var loader = new FXMLLoader(url, RES);
        loader.setClassLoader(getClass().getClassLoader());
        var root = loader.<javafx.scene.Parent>load();

        var stg = new Stage();
        stg.initOwner(qupath.getStage());
        stg.setTitle(RES.getString("runtime.install.title"));
        stg.setScene(new Scene(root));
        if (onHidden != null) {
            stg.setOnHidden(e -> onHidden.run());
        }
        this.stage = stg;
    }
}
