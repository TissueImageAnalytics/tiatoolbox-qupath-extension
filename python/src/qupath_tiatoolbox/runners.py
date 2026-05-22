"""Engine dispatch.

Maps an engine name to a tiatoolbox engine class and runs it on a single WSI.

All engines share the EngineABC contract; the only structural difference between
tasks is which class we instantiate. Annotation engines run with
``output_type="qupath"`` so each input slide produces a GeoJSON file the Java
side can import directly. Artifact-export engines can request a different
output type and return file paths instead.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class _EngineSpec:
    module: str
    cls: str


_ENGINES: dict[str, _EngineSpec] = {
    "patch_predictor": _EngineSpec(
        "tiatoolbox.models.engine.patch_predictor", "PatchPredictor"
    ),
    "semantic_segmentor": _EngineSpec(
        "tiatoolbox.models.engine.semantic_segmentor", "SemanticSegmentor"
    ),
    "multi_task_segmentor": _EngineSpec(
        "tiatoolbox.models.engine.multi_task_segmentor", "MultiTaskSegmentor"
    ),
    "nucleus_detector": _EngineSpec(
        "tiatoolbox.models.engine.nucleus_detector", "NucleusDetector"
    ),
    "nucleus_instance_segmentor": _EngineSpec(
        "tiatoolbox.models.engine.nucleus_instance_segmentor",
        "NucleusInstanceSegmentor",
    ),
    "deep_feature_extractor": _EngineSpec(
        "tiatoolbox.models.engine.deep_feature_extractor",
        "DeepFeatureExtractor",
    ),
}


def _load(engine_name: str):
    spec = _ENGINES.get(engine_name)
    if spec is None:
        raise ValueError(
            f"Unknown engine '{engine_name}'. Known: {sorted(_ENGINES)}"
        )
    mod = __import__(spec.module, fromlist=[spec.cls])
    return getattr(mod, spec.cls)


def run_engine(
    *,
    engine: str,
    model: str,
    wsi_path: str,
    save_dir: str,
    device: str = "cpu",
    batch_size: int = 8,
    num_workers: int = 0,
    classes: Sequence[str] | None = None,
    output_type: str = "qupath",
) -> dict[str, Any]:
    """Run one tiatoolbox engine on one WSI and return output file paths.

    Returns
    -------
    dict with either:
        ``geojson``: list[str] of output GeoJSON paths, or
        ``artifacts``: list[str] of output paths for non-annotation engines.
    """
    EngineCls = _load(engine)

    wsi = Path(wsi_path)
    if not wsi.exists():
        raise FileNotFoundError(f"WSI not found: {wsi_path}")

    out_dir = Path(save_dir)
    out_dir.mkdir(parents=True, exist_ok=True)


    logger.info(
        "Running %s with model=%s on %s (device=%s, batch_size=%d)",
        EngineCls.__name__, model, wsi.name, device, batch_size,
    )

    eng = EngineCls(
        model=model,
        batch_size=batch_size,
        num_workers=num_workers,
        device=device,
        verbose=False,
    )

    result = eng.run(
        images=[str(wsi)],
        patch_mode=False,
        save_dir=str(out_dir),
        overwrite=True,
        output_type=output_type,
    )

    if output_type == "qupath":
        geojsons = _collect_geojson_paths(result, out_dir)
        logger.info("Engine produced %d GeoJSON file(s): %s", len(geojsons), geojsons)

        if classes:
            for p in geojsons:
                _relabel_geojson_in_place(p, classes)

        return {"geojson": [str(p) for p in geojsons]}

    artifacts = _collect_artifact_paths(result, out_dir)
    logger.info("Engine produced %d artifact(s): %s", len(artifacts), artifacts)
    return {"artifacts": [str(p) for p in artifacts]}


def _relabel_geojson_in_place(path: Path, classes: Sequence[str]) -> None:
    """Replace numeric ``properties.classification.name`` values with the
    matching human-readable label from ``classes``.

    QuPath looks up its built-in PathClasses (Tumor / Stroma / Immune cells / …)
    by string name; without this rewrite every patch lands as a generic
    ``Class N`` and uses tiatoolbox's tab10 colour, which is hard to tell apart
    in the viewer.
    """
    try:
        with open(path, "r") as fh:
            data = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Could not re-label %s: %s", path, exc)
        return

    features = data.get("features") or []
    relabeled = 0
    for feat in features:
        cls = (feat.get("properties") or {}).get("classification")
        if not isinstance(cls, dict):
            continue
        name = cls.get("name")
        if isinstance(name, (int, float)):
            idx = int(name)
            if 0 <= idx < len(classes):
                cls["name"] = classes[idx]
                # Keep the embedded RGB so QuPath's GeoJSON parser remains
                # happy; the Java importer overrides the colour with QuPath's
                # built-in PathClass palette where one exists.
                relabeled += 1

    if relabeled:
        with open(path, "w") as fh:
            json.dump(data, fh)
        logger.info("Re-labelled %d/%d features in %s", relabeled, len(features), path)


_GEOJSON_SUFFIXES = (".geojson", ".json")


def _flatten_paths(value: Any) -> Any:
    """Yield ``Path`` objects from arbitrarily-nested dicts/lists/tuples."""
    if value is None:
        return
    if isinstance(value, (str, Path)):
        yield Path(value)
    elif isinstance(value, dict):
        for v in value.values():
            yield from _flatten_paths(v)
    elif isinstance(value, (list, tuple, set)):
        for v in value:
            yield from _flatten_paths(v)


def _collect_geojson_paths(result: Any, out_dir: Path) -> list[Path]:
    """Normalise the engine's return value to a list of GeoJSON paths.

    tiatoolbox writes ``.json`` (not ``.geojson``) when ``output_type='qupath'``
    and silently appends a timestamp suffix to ``save_dir`` to avoid collisions
    (so the actual output sits in a sibling directory). Values are sometimes
    nested (e.g. ``MultiTaskSegmentor`` returns a list per WSI), so we walk the
    structure and accept both ``.json`` and ``.geojson`` extensions.
    """
    paths = list(_flatten_paths(result))
    found = [p for p in paths if p.suffix in _GEOJSON_SUFFIXES and p.exists()]
    if found:
        return found

    # Search the save_dir and any sibling directory whose name starts with it
    # (tiatoolbox appends a timestamp to avoid clobbering existing dirs).
    candidate_dirs = [out_dir]
    parent = out_dir.parent
    if parent.exists():
        prefix = out_dir.name
        candidate_dirs.extend(d for d in parent.iterdir()
                              if d.is_dir() and d != out_dir and d.name.startswith(prefix))
    found = []
    for d in candidate_dirs:
        for suffix in _GEOJSON_SUFFIXES:
            found.extend(d.rglob(f"*{suffix}"))
    return sorted(set(found))


def _collect_artifact_paths(result: Any, out_dir: Path) -> list[Path]:
    """Normalise artifact output to a list of paths.

    Deep feature extraction writes a zarr store rather than GeoJSON. We accept
    any existing file or directory reported by tiatoolbox and fall back to the
    save directory if the engine only populated it on disk.
    """
    paths = list(_flatten_paths(result))
    found = [p for p in paths if p.exists()]
    if found:
        return sorted(set(found))

    candidate_dirs = [out_dir]
    parent = out_dir.parent
    if parent.exists():
        prefix = out_dir.name
        candidate_dirs.extend(
            d for d in parent.iterdir()
            if d.is_dir() and d != out_dir and d.name.startswith(prefix)
        )
    found = []
    for d in candidate_dirs:
        if d.exists():
            found.append(d)
            found.extend(p for p in d.iterdir())
    return sorted(set(found))
