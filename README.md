# tiatoolbox-qupath-extension

A QuPath extension that runs [TIAToolbox](https://github.com/tissueimageanalytics/tiatoolbox)
inference engines from inside QuPath. Click a button, pick a model, get
annotations on the image.

## Architecture

Two pieces, one click:

```
┌──────────── QuPath (JVM) ─────────┐  Py4J   ┌──── Python sidecar ────┐
│  TIAToolboxExtension              │ ◄─────► │  qupath_tiatoolbox     │
│  TIAController (FXML dialog)      │         │   bridge.TIATask       │
│  BridgeManager (spawn + connect)  │         │   runners.run_engine   │
│  ResultImporter (GeoJSON → hier.) │         │   tiatoolbox.engine    │
└───────────────────────────────────┘         └────────────────────────┘
```

The extension spawns the Python sidecar as a child process, talks to it over
[Py4J](https://www.py4j.org/) (`ClientServer` mode), and imports the engine's
GeoJSON output into the QuPath hierarchy. No Groovy, no REST, no terminals.

## Requirements

- **QuPath 0.7.0** or compatible.
- **Python 3.10+** in a Conda or venv environment with:
  - `tiatoolbox >= 2.0.1`
  - `py4j >= 0.10.9.7`
- **JDK 21+** — only required to *build* the extension. End users only need
  QuPath (which ships its own JRE).

## Install

### 1. Set up a Python environment

```bash
conda create -n tiatoolboxv2 python=3.10 -y
conda activate tiatoolboxv2
pip install tiatoolbox==2.0.1 py4j==0.10.9.7
pip install -e ./python   # installs the qupath-tiatoolbox sidecar
```

> The sidecar is a tiny pure-Python package; the heavy lifting is done by the
> tiatoolbox engines you already have installed.

### 2. Build the QuPath jar

```bash
./gradlew clean jar
# → build/libs/qupath-extension-tiatoolbox-0.1.0.jar
```

### 3. Drop the jar into QuPath

```bash
mkdir -p "<QuPath dir>/extensions/catalogs/Manual/TIAToolbox extension/v0.1.0/main-jar"
cp build/libs/qupath-extension-tiatoolbox-0.1.0.jar \
  "<QuPath dir>/extensions/catalogs/Manual/TIAToolbox extension/v0.1.0/main-jar/"
```

QuPath 0.7 only loads extensions registered in its `registry.json`. Add a
`Manual` catalog entry to `<QuPath dir>/extensions/catalogs/registry.json`:

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

(In normal use you would install via QuPath's extension-manager UI; this manual
path is the developer install.)

## Usage

1. Open a whole-slide image in QuPath.
2. **Extensions → TIAToolbox → Run TIAToolbox…**
3. First time only: set the path to your conda env's Python in the dialog
   (e.g. `/path/to/anaconda3/envs/tiatoolboxv2/bin/python`).
4. Pick a model, pick a device (cpu / cuda / mps), set a batch size, click **Run**.

The extension launches the Python sidecar in the background, runs the engine on
your slide, and imports the resulting GeoJSON as annotations or detections.
First run will download the pretrained weights from HuggingFace
(`TIACentre/TIAToolbox_pretrained_weights`).

### Models shipped in v1

| Model | Engine | Output |
|---|---|---|
| `resnet18-kather100k` | PatchPredictor | Tile-level colorectal tissue classification (9 classes) |
| `fcn-tissue_mask` | SemanticSegmentor | Tissue / background mask |
| `hovernet_fast-pannuke` | MultiTaskSegmentor | Per-nucleus polygons + 6 type classes (PanNuke) |

To add more models, edit
`src/main/resources/qupath/ext/tiatoolbox/ui/models.json` and rebuild.

## Repo layout

```
.
├── build.gradle.kts                    # Java/Gradle build
├── settings.gradle.kts
├── gradle/                              # gradle wrapper
├── src/main/
│   ├── java/qupath/ext/tiatoolbox/
│   │   ├── TIAToolboxExtension.java         # entry point
│   │   ├── core/                            # bridge, prefs-free utilities
│   │   │   ├── TiaRunner.java               # mirrors Python TIATask
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
    ├── pyproject.toml                       # name: qupath-tiatoolbox
    └── src/qupath_tiatoolbox/
        ├── __init__.py
        ├── __main__.py                      # python -m qupath_tiatoolbox
        ├── bridge.py                        # Py4J ClientServer entry point
        └── runners.py                       # engine dispatch
```

## Wire protocol

Java sends a JSON request, Python returns a JSON response. The contract lives
in `InferenceRequest.java` / `InferenceResponse.java` (Java) and
`runners.run_engine` (Python).

```jsonc
// request
{
  "engine": "patch_predictor",          // | "semantic_segmentor" | "multi_task_segmentor"
  "model":  "resnet18-kather100k",
  "wsi_path": "/abs/path/to/slide.svs",
  "save_dir": "/tmp/tia-{uuid}",
  "device":   "cpu",
  "batch_size": 8,
  "num_workers": 0
}
// response
{ "status": "ok",    "geojson": ["/tmp/tia-{uuid}/0.geojson"] }
{ "status": "error", "message": "...", "trace": "..." }
```

Progress: while inference runs, Python invokes the Java-side
`ProgressListener.onHeartbeat(int seconds)` every ~2 seconds and
`onStatus(String)` at significant transitions.

## Limitations (v1)

- Runs on the **whole slide**; ROI-restricted runs not yet supported.
- Slide must have a real file path on disk (no in-memory or virtual servers).
- Progress is coarse (engine internals don't expose tile-level progress).
- One model run at a time per QuPath instance.

## License

Apache 2.0 — see [LICENSE](LICENSE).
