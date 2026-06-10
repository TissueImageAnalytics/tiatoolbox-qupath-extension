"""Engine dispatch.

Maps an engine name to a tiatoolbox engine class and runs it on a single WSI.

All engines share the EngineABC contract; the only structural difference between
tasks is which class we instantiate. We always run with ``output_type="qupath"``
in WSI mode so each input slide produces a GeoJSON file the Java side can import
directly.
"""

from __future__ import annotations

import json
import logging
import math
from dataclasses import dataclass
from functools import partial
from pathlib import Path
from typing import Any, Sequence

import numpy as np

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
}


# Engines whose GeoJSON output already uses class names sourced from the
# model's class_dict (so the index→label relabel pass should be skipped).
_ENGINES_WITH_NATIVE_CLASS_NAMES = frozenset({"nucleus_detector"})

_SEMANTIC_MIN_OBJECT_AREA = 5 * 5
_SEMANTIC_COMPONENT_AREA_THRESHOLD = 36
_SEMANTIC_MORPH_KERNEL_DIAMETER = 5
_VISIBLE_BOUNDS_MASK_BASELINE_BIN = 64
_VISIBLE_BOUNDS_MASK_MAX_DIMENSION = 8192


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
    artifact_path: str | None = None,
    auto_get_mask: bool = True,
    visible_bounds: Any | None = None,
) -> dict[str, Any]:
    """Run one tiatoolbox engine on one WSI and return GeoJSON output paths.

    Returns
    -------
    dict with keys:
        ``geojson``: list[str] of output GeoJSON paths (one per input WSI).
    """
    wsi = Path(wsi_path)
    if not wsi.exists():
        raise FileNotFoundError(f"WSI not found: {wsi_path}")

    out_dir = Path(save_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if artifact_path:
        return _run_artifact_engine(
            artifact_path=artifact_path,
            wsi=wsi,
            out_dir=out_dir,
            device=device,
            batch_size=batch_size,
            num_workers=num_workers,
            auto_get_mask=auto_get_mask,
            visible_bounds=visible_bounds,
        )

    EngineCls = _load(engine)

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

    run_kwargs: dict[str, Any] = dict(
        images=[str(wsi)],
        patch_mode=False,
        save_dir=str(out_dir),
        overwrite=True,
        output_type="qupath",
        auto_get_mask=auto_get_mask,
    )

    # NucleusDetector accepts a class_dict mapping numeric class IDs to
    # human-readable labels; when provided, the GeoJSON it writes already
    # uses those labels (so we skip the later relabel pass).
    if engine == "nucleus_detector" and classes:
        run_kwargs["class_dict"] = {i: name for i, name in enumerate(classes)}
        # Auto-detect tissue so we don't waste compute on background, and
        # keep memory in check on large slides. These mirror the defaults
        # used in tiatoolbox's nucleus-detection example notebook.
        run_kwargs.setdefault("auto_get_mask", True)
        run_kwargs.setdefault("memory_threshold", 50)

    _maybe_add_visible_bounds_mask(
        run_kwargs,
        wsi=wsi,
        visible_bounds=visible_bounds,
        auto_get_mask=auto_get_mask,
    )

    result = eng.run(**run_kwargs)

    geojsons = _collect_geojson_paths(result, out_dir)
    if engine == "semantic_segmentor":
        for p in geojsons:
            _sanitize_geojson_for_qupath(p)
    logger.info("Engine produced %d GeoJSON file(s): %s", len(geojsons), geojsons)

    if classes and engine not in _ENGINES_WITH_NATIVE_CLASS_NAMES:
        for p in geojsons:
            _relabel_geojson_in_place(p, classes)

    return {"geojson": [str(p) for p in geojsons]}


def _run_artifact_engine(
    *,
    artifact_path: str,
    wsi: Path,
    out_dir: Path,
    device: str,
    batch_size: int,
    num_workers: int,
    auto_get_mask: bool,
    visible_bounds: Any | None = None,
) -> dict[str, Any]:
    from .training import build_model

    from tiatoolbox.models.training import TrainingArtifactManifest

    artifact_file = Path(artifact_path)
    artifact = TrainingArtifactManifest.load(artifact_file)
    engine_name = _artifact_engine_name(artifact)
    model_info = dict(artifact.model.get("constructor") or {})
    class_dict = artifact.class_dict or {}
    num_classes = int(model_info.get("num_classes") or _num_classes_from_dict(class_dict))
    model = build_model(
        model_info,
        num_classes=num_classes,
        class_dict=class_dict,
    )
    qupath_training = bool((artifact.metadata or {}).get("qupath_training", False))
    if qupath_training:
        model.preproc_func = _qupath_training_preproc
        if engine_name == "SemanticSegmentor":
            metadata = artifact.metadata or {}
            min_confidence = float(metadata.get("semantic_min_confidence", 0.5))
            model.postproc_func = partial(
                _qupath_semantic_postproc,
                min_confidence=min_confidence,
                min_object_area=int(
                    metadata.get("semantic_min_object_area", _SEMANTIC_MIN_OBJECT_AREA)
                ),
                component_area_threshold=int(
                    metadata.get(
                        "semantic_component_area_threshold",
                        _SEMANTIC_COMPONENT_AREA_THRESHOLD,
                    )
                ),
                kernel_diameter=int(
                    metadata.get(
                        "semantic_morph_kernel_diameter",
                        _SEMANTIC_MORPH_KERNEL_DIAMETER,
                    )
                ),
            )
    artifact.load_weights(
        model,
        name="best",
        manifest_path=artifact_file,
        map_location="cpu",
        strict=True,
    )

    setup = artifact.to_engine_setup(
        engine_name,
        manifest_path=artifact_file,
        include_weights=False,
    )

    EngineCls = _artifact_engine_class(engine_name)
    logger.info(
        "Running %s artifact=%s on %s (device=%s, batch_size=%d)",
        engine_name,
        artifact_file,
        wsi.name,
        device,
        batch_size,
    )
    eng = EngineCls(
        model=model,
        batch_size=batch_size,
        num_workers=num_workers,
        device=device,
        verbose=False,
    )

    run_kwargs = dict(setup.run_kwargs)
    run_kwargs["batch_size"] = batch_size
    run_kwargs["num_workers"] = num_workers
    run_kwargs["device"] = device
    run_kwargs["auto_get_mask"] = auto_get_mask
    _maybe_add_visible_bounds_mask(
        run_kwargs,
        wsi=wsi,
        visible_bounds=visible_bounds,
        auto_get_mask=auto_get_mask,
    )
    result = eng.run(
        images=[str(wsi)],
        patch_mode=False,
        save_dir=str(out_dir),
        overwrite=True,
        output_type="qupath",
        **run_kwargs,
    )
    geojsons = _collect_geojson_paths(result, out_dir)
    if engine_name == "SemanticSegmentor":
        for p in geojsons:
            _sanitize_geojson_for_qupath(p)
    class_labels = _artifact_classes(artifact) if engine_name == "PatchPredictor" else []
    if class_labels:
        for p in geojsons:
            _relabel_geojson_in_place(p, class_labels)
    return {"geojson": [str(p) for p in geojsons]}


def _artifact_engine_name(artifact: Any) -> str:
    engine_configs = artifact.engine_configs or {}
    if len(engine_configs) == 1:
        return str(next(iter(engine_configs)))
    if artifact.task_type == "semantic_segmentation":
        return "SemanticSegmentor"
    return "PatchPredictor"


def _artifact_engine_class(engine_name: str) -> Any:
    if engine_name == "PatchPredictor":
        from tiatoolbox.models.engine.patch_predictor import PatchPredictor

        return PatchPredictor
    if engine_name == "SemanticSegmentor":
        from tiatoolbox.models.engine.semantic_segmentor import SemanticSegmentor

        return SemanticSegmentor
    raise ValueError(f"Unsupported training artifact engine: {engine_name}")


def _num_classes_from_dict(class_dict: dict[Any, Any]) -> int:
    if not class_dict:
        return 1
    return max(int(key) for key in class_dict) + 1


def _artifact_classes(artifact: Any) -> list[str]:
    class_dict = artifact.class_dict or {}
    labels: list[str] = []
    for key in sorted(class_dict, key=lambda item: int(item)):
        labels.append(str(class_dict[key]))
    return labels


def _qupath_training_preproc(image: np.ndarray) -> np.ndarray:
    image = np.asarray(image)
    if np.issubdtype(image.dtype, np.integer):
        return image.astype(np.float32) / 255.0
    return image.astype(np.float32, copy=False)


def _maybe_add_visible_bounds_mask(
    run_kwargs: dict[str, Any],
    *,
    wsi: Path,
    visible_bounds: Any | None,
    auto_get_mask: bool,
) -> None:
    if auto_get_mask or run_kwargs.get("masks") is not None:
        return

    mask = _visible_bounds_mask(wsi, visible_bounds)
    if mask is None:
        return

    run_kwargs["masks"] = [mask]


def _visible_bounds_mask(wsi: Path, visible_bounds: Any | None) -> np.ndarray | None:
    bounds = _parse_visible_bounds(visible_bounds)
    if bounds is None:
        return None

    from tiatoolbox.wsicore.wsireader import WSIReader

    reader = WSIReader.open(wsi)
    slide_w, slide_h = (
        int(value)
        for value in reader.slide_dimensions(resolution=1.0, units="baseline")
    )
    if slide_w <= 0 or slide_h <= 0:
        return None

    x, y, width, height = bounds
    end_x = x + width
    end_y = y + height
    if x <= 0 and y <= 0 and end_x >= slide_w and end_y >= slide_h:
        return None

    downsample = max(
        float(_VISIBLE_BOUNDS_MASK_BASELINE_BIN),
        slide_w / float(_VISIBLE_BOUNDS_MASK_MAX_DIMENSION),
        slide_h / float(_VISIBLE_BOUNDS_MASK_MAX_DIMENSION),
    )
    mask_w = max(1, int(math.ceil(slide_w / downsample)))
    mask_h = max(1, int(math.ceil(slide_h / downsample)))

    mask_x0 = _scale_bound_floor(x, slide_w, mask_w)
    mask_y0 = _scale_bound_floor(y, slide_h, mask_h)
    mask_x1 = _scale_bound_ceil(end_x, slide_w, mask_w)
    mask_y1 = _scale_bound_ceil(end_y, slide_h, mask_h)
    if mask_x1 <= mask_x0 or mask_y1 <= mask_y0:
        logger.warning(
            "Skipping empty QuPath visible-bounds mask for %s: bounds=%s slide=(%d, %d)",
            wsi,
            bounds,
            slide_w,
            slide_h,
        )
        return None

    mask = np.zeros((mask_h, mask_w), dtype=np.uint8)
    mask[mask_y0:mask_y1, mask_x0:mask_x1] = 1
    logger.info(
        "Using QuPath visible-bounds mask for %s: bounds=(%.0f, %.0f, %.0f, %.0f), slide=(%d, %d), mask=(%d, %d)",
        wsi.name,
        x,
        y,
        width,
        height,
        slide_w,
        slide_h,
        mask_w,
        mask_h,
    )
    return mask


def _parse_visible_bounds(value: Any | None) -> tuple[float, float, float, float] | None:
    if not isinstance(value, dict):
        return None
    try:
        x = float(value["x"])
        y = float(value["y"])
        width = float(value["width"])
        height = float(value["height"])
    except (KeyError, TypeError, ValueError):
        logger.warning("Ignoring invalid QuPath visible bounds: %r", value)
        return None
    if width <= 0 or height <= 0:
        return None
    return x, y, width, height


def _scale_bound_floor(value: float, source_size: int, target_size: int) -> int:
    scaled = math.floor(value * target_size / source_size)
    return min(target_size, max(0, scaled))


def _scale_bound_ceil(value: float, source_size: int, target_size: int) -> int:
    scaled = math.ceil(value * target_size / source_size)
    return min(target_size, max(0, scaled))


def _qupath_semantic_postproc(
    image: Any,
    *,
    min_confidence: float = 0.5,
    min_object_area: int = _SEMANTIC_MIN_OBJECT_AREA,
    component_area_threshold: int = _SEMANTIC_COMPONENT_AREA_THRESHOLD,
    kernel_diameter: int = _SEMANTIC_MORPH_KERNEL_DIAMETER,
) -> Any:
    image = np.asarray(image)
    if image.ndim == 2:
        labels = image.astype(np.uint8, copy=False)
        probabilities = None
    elif image.ndim < 2:
        return image.astype(np.uint8, copy=False)
    elif image.shape[-1] == 1:
        probabilities = image[..., 0]
        labels = (probabilities >= min_confidence).astype(np.uint8)
    else:
        probabilities = image
        labels = image.argmax(axis=-1).astype(np.uint8, copy=False)
        labels[image.max(axis=-1) < min_confidence] = 0
    return _clean_semantic_label_map(
        labels,
        probabilities=probabilities,
        min_object_area=min_object_area,
        component_area_threshold=component_area_threshold,
        kernel_diameter=kernel_diameter,
    )


def _clean_semantic_label_map(
    labels: np.ndarray,
    *,
    probabilities: np.ndarray | None = None,
    min_object_area: int = _SEMANTIC_MIN_OBJECT_AREA,
    component_area_threshold: int = _SEMANTIC_COMPONENT_AREA_THRESHOLD,
    kernel_diameter: int = _SEMANTIC_MORPH_KERNEL_DIAMETER,
) -> np.ndarray:
    labels = np.asarray(labels)
    if labels.size == 0:
        return labels.astype(np.uint8, copy=False)

    class_ids = [int(value) for value in np.unique(labels) if int(value) > 0]
    if not class_ids:
        return labels.astype(np.uint8, copy=False)

    try:
        import cv2
    except ImportError as exc:  # pragma: no cover - tiatoolbox normally provides cv2
        logger.warning("OpenCV unavailable; skipping semantic morphology: %s", exc)
        return labels.astype(np.uint8, copy=False)

    kernel = _semantic_morph_kernel(cv2, kernel_diameter)
    cleaned = np.zeros(labels.shape, dtype=np.uint8)
    cleaned_scores = np.full(labels.shape, -np.inf, dtype=np.float32)

    for class_id in class_ids:
        mask = (labels == class_id).astype(np.uint8)
        mask = _remove_small_components(mask, max(0, int(min_object_area)), cv2)
        if kernel is not None:
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
        mask = _remove_small_components(
            mask,
            max(0, int(component_area_threshold)),
            cv2,
        )
        if not np.any(mask):
            continue

        score = _class_score_map(labels, probabilities, class_id)
        update = (mask > 0) & (score > cleaned_scores)
        cleaned[update] = class_id
        cleaned_scores[update] = score[update]
    return cleaned


def _semantic_morph_kernel(cv2: Any, kernel_diameter: int) -> Any:
    kernel_diameter = int(kernel_diameter)
    if kernel_diameter <= 1:
        return None
    if kernel_diameter % 2 == 0:
        kernel_diameter += 1
    return cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE,
        (kernel_diameter, kernel_diameter),
    )


def _remove_small_components(mask: np.ndarray, min_area: int, cv2: Any) -> np.ndarray:
    if min_area <= 1 or not np.any(mask):
        return mask.astype(np.uint8, copy=False)
    num_labels, component_map, stats, _ = cv2.connectedComponentsWithStats(
        mask.astype(np.uint8, copy=False),
        connectivity=8,
    )
    if num_labels <= 1:
        return mask.astype(np.uint8, copy=False)
    keep = np.zeros(num_labels, dtype=bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= min_area
    return keep[component_map].astype(np.uint8, copy=False)


def _class_score_map(
    labels: np.ndarray,
    probabilities: np.ndarray | None,
    class_id: int,
) -> np.ndarray:
    if probabilities is not None:
        probabilities = np.asarray(probabilities)
        if probabilities.ndim == 3 and class_id < probabilities.shape[-1]:
            return probabilities[..., class_id].astype(np.float32, copy=False)
        if probabilities.ndim == 2 and class_id == 1:
            return probabilities.astype(np.float32, copy=False)
    return (labels == class_id).astype(np.float32)


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
        props = feat.get("properties") or {}
        cls = props.get("classification")
        if not isinstance(cls, dict):
            continue
        name = cls.get("name")
        idx = _class_index(name)
        if idx is None:
            idx = _class_index(props.get("class_value"))
        if idx is None:
            idx = _class_index(feat.get("class_value"))
        if idx is not None and 0 <= idx < len(classes):
            cls["name"] = classes[idx]
            feat["name"] = classes[idx]
            # Keep the embedded RGB so QuPath's GeoJSON parser remains
            # happy; the Java importer overrides the colour with QuPath's
            # built-in PathClass palette where one exists.
            relabeled += 1
        elif isinstance(name, str) and name in classes:
            feat["name"] = name

    if relabeled:
        with open(path, "w") as fh:
            json.dump(data, fh)
        logger.info("Re-labelled %d/%d features in %s", relabeled, len(features), path)


def _sanitize_geojson_for_qupath(path: Path, min_area: float = 4.0) -> None:
    """Repair/drop polygon features that QuPath's GeoJSON reader rejects.

    Semantic segmentation masks can contour into self-intersecting rings or
    zero-area slivers. QuPath reduces geometry precision while importing and a
    single bad feature can make ``PathIO.readObjects`` reject the whole file, so
    repair valid polygonal parts and discard tiny fragments before Java sees it.
    """
    try:
        from shapely.geometry import GeometryCollection, MultiPolygon, Polygon
        from shapely.geometry import mapping, shape
        try:
            from shapely import make_valid
        except ImportError:  # pragma: no cover - older Shapely fallback
            try:
                from shapely.validation import make_valid
            except ImportError:  # pragma: no cover
                make_valid = None
    except ImportError as exc:
        logger.warning("Could not sanitize %s; Shapely is unavailable: %s", path, exc)
        return

    try:
        with open(path, "r") as fh:
            data = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Could not sanitize %s: %s", path, exc)
        return

    features = data.get("features") or []
    sanitized: list[dict[str, Any]] = []
    repaired = 0
    dropped = 0
    split = 0

    for feat in features:
        try:
            geom = shape(feat.get("geometry"))
            if geom.is_empty or geom.area < min_area:
                dropped += 1
                continue
            if not geom.is_valid:
                repaired += 1
                geom = make_valid(geom) if make_valid is not None else geom.buffer(0)

            parts = []
            for poly in _polygon_parts(geom, Polygon, MultiPolygon, GeometryCollection):
                if poly.is_empty or poly.area < min_area:
                    continue
                if not poly.is_valid:
                    poly = poly.buffer(0)
                if poly.is_empty or poly.area < min_area or not poly.is_valid:
                    continue
                parts.append(poly)

            if not parts:
                dropped += 1
                continue
            out_geom = parts[0] if len(parts) == 1 else MultiPolygon(parts)
            if len(parts) > 1:
                split += 1
            out = dict(feat)
            out["geometry"] = mapping(out_geom)
            sanitized.append(out)
        except (TypeError, ValueError, KeyError):
            dropped += 1

    if len(sanitized) == len(features) and repaired == 0 and dropped == 0 and split == 0:
        return

    data["features"] = sanitized
    try:
        with open(path, "w") as fh:
            json.dump(data, fh, separators=(",", ":"))
    except OSError as exc:
        logger.warning("Could not write sanitized GeoJSON %s: %s", path, exc)
        return

    logger.info(
        "Sanitized %s for QuPath: kept=%d/%d repaired=%d dropped=%d multipart=%d",
        path,
        len(sanitized),
        len(features),
        repaired,
        dropped,
        split,
    )


def _polygon_parts(geom: Any, polygon_type: Any, multipolygon_type: Any, collection_type: Any) -> list[Any]:
    if geom.is_empty:
        return []
    if isinstance(geom, polygon_type):
        return [geom]
    if isinstance(geom, multipolygon_type):
        return list(geom.geoms)
    if isinstance(geom, collection_type):
        parts: list[Any] = []
        for child in geom.geoms:
            parts.extend(_polygon_parts(child, polygon_type, multipolygon_type, collection_type))
        return parts
    return []


def _class_index(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        index = int(value)
        return index if float(value) == float(index) else None
    if isinstance(value, str):
        text = value.strip()
        if text:
            try:
                return int(text)
            except ValueError:
                return None
    return None


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
