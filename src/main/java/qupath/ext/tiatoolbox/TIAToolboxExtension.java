package qupath.ext.tiatoolbox;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.ui.RuntimeInstallCommand;
import qupath.ext.tiatoolbox.ui.TIACommand;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/** QuPath extension entry point. Registered via {@code META-INF/services}. */
public class TIAToolboxExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(TIAToolboxExtension.class);
    private static final ResourceBundle RES =
            ResourceBundle.getBundle("qupath.ext.tiatoolbox.ui.strings");

    private boolean installed;

    /** Curated Groovy templates shipped with the extension. */
    private static final List<ScriptTemplate> SCRIPT_TEMPLATES = List.of(
            new ScriptTemplate("Patch classification",
                    "TIAToolbox - Patch Classification.groovy",
                    "qupath/ext/tiatoolbox/scripts/PatchClassification.groovy"),
            new ScriptTemplate("Nucleus segmentation",
                    "TIAToolbox - Nucleus Segmentation.groovy",
                    "qupath/ext/tiatoolbox/scripts/NucleusSegmentation.groovy"),
            new ScriptTemplate("Batch process project",
                    "TIAToolbox - Batch Process Project.groovy",
                    "qupath/ext/tiatoolbox/scripts/BatchProcessProject.groovy")
    );

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            logger.debug("{} already installed", getName());
            return;
        }
        installed = true;

        var menu = qupath.getMenu("Extensions>" + RES.getString("extension.name"), true);

        var runItem = new MenuItem(RES.getString("menu.run"));
        var command = new TIACommand(qupath);
        runItem.setOnAction(e -> command.run());
        menu.getItems().add(runItem);

        var installItem = new MenuItem(RES.getString("menu.install-runtime"));
        var installCommand = new RuntimeInstallCommand(qupath);
        installItem.setOnAction(e -> installCommand.run());
        menu.getItems().add(installItem);

        menu.getItems().add(new SeparatorMenuItem());

        var scriptsMenu = new Menu(RES.getString("menu.scripts"));
        for (var tmpl : SCRIPT_TEMPLATES) {
            var item = new MenuItem(tmpl.label);
            item.setOnAction(e -> openScriptTemplate(qupath, tmpl));
            scriptsMenu.getItems().add(item);
        }
        menu.getItems().add(scriptsMenu);
    }

    private void openScriptTemplate(QuPathGUI qupath, ScriptTemplate tmpl) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            Dialogs.showErrorMessage(getName(), "Script editor is not available.");
            return;
        }
        String content = loadResource(tmpl.resourcePath);
        if (content == null) {
            Dialogs.showErrorMessage(getName(), "Could not load template: " + tmpl.resourcePath);
            return;
        }
        editor.showScript(tmpl.scriptName, content);
    }

    private String loadResource(String classpath) {
        try (var in = TIAToolboxExtension.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) return null;
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            logger.error("Failed to load resource {}", classpath, e);
            return null;
        }
    }

    private record ScriptTemplate(String label, String scriptName, String resourcePath) {}

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
