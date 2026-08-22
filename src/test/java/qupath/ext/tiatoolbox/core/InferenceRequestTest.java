package qupath.ext.tiatoolbox.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the JSON wire contract with the Python sidecar
 * ({@code qupath_tiatoolbox.bridge.TIATask.runInference}). If a field name here
 * drifts from what Python reads, these tests fail.
 */
class InferenceRequestTest {

    @Test
    void toJsonUsesPythonWireFieldNames() {
        var req = new InferenceRequest(
                "patch_predictor", "resnet18-kather100k",
                "/slides/a.svs", "/tmp/out",
                "cpu", 8, 0,
                List.of("Tumor", "Stroma"),
                null, true,
                new InferenceRequest.VisibleBounds(1, 2, 3, 4));

        var json = JsonParser.parseString(req.toJson()).getAsJsonObject();

        assertEquals("patch_predictor", json.get("engine").getAsString());
        assertEquals("resnet18-kather100k", json.get("model").getAsString());
        assertEquals("/slides/a.svs", json.get("wsi_path").getAsString());
        assertEquals("/tmp/out", json.get("save_dir").getAsString());
        assertEquals("cpu", json.get("device").getAsString());
        assertEquals(8, json.get("batch_size").getAsInt());
        assertEquals(0, json.get("num_workers").getAsInt());
        assertTrue(json.get("auto_get_mask").getAsBoolean());

        var classes = json.getAsJsonArray("classes");
        assertEquals(2, classes.size());
        assertEquals("Tumor", classes.get(0).getAsString());
        assertEquals("Stroma", classes.get(1).getAsString());

        var bounds = json.getAsJsonObject("visible_bounds");
        assertEquals(1.0, bounds.get("x").getAsDouble());
        assertEquals(2.0, bounds.get("y").getAsDouble());
        assertEquals(3.0, bounds.get("width").getAsDouble());
        assertEquals(4.0, bounds.get("height").getAsDouble());
    }

    @Test
    void nullFieldsAreOmitted() {
        // Gson drops null fields, and the Python side reads them with
        // req.get(...), so their absence is the expected "not set" signal.
        var req = new InferenceRequest(
                "semantic_segmentor", "fcn-tissue_mask",
                "/slides/a.svs", "/tmp/out",
                "cpu", 8, 0,
                null, null, false, null);

        var json = JsonParser.parseString(req.toJson()).getAsJsonObject();

        assertFalse(json.has("classes"));
        assertFalse(json.has("artifact_path"));
        assertFalse(json.has("visible_bounds"));
    }
}
