package qupath.ext.tiatoolbox.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceResponseTest {

    @Test
    void parsesOkResponseWithGeojsonPaths() {
        var resp = InferenceResponse.fromJson("""
                {"status": "ok", "geojson": ["/tmp/a.geojson", "/tmp/b.geojson"]}
                """);

        assertTrue(resp.ok());
        assertEquals(List.of("/tmp/a.geojson", "/tmp/b.geojson"), resp.geojson());
        assertNull(resp.message());
    }

    @Test
    void parsesErrorResponse() {
        var resp = InferenceResponse.fromJson("""
                {"status": "error", "message": "boom", "trace": "stack"}
                """);

        assertFalse(resp.ok());
        assertEquals("boom", resp.message());
        assertEquals("stack", resp.trace());
    }

    @Test
    void okIsTrueOnlyForOkStatus() {
        assertTrue(new InferenceResponse("ok", List.of(), null, null).ok());
        assertFalse(new InferenceResponse("error", null, null, null).ok());
        assertFalse(new InferenceResponse(null, null, null, null).ok());
    }
}
