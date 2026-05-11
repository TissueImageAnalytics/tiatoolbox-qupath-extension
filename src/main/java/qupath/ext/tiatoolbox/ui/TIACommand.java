package qupath.ext.tiatoolbox.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.util.ResourceBundle;

/** Lazily builds and shows the extension dialog. One stage per extension load. */
public final class TIACommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TIACommand.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    private final QuPathGUI qupath;
    private Stage stage;
    private TIAController controller;

    public TIACommand(QuPathGUI qupath) {
        this.qupath = qupath;
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
            logger.error("Failed to show TIAToolbox dialog", e);
        }
    }

    private void buildStage() throws Exception {
        var url = TIACommand.class.getResource("tiatoolbox_control.fxml");
        if (url == null) {
            throw new IllegalStateException("Missing FXML: tiatoolbox_control.fxml");
        }
        var loader = new FXMLLoader(url, RES);
        loader.setClassLoader(getClass().getClassLoader());
        var root = loader.<javafx.scene.Parent>load();
        controller = loader.getController();
        controller.setQuPath(qupath);

        var stg = new Stage();
        stg.initOwner(qupath.getStage());
        stg.setTitle(RES.getString("title"));
        stg.setScene(new Scene(root));
        // The Python sidecar is now JVM-wide (owned by TIAToolbox); closing
        // the dialog leaves it running so subsequent runs and Groovy scripts
        // don't pay the cold-start cost.
        stg.setResizable(false);
        this.stage = stg;
    }
}
