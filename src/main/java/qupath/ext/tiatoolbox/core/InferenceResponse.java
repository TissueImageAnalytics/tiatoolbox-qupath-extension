package qupath.ext.tiatoolbox.core;

import com.google.gson.Gson;
import java.util.List;

/**
 * Wire format returned from Python to Java. Mirrors the JSON written by
 * {@code TIATask.runInference} — keep field names in sync with the Python side.
 */
public record InferenceResponse(
        String status,
        List<String> geojson,
        String message,
        String trace
) {
    private static final Gson GSON = new Gson();

    public static InferenceResponse fromJson(String json) {
        return GSON.fromJson(json, InferenceResponse.class);
    }

    public boolean ok() {
        return "ok".equals(status);
    }
}
