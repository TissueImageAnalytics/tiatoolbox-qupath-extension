# tiatoolbox-qupath-extension

A QuPath extension that runs [TIAToolbox](https://github.com/tissueimageanalytics/tiatoolbox)
inference engines from inside QuPath. Inference is performed by tiatoolbox's
Python engines. The extension provides the QuPath-side GUI and scripting API,
manages a Python sidecar process, and imports the resulting annotations into
the open image.

## Requirements

- QuPath 0.7.0 or compatible.
- A Python 3.10+ environment named `tiatoolbox-qupath` containing
  `tiatoolbox >= 2.0.1` and `py4j >= 0.10.9.7`. Other env names work but the
  extension will auto-detect this one by default.
- JDK 21+ — only required to **build** the extension. End users do not need
  a JDK installed, QuPath ships its own JRE.

## Installation

### End users (via the QuPath extension catalog)

Recommended once a release is published. From inside QuPath:

1. **Extensions → Manage extensions** (the extension-manager UI).
2. **Add a catalog**, paste the URL of the TIA catalog:
   ```
   https://github.com/TissueImageAnalytics/qupath-catalog/main/catalog.json
   ```
3. Find **TIAToolbox extension** in the catalog list and click **Install**.

Then set up the Python environment (see [Python setup](#python-setup) below).

### Developers (local build)

From a clone of this repo:

```bash
./gradlew clean jar
# → build/libs/qupath-extension-tiatoolbox-<version>.jar
```

Add the JAR into QuPath using whichever you prefer:

- **Drag-and-drop** the JAR onto the running QuPath window, or
- **Copy** the JAR into the QuPath user-extensions folder. By default this
  is `<QuPath dir>/extensions/` (The exact path is shown under
  **Edit → Preferences → User directory**). 
  QuPath auto-loads any JAR placed in this folder. Then, restart QuPath.


### Python setup

In any conda flavour (Anaconda, Miniconda, Miniforge, Mambaforge):

```bash
conda create -n tiatoolbox-qupath python=3.10 -y
conda activate tiatoolbox-qupath
pip install "tiatoolbox>=2.0.1" "py4j>=0.10.9.7"
pip install -e ./python   # installs the qupath-tiatoolbox sidecar
```

The first time you open the dialog, the extension scans common conda
locations for an env named `tiatoolbox-qupath` and pre-fills the Python
path. You can override this via the dialog's **Browse…** button.

## Usage

### GUI

1. Open a whole-slide image (or a QuPath project).
2. **Extensions → TIAToolbox → Run TIAToolbox…**
3. Choose a model and device (`cpu`, `cuda`, or `mps`), adjust batch size
   if needed.
4. Choose the **Run on** scope:
   - **Current image** — runs on the active viewer.
   - **All project images** — iterates every entry in the open project,
     saving the resulting hierarchy back into each `.qpdata` so results
     survive a crash mid-batch.
5. Click **Run**. Progress is shown per image. Click **Cancel** to stop at
   the next image.

The first run for a given model downloads its pretrained weights from the
HuggingFace repository `TIACentre/TIAToolbox_pretrained_weights`.

### Scripting

The extension exposes a Groovy-friendly API. Three templates are
shipped under **Extensions → TIAToolbox → Script templates**:

- **Patch classification**: `resnet18-kather100k` on the current image.
- **Nucleus segmentation**: `hovernet_fast-pannuke` on the current image.
- **Batch process project**: iterates `project.getImageList()` and saves
  each entry's hierarchy.

The underlying API is easy to use:

```groovy
import qupath.ext.tiatoolbox.TIAToolbox

TIAToolbox.builder()
    .model("resnet18-kather100k")
    .device("cpu")            // "cpu" | "cuda" | "mps"
    .batchSize(8)
    .build()
    .run()                    // active viewer image; returns int (objects added)
```

`run(ImageData)` and `run(ImageData, ProgressListener)` overloads are
available for batch loops. The Python path used is the one configured in the
dialog (persistent across sessions), unless overridden with
`.pythonExecutable(...)` on the builder.

### Models included

| Model | Engine | Output |
|-------|--------|--------|
| `resnet18-kather100k` | PatchPredictor | Patch-level colorectal tissue classification (9 classes). |
| `resnet18-pcam` | PatchPredictor | Binary lymph-node metastasis classification (tumor vs. negative). |
| `resnet34-idars-msi` | PatchPredictor | IDaRS microsatellite-instability biomarker prediction (MSS / MSI). |
| `fcn-tissue_mask` | SemanticSegmentor | Foreground tissue / background mask. |
| `hovernet_fast-pannuke` | MultiTaskSegmentor | Per-nucleus polygons with 6 type classes (PanNuke). |
| `hovernetplus-oed` | MultiTaskSegmentor | Nuclei + epithelial layer segmentation, OED dataset. |

The list is curated from tiatoolbox's
[`pretrained_model.yaml`](https://github.com/TissueImageAnalytics/tiatoolbox/blob/master/tiatoolbox/data/pretrained_model.yaml).
To add or remove models, edit
[`src/main/resources/qupath/ext/tiatoolbox/ui/models.json`](src/main/resources/qupath/ext/tiatoolbox/ui/models.json)
and rebuild the JAR. Any pretrained
model accepted by the corresponding tiatoolbox engine works (see
`PatchPredictor`, `SemanticSegmentor`, `MultiTaskSegmentor`).



## Architecture

The project has two halves: a Java extension that runs inside QuPath, and a
Python sidecar that wraps the tiatoolbox engines. They communicate over
[Py4J](https://www.py4j.org/) in `ClientServer` mode. Python is the server
hosting a `TIATask` entry point, the JVM is the client.

```
┌──────────── QuPath (JVM) ─────────┐  Py4J   ┌──── Python sidecar ────┐
│  TIAToolboxExtension (menus)      │ ◄─────► │  qupath_tiatoolbox     │
│  TIAToolbox (scripting API)       │         │   bridge.TIATask       │
│  TIAController (FXML dialog)      │         │   runners.run_engine   │
│  BridgeManager (sidecar lifecycle)│         │   tiatoolbox.engine    │
│  ResultImporter (GeoJSON → hier.) │         │                        │
└───────────────────────────────────┘         └────────────────────────┘
```

The UI and Groovy scripting go through a single code path:
`TIAToolbox.run(imageData, listener)`. `TIAToolbox` owns a process-wide
`BridgeManager`. The first call spawns the Python sidecar.
Subsequent calls (including across Groovy scripts and batch images) reuse
it. The sidecar is restarted only if the
configured Python interpreter changes, and is shut down via a JVM hook on
QuPath exit.

Per call, the sidecar invokes the matching tiatoolbox engine with
`output_type="qupath"`, which writes a GeoJSON output file. Java reads that file
and adds the objects to the QuPath hierarchy.


## Repository layout

```
.
├── build.gradle.kts                         # Java/Gradle build
├── settings.gradle.kts
├── gradle/                                  # Gradle wrapper
├── src/main/
│   ├── java/qupath/ext/tiatoolbox/
│   │   ├── TIAToolboxExtension.java         # entry point: menus + script templates
│   │   ├── TIAToolbox.java                  # public scripting API (builder) + shared run() path
│   │   ├── core/                            # bridge, wire format, and import logic
│   │   │   ├── BridgeManager.java           # owns the Py4J ClientServer + Python subprocess
│   │   │   ├── PythonLauncher.java          # spawns `python -m qupath_tiatoolbox`
│   │   │   ├── PythonDetector.java          # locates the tiatoolbox-qupath conda env
│   │   │   ├── TiaRunner.java               # Java view of the Python TIATask interface
│   │   │   ├── InferenceRequest.java        # JSON sent to Python
│   │   │   ├── InferenceResponse.java       # JSON returned by Python
│   │   │   ├── ProgressListener.java        # status / heartbeat callbacks (Python → Java)
│   │   │   └── ResultImporter.java          # GeoJSON → QuPath hierarchy, reapplies PathClass
│   │   └── ui/                              # JavaFX dialog (scope radios, model picker, progress)
│   │       ├── TIACommand.java
│   │       ├── TIAController.java
│   │       ├── TIAPrefs.java
│   │       └── ModelInfo.java
│   └── resources/
│       ├── META-INF/services/qupath.lib.gui.extensions.QuPathExtension
│       └── qupath/ext/tiatoolbox/
│           ├── scripts/                      # Groovy script templates
│           │   ├── PatchClassification.groovy
│           │   ├── NucleusSegmentation.groovy
│           │   └── BatchProcessProject.groovy
│           └── ui/{tiatoolbox_control.fxml, strings.properties, models.json}
└── python/
    ├── pyproject.toml                       # qupath-tiatoolbox package
    └── src/qupath_tiatoolbox/
        ├── __init__.py
        ├── __main__.py                      # python -m qupath_tiatoolbox
        ├── bridge.py                        # Py4J ClientServer entry point
        └── runners.py                       # engine dispatch
```

## Wire protocol

The Java side sends a JSON request to the Python sidecar and receives a JSON
response. The contract is defined by
[`InferenceRequest.java`](src/main/java/qupath/ext/tiatoolbox/core/InferenceRequest.java)
and
[`InferenceResponse.java`](src/main/java/qupath/ext/tiatoolbox/core/InferenceResponse.java)
on the Java side, and consumed by `runners.run_engine` on the Python side.
Both sides share one open Py4J connection across calls.

```jsonc
// request: Java → Python
{
  "engine":      "patch_predictor",       // also: "semantic_segmentor", "multi_task_segmentor"
  "model":       "resnet18-kather100k",   // any model accepted by the engine
  "wsi_path":    "/abs/path/to/slide.svs",
  "save_dir":    "/tmp/tia-{uuid}",       // GeoJSON is written here
  "device":      "cpu",                   // "cpu" | "cuda" | "mps"
  "batch_size":  8,
  "num_workers": 0,
  "classes":     ["Tumor", "Stroma", "Immune cells", "..."]
                                          // remaps numeric labels to QuPath class names
}

// response: Python → Java
{ "status": "ok",    "geojson": ["/tmp/tia-{uuid}/0.geojson"] }
{ "status": "error", "message": "...", "trace": "..." }
```

While inference is running, the sidecar invokes two callbacks on the
Java-side `ProgressListener` over the same Py4J connection:

- `onStatus(String)` — at significant transitions (model load, tiling,
  postprocessing, GeoJSON write).
- `onHeartbeat(int elapsedSeconds)` — roughly every two seconds with the
elapsed running time

For batch runs, the Java side iterates `BatchEntry`s sequentially and reuses
the same connection (one request per image, with `save_dir` parameterised
per call).

## Current limitations

- Inference on region of interests is not yet supported.
- The slide(s) must have a real file path on disk.
- Only one inference run at a time per QuPath instance.

## License

See [LICENSE](LICENSE).
