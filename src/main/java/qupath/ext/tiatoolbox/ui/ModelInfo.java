package qupath.ext.tiatoolbox.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Curated entry from {@code resources/qupath/ext/tiatoolbox/ui/models.json}. The
 * {@code engine} string must match a key in the Python-side runners registry;
 * {@code classes}, if non-empty, is the human-readable label list (in label
 * order) that the Python sidecar substitutes for the model's numeric labels
 * before writing GeoJSON.
 */
public record ModelInfo(String name, String engine, String task, String description, List<String> classes) {

    @Override
    public String toString() {
        return name + " — " + task;
    }

    /** Load the bundled curated model list from classpath resources. */
    public static List<ModelInfo> loadBundled() {
        var url = ModelInfo.class.getResource("models.json");
        if (url == null) {
            throw new IllegalStateException("Missing resource: models.json");
        }
        try (var in = url.openStream();
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var type = new TypeToken<List<ModelInfo>>() {}.getType();
            List<ModelInfo> list = new Gson().fromJson(reader, type);
            return List.copyOf(list);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load models.json", e);
        }
    }
}
