package qupath.ext.tiatoolbox.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.CroppedImageServer;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

/** Coordinate helpers for translating between QuPath display and source-slide coordinates. */
public final class ImageServerCoordinates {

    private static final Logger logger = LoggerFactory.getLogger(ImageServerCoordinates.class);

    private ImageServerCoordinates() {}

    /**
     * Returns the full-slide coordinate of QuPath's displayed image origin.
     *
     * <p>Most image servers display the full source image, so this is (0, 0).
     * QuPath's OpenSlide server can instead display an OpenSlide bounds crop,
     * while still reading tiles from the original level-0 canvas.
     */
    public static Origin displayOriginInFullSlideCoordinates(ImageServer<BufferedImage> server) {
        if (server instanceof CroppedImageServer cropped) {
            var region = cropped.getCropRegion();
            return new Origin(region.getX(), region.getY());
        }
        return openSlideOriginFor(server);
    }

    private static Origin openSlideOriginFor(ImageServer<BufferedImage> server) {
        if (!"OpenSlide".equals(server.getServerType())) {
            return Origin.ZERO;
        }
        try {
            var method = server.getClass().getMethod("dumpMetadata");
            var metadata = method.invoke(server);
            if (metadata instanceof String json) {
                return openSlideOriginFromMetadata(
                        JsonParser.parseString(json),
                        server.getWidth(),
                        server.getHeight());
            }
        } catch (ReflectiveOperationException | JsonParseException | IllegalStateException e) {
            logger.debug("Could not read OpenSlide bounds metadata for coordinate correction", e);
        }
        return Origin.ZERO;
    }

    static Origin openSlideOriginFromMetadata(JsonElement metadata, int serverWidth, int serverHeight) {
        if (metadata == null || !metadata.isJsonObject()) {
            return Origin.ZERO;
        }
        var props = metadata.getAsJsonObject();
        var boundsX = intProperty(props, "openslide.bounds-x");
        var boundsY = intProperty(props, "openslide.bounds-y");
        var boundsWidth = intProperty(props, "openslide.bounds-width");
        var boundsHeight = intProperty(props, "openslide.bounds-height");
        if (boundsX == null || boundsY == null || boundsWidth == null || boundsHeight == null) {
            return Origin.ZERO;
        }
        if (serverWidth != boundsWidth || serverHeight != boundsHeight) {
            return Origin.ZERO;
        }
        return new Origin(boundsX, boundsY);
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

    public record Origin(double x, double y) {
        public static final Origin ZERO = new Origin(0.0, 0.0);

        public boolean isZero() {
            return x == 0.0 && y == 0.0;
        }
    }
}
