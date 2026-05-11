/**
 * TIAToolbox — HoVerNet nucleus instance segmentation on the current image.
 *
 * GPU is strongly recommended; CPU runs are slow on full slides. Tune the
 * batch size to fit in GPU memory.
 *
 * Prerequisites:
 *   1. Open a whole-slide image in the active viewer.
 *   2. Set the Python interpreter once via the Run TIAToolbox… dialog.
 */

import qupath.ext.tiatoolbox.TIAToolbox

int added = TIAToolbox.builder()
        .model("hovernet_fast-pannuke")
        .device("cuda")           // fall back to "cpu" if no GPU
        .batchSize(4)
        .build()
        .run()

print "TIAToolbox: added ${added} nucleus objects to the hierarchy."
