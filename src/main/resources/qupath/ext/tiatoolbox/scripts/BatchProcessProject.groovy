/**
 * TIAToolbox — run a model on every image in the current QuPath project.
 *
 * Iterates project entries, reads each image's data, runs inference, and
 * saves the resulting hierarchy back to the project file. Results survive
 * a crash mid-batch because data is saved after every image.
 *
 * Prerequisites:
 *   1. A QuPath project must be open.
 *   2. Set the Python interpreter once via the Run TIAToolbox… dialog.
 */

import qupath.ext.tiatoolbox.TIAToolbox
import qupath.lib.scripting.QP

def project = QP.getProject()
if (project == null) {
    print "No project is open — open a project or use the per-image scripts."
    return
}

def runner = TIAToolbox.builder()
        .model("resnet18-kather100k")
        .device("cpu")
        .batchSize(8)
        .build()

def entries = project.getImageList()
print "Running on ${entries.size()} image(s)…"

entries.eachWithIndex { entry, i ->
    print "[${i + 1}/${entries.size()}] ${entry.getImageName()}"
    try {
        def imageData = entry.readImageData()
        int added = runner.run(imageData)
        entry.saveImageData(imageData)
        print "  + ${added} objects"
    } catch (Exception e) {
        print "  ! failed: ${e.message}"
    }
}

print "TIAToolbox batch finished."
