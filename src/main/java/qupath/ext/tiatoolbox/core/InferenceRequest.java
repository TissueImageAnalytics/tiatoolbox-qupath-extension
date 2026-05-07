package qupath.ext.tiatoolbox.core;

import com.google.gson.Gson;
import java.util.List;

/**
 * Wire format sent from Java to Python. Field names are part of the contract
 * with {@code qupath_tiatoolbox.bridge.TIATask.runInference} — do not rename
 * without updating the Python side.
 */
public record InferenceRequest(
        String engine,
        String model,
        String wsi_path,
        String save_dir,
        String device,
        int batch_size,
        int num_workers,
        List<String> classes
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }
}
