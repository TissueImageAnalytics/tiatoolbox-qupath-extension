/**
 * TIAToolbox — KongNet nucleus *detection* on the current image.
 *
 * Detection (as opposed to instance segmentation) outputs a single point per
 * nucleus, classified by cell type. This is much faster than HoVerNet-style
 * segmentation and is the right pick when you only need centroids — e.g. for
 * cell counting, density maps, or downstream graph-based analysis.
 *
 * Available models (any can be swapped in via .model(...)):
 *   - KongNet_CoNIC_1     — 6-class colorectal H&E (Neutrophil / Epithelial /
 *                            Lymphocyte / Plasma / Eosinophil / Connective).
 *   - KongNet_PanNuke_1   — 5-class pan-tissue H&E.
 *   - KongNet_MONKEY_1    — 3-class immune cells in PAS-stained kidney.
 *   - KongNet_Det_MIDOG_1 — mitotic-figure detection in H&E.
 *   - KongNet_PUMA_T1_3   — 3-class melanoma H&E.
 *   - KongNet_PUMA_T2_3   — 10-class melanoma H&E.
 *   - mapde-conic, mapde-crchisto — single-class MapDe detectors.
 *
 * GPU is strongly recommended; on CPU a full WSI can take an hour or more.
 *
 * Prerequisites:
 *   1. Open a whole-slide image in the active viewer.
 *   2. Install the Python runtime once via Extensions → TIAToolbox →
 *      Install Python runtime…
 */

import qupath.ext.tiatoolbox.TIAToolbox

int added = TIAToolbox.builder()
        .model("KongNet_CoNIC_1")
        .device("cuda")           // fall back to "cpu" if no GPU
        .batchSize(8)
        .build()
        .run()

print "TIAToolbox: added ${added} nucleus detections to the hierarchy."
