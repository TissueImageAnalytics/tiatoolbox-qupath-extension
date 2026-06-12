package qupath.ext.tiatoolbox.ui;

import com.google.gson.JsonParser;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.tiatoolbox.install.RuntimePaths;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

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
            if (qupath == null || qupath.getProject() == null) {
                Dialogs.showMessageDialog(
                        RES.getString("training.title"),
                        RES.getString("training.project-required"));
                return;
            }
            var python = RuntimePaths.installedPython();
            if (python == null) {
                Dialogs.showErrorMessage(RES.getString("title"), RES.getString("error.python-not-set"));
                return;
            }
            if (!isTrainingModuleAvailable()) {
                Dialogs.showMessageDialog(
                        RES.getString("training.title"),
                        MessageFormat.format(
                                RES.getString("training.unavailable.message"),
                                RES.getString("runtime.install.local-clone.default")));
                return;
            }
            if (stage == null) {
                buildStage();
            }
            stage.show();
            stage.toFront();
        } catch (Exception e) {
            logger.error("Failed to show training dialog", e);
        }
    }

    private boolean isTrainingModuleAvailable() {
        for (var sitePackages : sitePackagesCandidates()) {
            if (hasTrainingModule(sitePackages)) {
                return true;
            }
            for (var source : directUrlSources(sitePackages)) {
                if (hasTrainingModule(source)) {
                    return true;
                }
            }
            for (var source : localOverrideSources(sitePackages)) {
                if (hasTrainingModule(source)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTrainingModule(Path sourceRoot) {
        return sourceRoot != null
                && Files.isRegularFile(sourceRoot.resolve("tiatoolbox/models/training/__init__.py"));
    }

    private static List<Path> sitePackagesCandidates() {
        var venv = RuntimePaths.venvDir();
        var candidates = new ArrayList<Path>();
        candidates.add(venv.resolve("Lib/site-packages"));
        candidates.add(venv.resolve("lib/python3.11/site-packages"));
        var lib = venv.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var entries = Files.list(lib)) {
                entries
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("python"))
                        .map(path -> path.resolve("site-packages"))
                        .forEach(candidates::add);
            } catch (IOException e) {
                logger.debug("Could not enumerate venv lib directory {}", lib, e);
            }
        }
        return candidates.stream().filter(Files::isDirectory).distinct().toList();
    }

    private static List<Path> directUrlSources(Path sitePackages) {
        var sources = new ArrayList<Path>();
        try (var entries = Files.list(sitePackages)) {
            entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("tiatoolbox-"))
                    .filter(path -> path.getFileName().toString().endsWith(".dist-info"))
                    .map(path -> path.resolve("direct_url.json"))
                    .filter(Files::isRegularFile)
                    .map(TrainingCommand::sourceFromDirectUrl)
                    .filter(path -> path != null)
                    .forEach(sources::add);
        } catch (IOException e) {
            logger.debug("Could not inspect direct_url.json in {}", sitePackages, e);
        }
        return sources;
    }

    private static Path sourceFromDirectUrl(Path directUrl) {
        try {
            var json = JsonParser.parseString(Files.readString(directUrl)).getAsJsonObject();
            var urlElement = json.get("url");
            if (urlElement == null || !urlElement.isJsonPrimitive()) {
                return null;
            }
            var url = urlElement.getAsString();
            if (!url.startsWith("file:")) {
                return null;
            }
            return Path.of(URI.create(url));
        } catch (Exception e) {
            logger.debug("Could not parse {}", directUrl, e);
            return null;
        }
    }

    private static List<Path> localOverrideSources(Path sitePackages) {
        var pth = sitePackages.resolve("qupath_tiatoolbox_local_tiatoolbox.pth");
        if (!Files.isRegularFile(pth)) {
            return List.of();
        }
        try {
            var matcher = Pattern.compile("p\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(Files.readString(pth));
            if (matcher.find()) {
                return List.of(Path.of(unescapePythonString(matcher.group(1))));
            }
        } catch (Exception e) {
            logger.debug("Could not inspect {}", pth, e);
        }
        return List.of();
    }

    private static String unescapePythonString(String value) {
        var out = new StringBuilder(value.length());
        var escaping = false;
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (!escaping) {
                if (c == '\\') {
                    escaping = true;
                } else {
                    out.append(c);
                }
                continue;
            }
            switch (c) {
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                default -> out.append(c);
            }
            escaping = false;
        }
        if (escaping) {
            out.append('\\');
        }
        return out.toString();
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
