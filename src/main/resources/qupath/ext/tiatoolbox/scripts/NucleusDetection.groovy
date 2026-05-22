/**
 * TIAToolbox — nucleus detection on the current image.
 *
 * Use a detector preset such as `mapde-conic` to produce nucleus annotations
 * that QuPath can import directly.
 *
 * Prerequisites:
 *   1. Open a whole-slide image in the active viewer.
 *   2. Install the Python runtime once via Extensions → TIAToolbox →
 *      Install Python runtime…
 */

import qupath.ext.tiatoolbox.TIAToolbox

int added = TIAToolbox.builder()
        .model("mapde-conic")
        .device("cuda")           // fall back to "cpu" if no GPU
        .batchSize(64)
        .build()
        .run()

print "TIAToolbox: added ${added} nucleus annotations to the hierarchy."