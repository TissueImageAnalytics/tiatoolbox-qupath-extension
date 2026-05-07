package qupath.ext.tiatoolbox;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.ui.TIACommand;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ResourceBundle;

/** QuPath extension entry point. Registered via {@code META-INF/services}. */
public class TIAToolboxExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(TIAToolboxExtension.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            logger.debug("{} already installed", getName());
            return;
        }
        installed = true;

        var menu = qupath.getMenu("Extensions>" + RES.getString("extension.name"), true);
        var item = new MenuItem(RES.getString("menu.run"));
        var command = new TIACommand(qupath);
        item.setOnAction(e -> command.run());
        menu.getItems().add(item);
    }

    @Override
    public String getName() {
        return RES.getString("extension.name");
    }

    @Override
    public String getDescription() {
        return RES.getString("extension.description");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse(RES.getString("extension.qupath.version"));
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "qupath", "tiatoolbox-qupath-extension");
    }
}
