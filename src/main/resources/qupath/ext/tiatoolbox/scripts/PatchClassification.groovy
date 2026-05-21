/**
 * TIAToolbox — Patch classification on the current image.
 *
 * Adjust the model name to any entry in the extension's bundled `models.json`
 * (Run TIAToolbox… dialog → Model dropdown), or use any pretrained name
 * accepted by tiatoolbox's PatchPredictor with an explicit `.engine(...)`.
 *
 * Prerequisites:
 *   1. Open a whole-slide image in the active viewer.
 *   2. Install the Python runtime once via Extensions → TIAToolbox →
 *      Install Python runtime…
 */

import qupath.ext.tiatoolbox.TIAToolbox

int added = TIAToolbox.builder()
        .model("resnet18-kather100k")
        .device("cpu")            // "cpu" | "cuda" | "mps"
        .batchSize(8)
        .build()
        .run()

print "TIAToolbox: added ${added} objects to the hierarchy."
