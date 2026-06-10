package qupath.ext.tiatoolbox.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultImporterTest {

    @Test
    void translateGeoJsonShiftsGeometryCoordinatesOnly() {
        var geojson = JsonParser.parseString("""
                {
                  "type": "FeatureCollection",
                  "bbox": [15, 25, 130, 240],
                  "features": [
                    {
                      "type": "Feature",
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[110, 220], [130, 240], [110, 220]]]
                      },
                      "properties": {
                        "classification": {
                          "name": "Tissue",
                          "color": [255, 0, 0]
                        }
                      }
                    },
                    {
                      "type": "Feature",
                      "bbox": [15, 25, 15, 25],
                      "geometry": {
                        "type": "Point",
                        "coordinates": [15, 25]
                      }
                    }
                  ]
                }
                """);

        ResultImporter.translateGeoJson(geojson, -10, -20);

        var root = geojson.getAsJsonObject();
        var bbox = root.getAsJsonArray("bbox");
        assertEquals(5.0, bbox.get(0).getAsDouble());
        assertEquals(5.0, bbox.get(1).getAsDouble());
        assertEquals(120.0, bbox.get(2).getAsDouble());
        assertEquals(220.0, bbox.get(3).getAsDouble());

        var features = root.getAsJsonArray("features");
        var polygon = features.get(0).getAsJsonObject()
                .getAsJsonObject("geometry")
                .getAsJsonArray("coordinates")
                .get(0).getAsJsonArray()
                .get(0).getAsJsonArray();
        assertEquals(100.0, polygon.get(0).getAsDouble());
        assertEquals(200.0, polygon.get(1).getAsDouble());

        var color = features.get(0).getAsJsonObject()
                .getAsJsonObject("properties")
                .getAsJsonObject("classification")
                .getAsJsonArray("color");
        assertEquals(255, color.get(0).getAsInt());
        assertEquals(0, color.get(1).getAsInt());
        assertEquals(0, color.get(2).getAsInt());

        var point = features.get(1).getAsJsonObject()
                .getAsJsonObject("geometry")
                .getAsJsonArray("coordinates");
        assertEquals(5.0, point.get(0).getAsDouble());
        assertEquals(5.0, point.get(1).getAsDouble());
    }

    @Test
    void openSlideOriginUsesBoundsWhenServerIsCropped() {
        var metadata = JsonParser.parseString("""
                {
                  "openslide.level[0].width": "272128",
                  "openslide.level[0].height": "294144",
                  "openslide.bounds-x": "2048",
                  "openslide.bounds-y": "14592",
                  "openslide.bounds-width": "100352",
                  "openslide.bounds-height": "168448"
                }
                """);

        var origin = ImageServerCoordinates.openSlideOriginFromMetadata(metadata, 100352, 168448);

        assertEquals(2048.0, origin.x());
        assertEquals(14592.0, origin.y());
    }

    @Test
    void openSlideRegionUsesBoundsWhenServerIsCropped() {
        var metadata = JsonParser.parseString("""
                {
                  "openslide.level[0].width": "272128",
                  "openslide.level[0].height": "294144",
                  "openslide.bounds-x": "2048",
                  "openslide.bounds-y": "14592",
                  "openslide.bounds-width": "100352",
                  "openslide.bounds-height": "168448"
                }
                """);

        var region = ImageServerCoordinates.openSlideRegionFromMetadata(metadata, 100352, 168448);

        assertEquals(2048.0, region.x());
        assertEquals(14592.0, region.y());
        assertEquals(100352.0, region.width());
        assertEquals(168448.0, region.height());
        assertEquals(true, region.bounded());
    }

    @Test
    void openSlideOriginIsSkippedWhenServerIsNotCropped() {
        var metadata = JsonParser.parseString("""
                {
                  "openslide.bounds-x": "2048",
                  "openslide.bounds-y": "14592",
                  "openslide.bounds-width": "100352",
                  "openslide.bounds-height": "168448"
                }
                """);

        var origin = ImageServerCoordinates.openSlideOriginFromMetadata(metadata, 272128, 294144);

        assertEquals(0.0, origin.x());
        assertEquals(0.0, origin.y());
    }

    @Test
    void openSlideRegionIsUnboundedWhenServerIsNotCropped() {
        var metadata = JsonParser.parseString("""
                {
                  "openslide.bounds-x": "2048",
                  "openslide.bounds-y": "14592",
                  "openslide.bounds-width": "100352",
                  "openslide.bounds-height": "168448"
                }
                """);

        var region = ImageServerCoordinates.openSlideRegionFromMetadata(metadata, 272128, 294144);

        assertEquals(0.0, region.x());
        assertEquals(0.0, region.y());
        assertEquals(272128.0, region.width());
        assertEquals(294144.0, region.height());
        assertEquals(false, region.bounded());
    }
}
