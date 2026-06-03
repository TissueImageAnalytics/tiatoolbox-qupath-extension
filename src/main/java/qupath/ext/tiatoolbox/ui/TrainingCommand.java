package qupath.ext.tiatoolbox.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.util.ResourceBundle;

/** Builds and shows the patch-classifier training dialog. */
public final class TrainingCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TrainingCommand.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    private final QuPathGUI qupath;
    private Stage stage;

    public TrainingCommand(QuPathGUI qupath) {
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
            logger.error("Failed to show training dialog", e);
        }
    }

    private void buildStage() throws Exception {
        var url = TrainingCommand.class.getResource("training.fxml");
        if (url == null) {
            throw new IllegalStateException("Missing FXML: training.fxml");
        }
        var loader = new FXMLLoader(url, RES);
        loader.setClassLoader(getClass().getClassLoader());
        var root = loader.<javafx.scene.Parent>load();
        var controller = loader.<TrainingController>getController();
        controller.setQuPath(qupath);

        var stg = new Stage();
        stg.initOwner(qupath.getStage());
        stg.setTitle(RES.getString("training.title"));
        stg.setScene(new Scene(root));
        this.stage = stg;
    }
}
