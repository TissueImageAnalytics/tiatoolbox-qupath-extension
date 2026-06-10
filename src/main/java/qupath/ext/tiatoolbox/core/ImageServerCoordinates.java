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
        var region = displayRegionInFullSlideCoordinates(server);
        return new Origin(region.x(), region.y());
    }

    /**
     * Returns the source-slide region displayed by QuPath.
     *
     * <p>When {@link DisplayRegion#bounded()} is true, QuPath is displaying
     * only a bounded subregion of the image source. This is used to restrict
     * tiatoolbox WSI inference to the same visible canvas.
     */
    public static DisplayRegion displayRegionInFullSlideCoordinates(ImageServer<BufferedImage> server) {
        if (server instanceof CroppedImageServer cropped) {
            var region = cropped.getCropRegion();
            return new DisplayRegion(
                    region.getX(),
                    region.getY(),
                    region.getWidth(),
                    region.getHeight(),
                    true);
        }
        var openSlideRegion = openSlideRegionFor(server);
        if (openSlideRegion.bounded()) {
            return openSlideRegion;
        }
        return unboundedRegion(server.getWidth(), server.getHeight());
    }

    private static DisplayRegion openSlideRegionFor(ImageServer<BufferedImage> server) {
        if (!"OpenSlide".equals(server.getServerType())) {
            return unboundedRegion(server.getWidth(), server.getHeight());
        }
        try {
            var method = server.getClass().getMethod("dumpMetadata");
            var metadata = method.invoke(server);
            if (metadata instanceof String json) {
                return openSlideRegionFromMetadata(
                        JsonParser.parseString(json),
                        server.getWidth(),
                        server.getHeight());
            }
        } catch (ReflectiveOperationException | JsonParseException | IllegalStateException e) {
            logger.debug("Could not read OpenSlide bounds metadata for coordinate correction", e);
        }
        return unboundedRegion(server.getWidth(), server.getHeight());
    }

    static Origin openSlideOriginFromMetadata(JsonElement metadata, int serverWidth, int serverHeight) {
        var region = openSlideRegionFromMetadata(metadata, serverWidth, serverHeight);
        return new Origin(region.x(), region.y());
    }

    static DisplayRegion openSlideRegionFromMetadata(JsonElement metadata, int serverWidth, int serverHeight) {
        if (metadata == null || !metadata.isJsonObject()) {
            return unboundedRegion(serverWidth, serverHeight);
        }
        var props = metadata.getAsJsonObject();
        var boundsX = intProperty(props, "openslide.bounds-x");
        var boundsY = intProperty(props, "openslide.bounds-y");
        var boundsWidth = intProperty(props, "openslide.bounds-width");
        var boundsHeight = intProperty(props, "openslide.bounds-height");
        if (boundsX == null || boundsY == null || boundsWidth == null || boundsHeight == null) {
            return unboundedRegion(serverWidth, serverHeight);
        }
        if (serverWidth != boundsWidth || serverHeight != boundsHeight) {
            return unboundedRegion(serverWidth, serverHeight);
        }
        return new DisplayRegion(boundsX, boundsY, boundsWidth, boundsHeight, true);
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

    public record DisplayRegion(double x, double y, double width, double height, boolean bounded) {}

    private static DisplayRegion unboundedRegion(int width, int height) {
        return new DisplayRegion(0.0, 0.0, width, height, false);
    }
}
