/**
 * TIAToolbox — deep feature extraction on the current image.
 *
 * This writes a zarr store to the run directory instead of importing
 * annotations into QuPath.
 *
 * Prerequisites:
 *   1. Open a whole-slide image in the active viewer.
 *   2. Install the Python runtime once via Extensions → TIAToolbox →
 *      Install Python runtime…
 */

import qupath.ext.tiatoolbox.TIAToolbox

def artifacts = TIAToolbox.builder()
        .engine("deep_feature_extractor")
        .model("resnet18")
        .device("cuda")           // fall back to "cpu" if no GPU
        .batchSize(8)
        .build()
        .export()

print "TIAToolbox: saved feature artifact(s): ${artifacts}"