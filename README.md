# tiatoolbox-qupath-extension

A QuPath extension that runs [TIAToolbox](https://github.com/tissueimageanalytics/tiatoolbox)
inference engines from inside QuPath. Inference is performed by tiatoolbox's
Python engines; the extension provides the QuPath-side UI, manages a Python
sidecar process, and imports the resulting annotations into the open image.

## Architecture

The project has two halves: a Java extension that runs in QuPath, and a Python
sidecar that wraps the tiatoolbox engines. They communicate over
[Py4J](https://www.py4j.org/) using its `ClientServer` mode.

```
┌──────────── QuPath (JVM) ─────────┐  Py4J   ┌──── Python sidecar ────┐
│  TIAToolboxExtension              │ ◄─────► │  qupath_tiatoolbox     │
│  TIAController (FXML dialog)      │         │   bridge.TIATask       │
│  BridgeManager (spawn + connect)  │         │   runners.run_engine   │
│  ResultImporter (GeoJSON → hier.) │         │   tiatoolbox.engine    │
└───────────────────────────────────┘         └────────────────────────┘
```

When the user runs a model, the extension launches the Python sidecar as a
child process, opens a Py4J connection to it, and sends a JSON request
describing the model and slide. The sidecar invokes the matching tiatoolbox
engine with `output_type="qupath"`, which writes a GeoJSON file. The extension
reads that file and adds the objects to the QuPath hierarchy.

## Requirements

- QuPath 0.7.0 or compatible.
- A Python 3.10+ environment containing `tiatoolbox >= 2.0.1` and
  `py4j >= 0.10.9.7`.
- JDK 21+, but only to build the extension. End users do not need a JDK
  installed; QuPath ships its own JRE.

## Installation

### 1. Set up the Python environment

```bash
conda create -n tiatoolbox-qupath python=3.10 -y
conda activate tiatoolbox-qupath
pip install tiatoolbox==2.0.1 py4j==0.10.9.7
pip install -e ./python   # installs the qupath-tiatoolbox sidecar
```

The sidecar package itself is small; tiatoolbox provides the engines and
pretrained models.

### 2. Build the extension JAR

```bash
./gradlew clean jar
# → build/libs/qupath-extension-tiatoolbox-0.1.0.jar
```

### 3. Install the JAR into QuPath

QuPath 0.7 loads extensions from `<QuPath dir>/extensions/catalogs`, and only
those listed in `registry.json` are picked up. For a developer install, place
the JAR at:

```
<QuPath dir>/extensions/catalogs/Manual/TIAToolbox extension/v0.1.0/main-jar/
```

and add the following entry to
`<QuPath dir>/extensions/catalogs/registry.json` under `catalogs`:

```json
{
  "name": "Manual",
  "description": "Locally-installed extensions",
  "uri": "file:///local",
  "rawUri": "file:///local",
  "deletable": true,
  "extensions": [
    {
      "name": "TIAToolbox extension",
      "installedVersion": "v0.1.0",
      "optionalDependenciesInstalled": false
    }
  ]
}
```

End users would normally install via QuPath's extension-manager UI; the path
above is the manual developer install.

## Usage

1. Open a whole-slide image in QuPath.
2. Select **Extensions → TIAToolbox → Run TIAToolbox…**
3. On first use, set the path to the Python interpreter from the conda
   environment created above (for example
   `/path/to/anaconda3/envs/tiatoolbox-qupath/bin/python`). This setting is
   persisted across sessions.
4. Choose a model and device (`cpu`, `cuda`, or `mps`), adjust batch size if
   needed, and click **Run**.

The first run for a given model will download its pretrained weights from
the HuggingFace repository `TIACentre/TIAToolbox_pretrained_weights`. Once
inference completes, the resulting annotations are imported into the active
image's hierarchy.

### Models included

| Model | Engine | Output |
|-------|--------|--------|
| `resnet18-kather100k` | PatchPredictor | Patch-level colorectal tissue classification (9 classes). |
| `fcn-tissue_mask` | SemanticSegmentor | Foreground tissue / background mask. |
| `hovernet_fast-pannuke` | MultiTaskSegmentor | Per-nucleus polygons with 6 type classes (PanNuke). |

To add or remove models, edit
`src/main/resources/qupath/ext/tiatoolbox/ui/models.json` and rebuild the JAR.

## Repository layout

```
.
├── build.gradle.kts                    # Java/Gradle build
├── settings.gradle.kts
├── gradle/                             # Gradle wrapper
├── src/main/
│   ├── java/qupath/ext/tiatoolbox/
│   │   ├── TIAToolboxExtension.java         # extension entry point
│   │   ├── core/                            # bridge and import logic
│   │   │   ├── TiaRunner.java               # mirrors the Python TIATask interface
│   │   │   ├── ProgressListener.java
│   │   │   ├── InferenceRequest.java
│   │   │   ├── InferenceResponse.java
│   │   │   ├── PythonLauncher.java
│   │   │   ├── BridgeManager.java
│   │   │   └── ResultImporter.java
│   │   └── ui/                              # JavaFX UI
│   │       ├── TIACommand.java
│   │       ├── TIAController.java
│   │       ├── TIAPrefs.java
│   │       └── ModelInfo.java
│   └── resources/
│       ├── META-INF/services/qupath.lib.gui.extensions.QuPathExtension
│       └── qupath/ext/tiatoolbox/ui/{tiatoolbox_control.fxml, strings.properties, models.json}
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
response. The contract is defined by `InferenceRequest.java` /
`InferenceResponse.java` on the Java side and `runners.run_engine` on the
Python side.

```jsonc
// request
{
  "engine": "patch_predictor",          // also: "semantic_segmentor", "multi_task_segmentor"
  "model":  "resnet18-kather100k",
  "wsi_path": "/abs/path/to/slide.svs",
  "save_dir": "/tmp/tia-{uuid}",
  "device":   "cpu",
  "batch_size": 8,
  "num_workers": 0,
  "classes": ["Adipose", "Ignore*", "..."]
}

// response
{ "status": "ok",    "geojson": ["/tmp/tia-{uuid}/0.geojson"] }
{ "status": "error", "message": "...", "trace": "..." }
```

While inference is running, the sidecar invokes
`ProgressListener.onStatus(String)` at significant transitions and
`ProgressListener.onHeartbeat(int)` roughly every two seconds with the elapsed
running time.

## Current limitations

- Inference runs on the entire slide; restricting it to a region of interest
  is not yet supported.
- The slide must have a real file path on disk; in-memory and virtual
  servers are not supported.
- Progress reporting is coarse, since the underlying engines do not expose
  tile-level progress.
- Only one inference run at a time per QuPath instance.

## License

Apache 2.0; see [LICENSE](LICENSE).
