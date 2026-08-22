package qupath.ext.tiatoolbox.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInfoTest {

    // Must stay in sync with the Python runners registry
    // (qupath_tiatoolbox.runners._ENGINES).
    private static final Set<String> KNOWN_ENGINES = Set.of(
            "patch_predictor", "semantic_segmentor",
            "multi_task_segmentor", "nucleus_detector");

    @Test
    void bundledModelsParseAndAreWellFormed() {
        List<ModelInfo> models = ModelInfo.loadBundled();

        assertFalse(models.isEmpty(), "bundled models.json should not be empty");
        for (var model : models) {
            assertNotNull(model.name());
            assertFalse(model.name().isBlank(), "model name must not be blank");
            assertTrue(KNOWN_ENGINES.contains(model.engine()),
                    () -> "unknown engine '" + model.engine() + "' for model " + model.name());
        }
    }

    @Test
    void toStringCombinesNameAndTask() {
        var model = new ModelInfo("resnet18-kather100k", "patch_predictor",
                "Patch classification", "desc", List.of());
        assertEquals("resnet18-kather100k — Patch classification", model.toString());
    }
}
