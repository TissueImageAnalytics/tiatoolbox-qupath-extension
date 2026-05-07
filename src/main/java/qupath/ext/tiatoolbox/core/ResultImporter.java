package qupath.ext.tiatoolbox.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads GeoJSON files written by tiatoolbox into a QuPath hierarchy. */
public final class ResultImporter {

    private static final Logger logger = LoggerFactory.getLogger(ResultImporter.class);

    private ResultImporter() {}

    /**
     * Read every GeoJSON file in {@code paths}, add its objects to
     * {@code imageData}'s hierarchy, and return the count.
     */
    public static int importGeoJson(ImageData<BufferedImage> imageData, List<String> paths) throws IOException {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }
        var hierarchy = imageData.getHierarchy();
        var added = new ArrayList<PathObject>();
        for (var p : paths) {
            var path = Path.of(p);
            List<PathObject> objs = PathIO.readObjects(path.toFile());
            logger.info("Read {} objects from {}", objs.size(), p);
            applyClassifications(objs, path);
            added.addAll(objs);
        }
        hierarchy.addObjects(added);
        hierarchy.fireHierarchyChangedEvent(ResultImporter.class);
        return added.size();
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
