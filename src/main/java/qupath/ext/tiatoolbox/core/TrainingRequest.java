package qupath.ext.tiatoolbox.core;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/** Wire format sent from Java to Python for project training. */
public record TrainingRequest(
        String task_type,
        List<Slide> slides,
        List<String> classes,
        Map<String, Integer> class_mapping,
        ModelSpec model,
        Options options,
        String output_dir
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public record Slide(
            String name,
            String wsi_path,
            String geojson_path,
            String split,
            double origin_x,
            double origin_y
    ) {}

    public record ModelSpec(
            String model_type,
            String backbone,
            boolean pretrained
    ) {}

    public record Options(
            String device,
            int epochs,
            int batch_size,
            double learning_rate,
            int patch_size,
            int stride,
            double mpp,
            double validation_fraction,
            int max_patches_per_class_slide,
            int seed,
            double min_mask_ratio,
            int log_every_n_steps
    ) {}
}
