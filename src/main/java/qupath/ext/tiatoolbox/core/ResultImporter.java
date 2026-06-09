package qupath.ext.tiatoolbox.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.CroppedImageServer;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads GeoJSON files written by tiatoolbox into a QuPath hierarchy. */
public final class ResultImporter {

    private static final Logger logger = LoggerFactory.getLogger(ResultImporter.class);
    private static final Gson GSON = new Gson();

    private ResultImporter() {}

    /**
     * Read every GeoJSON file in {@code paths}, add its objects to
     * {@code imageData}'s hierarchy, and return the count.
     */
    public static int importGeoJson(ImageData<BufferedImage> imageData, List<String> paths) throws IOException {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }
        var shift = coordinateShiftFor(imageData);
        if (!shift.isZero()) {
            logger.info(
                    "Applying QuPath crop-origin correction to imported GeoJSON: dx={}, dy={} (crop origin x={}, y={})",
                    shift.dx(), shift.dy(), -shift.dx(), -shift.dy());
        }
        var hierarchy = imageData.getHierarchy();
        var added = new ArrayList<PathObject>();
        for (var p : paths) {
            var path = Path.of(p);
            List<PathObject> objs = readGeoJsonObjects(path, shift);
            logger.info("Read {} objects from {}", objs.size(), p);
            applyClassifications(objs, path);
            added.addAll(objs);
        }
        hierarchy.addObjects(added);
        hierarchy.fireHierarchyChangedEvent(ResultImporter.class);
        return added.size();
    }

    private static List<PathObject> readGeoJsonObjects(Path path, CoordinateShift shift) throws IOException {
        if (shift.isZero()) {
            return PathIO.readObjects(path.toFile());
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var geojson = JsonParser.parseReader(reader);
            translateGeoJson(geojson, shift.dx(), shift.dy());
            var bytes = GSON.toJson(geojson).getBytes(StandardCharsets.UTF_8);
            try (var input = new ByteArrayInputStream(bytes)) {
                return PathIO.readObjectsFromGeoJSON(input);
            }
        }
    }

    private static CoordinateShift coordinateShiftFor(ImageData<BufferedImage> imageData) {
        var server = imageData.getServer();
        if (server instanceof CroppedImageServer cropped) {
            var region = cropped.getCropRegion();
            return new CoordinateShift(-region.getX(), -region.getY());
        }
        return openSlideCoordinateShiftFor(server);
    }

    private static CoordinateShift openSlideCoordinateShiftFor(ImageServer<BufferedImage> server) {
        if (!"OpenSlide".equals(server.getServerType())) {
            return CoordinateShift.NONE;
        }
        try {
            var method = server.getClass().getMethod("dumpMetadata");
            var metadata = method.invoke(server);
            if (metadata instanceof String json) {
                return openSlideCoordinateShiftFromMetadata(
                        JsonParser.parseString(json),
                        server.getWidth(),
                        server.getHeight());
            }
        } catch (ReflectiveOperationException | JsonParseException | IllegalStateException e) {
            logger.debug("Could not read OpenSlide bounds metadata for coordinate correction", e);
        }
        return CoordinateShift.NONE;
    }

    static CoordinateShift openSlideCoordinateShiftFromMetadata(JsonElement metadata, int serverWidth, int serverHeight) {
        if (metadata == null || !metadata.isJsonObject()) {
            return CoordinateShift.NONE;
        }
        var props = metadata.getAsJsonObject();
        var boundsX = intProperty(props, "openslide.bounds-x");
        var boundsY = intProperty(props, "openslide.bounds-y");
        var boundsWidth = intProperty(props, "openslide.bounds-width");
        var boundsHeight = intProperty(props, "openslide.bounds-height");
        if (boundsX == null || boundsY == null || boundsWidth == null || boundsHeight == null) {
            return CoordinateShift.NONE;
        }
        if (serverWidth != boundsWidth || serverHeight != boundsHeight) {
            return CoordinateShift.NONE;
        }
        return new CoordinateShift(-boundsX, -boundsY);
    }

    private static Integer intProperty(JsonObject obj, String name) {
        var element = obj.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | ClassCastException | IllegalStateException e) {
            logger.debug("Could not parse OpenSlide integer property {}={}", name, element);
            return null;
        }
    }

    static JsonElement translateGeoJson(JsonElement geojson, double dx, double dy) {
        if (geojson == null || geojson.isJsonNull() || (dx == 0.0 && dy == 0.0)) {
            return geojson;
        }
        if (!geojson.isJsonObject()) {
            return geojson;
        }
        translateGeoJsonObject(geojson.getAsJsonObject(), dx, dy);
        return geojson;
    }

    private static void translateGeoJsonObject(JsonObject obj, double dx, double dy) {
        translateBbox(obj, dx, dy);
        var typeElement = obj.get("type");
        var type = typeElement != null && typeElement.isJsonPrimitive()
                ? typeElement.getAsString()
                : "";

        switch (type) {
            case "FeatureCollection" -> {
                var features = obj.getAsJsonArray("features");
                if (features == null) return;
                for (var feature : features) {
                    if (feature.isJsonObject()) {
                        translateGeoJsonObject(feature.getAsJsonObject(), dx, dy);
                    }
                }
            }
            case "Feature" -> {
                var geometry = obj.get("geometry");
                if (geometry != null && geometry.isJsonObject()) {
                    translateGeoJsonObject(geometry.getAsJsonObject(), dx, dy);
                }
            }
            case "GeometryCollection" -> {
                var geometries = obj.getAsJsonArray("geometries");
                if (geometries == null) return;
                for (var geometry : geometries) {
                    if (geometry.isJsonObject()) {
                        translateGeoJsonObject(geometry.getAsJsonObject(), dx, dy);
                    }
                }
            }
            default -> {
                var coordinates = obj.get("coordinates");
                if (coordinates != null) {
                    translateCoordinates(coordinates, dx, dy);
                }
            }
        }
    }

    private static void translateCoordinates(JsonElement coordinates, double dx, double dy) {
        if (!coordinates.isJsonArray()) {
            return;
        }
        var array = coordinates.getAsJsonArray();
        if (array.size() >= 2 && isNumber(array.get(0)) && isNumber(array.get(1))) {
            array.set(0, newNumber(array.get(0).getAsDouble() + dx));
            array.set(1, newNumber(array.get(1).getAsDouble() + dy));
            return;
        }
        for (var child : array) {
            translateCoordinates(child, dx, dy);
        }
    }

    private static void translateBbox(JsonObject obj, double dx, double dy) {
        var bbox = obj.get("bbox");
        if (bbox == null || !bbox.isJsonArray()) {
            return;
        }
        var array = bbox.getAsJsonArray();
        if (array.size() != 4) {
            return;
        }
        if (isNumber(array.get(0)) && isNumber(array.get(1)) &&
                isNumber(array.get(2)) && isNumber(array.get(3))) {
            array.set(0, newNumber(array.get(0).getAsDouble() + dx));
            array.set(1, newNumber(array.get(1).getAsDouble() + dy));
            array.set(2, newNumber(array.get(2).getAsDouble() + dx));
            array.set(3, newNumber(array.get(3).getAsDouble() + dy));
        }
    }

    private static boolean isNumber(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static JsonElement newNumber(double value) {
        return GSON.toJsonTree(value);
    }

    record CoordinateShift(double dx, double dy) {
        static final CoordinateShift NONE = new CoordinateShift(0.0, 0.0);

        boolean isZero() {
            return dx == 0.0 && dy == 0.0;
        }
    }

    /**
     * tiatoolbox writes {@code properties.classification.name} as a numeric
     * label by default; the Python sidecar re-labels these to strings when a
     * class list is supplied. The embedded {@code [r,g,b]} colour is left in
     * place so QuPath's parser can still build the {@link PathClass}.
     *
     * <p>This step then strips that per-feature colour and re-resolves the
     * class through {@link PathClass#fromString(String)}, so QuPath's
     * built-in palette wins for known names (Tumor → red, Stroma → green, …).
     * For unknown names QuPath assigns a stable colour from its registry.
     */
    private static void applyClassifications(List<PathObject> objects, Path geojson) throws IOException {
        for (var obj : objects) {
            var pc = obj.getPathClass();
            if (pc != null && pc.getName() != null) {
                obj.setPathClass(PathClass.fromString(pc.getName()));
            }
        }
    }
}
