package qupath.ext.tiatoolbox.core;

import com.google.gson.Gson;

/** Wire format returned from Python after training. */
public record TrainingResponse(
        String status,
        String artifact,
        String output_dir,
        String message,
        String trace,
        int train_samples,
        int val_samples
) {
    private static final Gson GSON = new Gson();

    public static TrainingResponse fromJson(String json) {
        return GSON.fromJson(json, TrainingResponse.class);
    }

    public boolean ok() {
        return "ok".equals(status);
    }
}
