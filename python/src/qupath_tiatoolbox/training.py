"""Training workflow used by the QuPath extension."""

from __future__ import annotations

import json
import logging
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Subset

from tiatoolbox.annotation import Annotation, SQLiteStore
from tiatoolbox.models.architecture.efficientunet_tissue_mask_model import (
    EfficientUNetTissueMaskModel,
)
from tiatoolbox.models.architecture.vanilla import CNNModel, TimmModel
from tiatoolbox.models.engine.io_config import IOPatchPredictorConfig, IOSegmentorConfig
from tiatoolbox.models.training import (
    CheckpointConfig,
    ClassificationTask,
    CoverageClassTargetBuilder,
    MaskTargetBuilder,
    SegmentationTask,
    SlideAnnotationPatchDataset,
    Trainer,
    TrainerConfig,
    TrainingArtifactManifest,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class _SlideSpec:
    name: str
    wsi_path: Path
    geojson_path: Path
    split: str


_ANNOTATION_STORE_SUFFIXES = {".db", ".sqlite", ".sqlite3"}


class _QuPathSlideAnnotationPatchDataset(SlideAnnotationPatchDataset):
    @staticmethod
    def _normalize_input_masks(input_masks: Any, num_slides: int) -> list[Any]:
        """Keep annotation-store masks as strings for TIAToolbox patch extraction."""
        if isinstance(input_masks, list):
            if len(input_masks) != num_slides:
                msg = (
                    "When `input_masks` is a list it must have the same length "
                    "as `slide_inputs`."
                )
                raise ValueError(msg)
            values = input_masks
        else:
            values = [input_masks for _ in range(num_slides)]

        normalized = []
        for mask in values:
            if (
                isinstance(mask, (str, Path))
                and Path(mask).suffix.lower() in _ANNOTATION_STORE_SUFFIXES
            ):
                normalized.append(str(mask))
            else:
                normalized.append(mask)
        return normalized


class TrainingCancelled(RuntimeError):
    """Raised when the Java side requests cancellation."""


class _CancellableTrainer(Trainer):
    def __init__(self, *args: Any, cancel_event: Any = None, listener: Any = None, **kwargs: Any):
        super().__init__(*args, **kwargs)
        self.cancel_event = cancel_event
        self.listener = listener

    def _check_cancelled(self) -> None:
        if self.cancel_event is not None and self.cancel_event.is_set():
            raise TrainingCancelled("Training cancelled.")

    def _run_epoch(self, loader: DataLoader, *, training: bool) -> dict[str, float]:
        self.model.train(mode=training)
        mode_name = "train" if training else "val"

        metric_totals: dict[str, float] = {"loss": 0.0}
        total_samples = 0

        if training:
            self.optimizer.zero_grad(set_to_none=True)

        self.task.reset_epoch_state(training=training)

        for step_index, batch in enumerate(loader, start=1):
            self._check_cancelled()
            images, targets = self._extract_batch(batch)
            images = images.to(self.device).float()
            targets = self._move_to_device(targets)

            with torch.set_grad_enabled(training):
                with torch.amp.autocast(device_type=self.device.type, enabled=self.use_amp):
                    output = self.model(images)
                    loss = self.task.compute_loss(output, targets)

            batch_size = int(images.shape[0])
            total_samples += batch_size

            detached_output = self._detach(output)
            detached_targets = self._detach(targets)
            batch_metrics = self.task.compute_metrics(detached_output, detached_targets)
            self.task.update_epoch_state(detached_output, detached_targets)
            metric_totals["loss"] += float(loss.item()) * batch_size
            for metric_name, metric_value in batch_metrics.items():
                metric_totals[metric_name] = (
                    metric_totals.get(metric_name, 0.0) + metric_value * batch_size
                )

            if training:
                scaled_loss = loss / self.config.grad_accum_steps
                if self.use_amp:
                    self.grad_scaler.scale(scaled_loss).backward()
                else:
                    scaled_loss.backward()

                last_step = step_index == len(loader)
                should_step = (step_index % self.config.grad_accum_steps == 0) or last_step
                if should_step:
                    self._optimizer_step()

            if (
                self.config.log_every_n_steps > 0
                and step_index % self.config.log_every_n_steps == 0
            ):
                _safe_status(
                    self.listener,
                    f"{mode_name} step {step_index}/{len(loader)}: loss={float(loss.item()):.4f}",
                )

        if total_samples == 0:
            raise ValueError(f"`{mode_name}` dataloader yielded zero samples.")

        epoch_metrics = {
            metric_name: metric_total / total_samples
            for metric_name, metric_total in metric_totals.items()
        }
        epoch_metrics.update(self.task.compute_epoch_metrics())
        return epoch_metrics


def run_training(
    request: dict[str, Any],
    *,
    listener: Any = None,
    cancel_event: Any = None,
) -> dict[str, Any]:
    """Run a QuPath project training workflow."""
    _safe_status(listener, "Preparing training data...")

    task_type = str(request.get("task_type") or "patch_classification")
    if task_type not in {"patch_classification", "semantic_segmentation"}:
        raise ValueError(f"Unsupported training task: {task_type}")
    is_segmentation = task_type == "semantic_segmentation"

    output_dir = Path(request["output_dir"])
    output_dir.mkdir(parents=True, exist_ok=True)
    classes = list(request["classes"])
    if not is_segmentation and len(classes) < 2:
        raise ValueError("Patch classification training requires at least two classes.")
    if is_segmentation and not classes:
        raise ValueError("Semantic segmentation training requires at least one class.")

    class_mapping = _class_mapping(request, classes, first_index=1 if is_segmentation else 0)
    slides = _parse_slides(request.get("slides", []))
    train_slides = [slide for slide in slides if slide.split == "train"]
    val_slides = [slide for slide in slides if slide.split == "val"]
    if not train_slides:
        raise ValueError("Training request contains no training slides.")

    stores_dir = output_dir / "annotation-stores"
    stores_dir.mkdir(parents=True, exist_ok=True)
    train_stores = _build_stores(train_slides, stores_dir, listener)
    val_stores = _build_stores(val_slides, stores_dir, listener) if val_slides else []

    options = dict(request.get("options") or {})
    model_spec = dict(request.get("model") or {})
    patch_size = int(options.get("patch_size", 224))
    stride = int(options.get("stride", patch_size))
    mpp = float(options.get("mpp", 0.5))
    if mpp <= 0:
        raise ValueError("Training resolution `mpp` must be positive.")
    seed = int(options.get("seed", 1))
    min_mask_ratio = float(options.get("min_mask_ratio", 0.01))

    target_builder = _target_builder(task_type, class_mapping, options)

    train_dataset = _build_dataset(
        slides=train_slides,
        stores=train_stores,
        target_builder=target_builder,
        patch_size=patch_size,
        stride=stride,
        mpp=mpp,
        min_mask_ratio=min_mask_ratio,
    )
    val_dataset = (
        _build_dataset(
            slides=val_slides,
            stores=val_stores,
            target_builder=target_builder,
            patch_size=patch_size,
            stride=stride,
            mpp=mpp,
            min_mask_ratio=min_mask_ratio,
        )
        if val_slides
        else None
    )

    max_per_class_slide = int(options.get("max_patches_per_class_slide", 250))
    _safe_status(listener, f"Sampling training patches from {len(train_dataset)} candidates...")
    subsetter = _capped_segmentation_subset if is_segmentation else _capped_subset
    train_dataset = subsetter(
        train_dataset,
        max_per_class_slide=max_per_class_slide,
        seed=seed,
        listener=listener,
        cancel_event=cancel_event,
    )
    if val_dataset is not None:
        _safe_status(listener, f"Sampling validation patches from {len(val_dataset)} candidates...")
        val_dataset = subsetter(
            val_dataset,
            max_per_class_slide=max(1, max_per_class_slide // 4),
            seed=seed + 1,
            listener=listener,
            cancel_event=cancel_event,
        )

    _safe_status(listener, "Building model...")
    class_dict = _class_dict(task_type, classes, class_mapping)
    num_model_classes = max(int(index) for index in class_dict) + 1
    model = build_model(model_spec, num_classes=num_model_classes, class_dict=class_dict)
    optimizer = torch.optim.Adam(model.parameters(), lr=float(options.get("learning_rate", 1e-4)))
    task = SegmentationTask(ignore_index=-100) if is_segmentation else ClassificationTask(ignore_index=-100)

    batch_size = int(options.get("batch_size", 8))
    num_workers = int(options.get("num_workers", 4))
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True, num_workers=num_workers)
    val_loader = (
        DataLoader(val_dataset, batch_size=batch_size, shuffle=False, num_workers=num_workers)
        if val_dataset is not None
        else None
    )

    artifact = _build_artifact(
        task_type=task_type,
        model=model,
        model_spec=model_spec,
        class_dict=class_dict,
        classes=classes,
        patch_size=patch_size,
        stride=stride,
        mpp=mpp,
    )

    monitor = "val_loss" if val_loader is not None else "train_loss"
    trainer = _CancellableTrainer(
        model=model,
        task=task,
        optimizer=optimizer,
        train_loader=train_loader,
        val_loader=val_loader,
        config=TrainerConfig(
            max_epochs=int(options.get("epochs", 1)),
            device=str(options.get("device", "auto")),
            amp="auto",
            seed=seed,
            monitor=monitor,
            monitor_mode="min",
            output_dir=output_dir,
            log_every_n_steps=int(options.get("log_every_n_steps", 20)),
        ),
        checkpoint_config=CheckpointConfig(),
        artifact_manifest=artifact,
        cancel_event=cancel_event,
        listener=listener,
    )

    _safe_status(listener, "Training...")
    history = trainer.fit()
    artifact_path = output_dir / "training_artifact.json"
    if not artifact_path.exists():
        artifact.save(artifact_path)

    _safe_status(listener, "Training complete.")
    return {
        "artifact": str(artifact_path),
        "output_dir": str(output_dir),
        "history": history,
        "train_samples": len(train_dataset),
        "val_samples": 0 if val_dataset is None else len(val_dataset),
    }


def build_model(
    model_spec: dict[str, Any],
    *,
    num_classes: int,
    class_dict: dict[int, str] | None = None,
) -> nn.Module:
    """Construct a supported QuPath training model."""
    model_type = str(model_spec.get("model_type", "CNNModel"))
    backbone = str(model_spec.get("backbone", "resnet18"))
    if model_type == "CNNModel":
        return CNNModel(backbone=backbone, num_classes=num_classes)
    if model_type == "TimmModel":
        return TimmModel(
            backbone=backbone,
            num_classes=num_classes,
            pretrained=bool(model_spec.get("pretrained", False)),
        )
    if model_type == "EfficientUNetTissueMaskModel":
        return EfficientUNetTissueMaskModel(
            num_classes=num_classes,
            class_dict=class_dict,
        )
    raise ValueError(f"Unsupported model_type: {model_type}")


def _class_mapping(
    request: dict[str, Any],
    classes: list[str],
    *,
    first_index: int,
) -> dict[str, int]:
    payload = dict(request.get("class_mapping") or {})
    if payload:
        return {str(name): int(index) for name, index in payload.items()}
    return {name: index + first_index for index, name in enumerate(classes)}


def _class_dict(
    task_type: str,
    classes: list[str],
    class_mapping: dict[str, int],
) -> dict[int, str]:
    labels = {int(index): str(name) for name, index in class_mapping.items()}
    if task_type == "semantic_segmentation":
        labels.setdefault(0, "Background")
    if labels:
        return dict(sorted(labels.items()))
    return {index: name for index, name in enumerate(classes)}


def _target_builder(
    task_type: str,
    class_mapping: dict[str, int],
    options: dict[str, Any],
) -> Any:
    if task_type == "semantic_segmentation":
        return MaskTargetBuilder(
            class_mapping=class_mapping,
            class_property="class",
            default_label=0,
        )
    return CoverageClassTargetBuilder(
        class_mapping=class_mapping,
        class_property="class",
        default_label=-100,
        min_fraction=float(options.get("min_fraction", 0.0)),
    )


def _build_artifact(
    *,
    task_type: str,
    model: nn.Module,
    model_spec: dict[str, Any],
    class_dict: dict[int, str],
    classes: list[str],
    patch_size: int,
    stride: int,
    mpp: float,
) -> TrainingArtifactManifest:
    model_type = str(model_spec.get("model_type", "CNNModel"))
    backbone = str(model_spec.get("backbone", "resnet18"))
    constructor = {
        "model_type": model_type,
        "backbone": backbone,
        "num_classes": max(class_dict) + 1,
        "pretrained": bool(model_spec.get("pretrained", False)),
    }
    metadata = {
        "qupath_training": True,
        "classes": classes,
        "patch_size": patch_size,
        "stride": stride,
        "mpp": mpp,
        "units": "mpp",
    }
    description = f"{model_type} {backbone}"

    if task_type == "semantic_segmentation":
        ioconfig = IOSegmentorConfig(
            input_resolutions=[{"units": "mpp", "resolution": mpp}],
            output_resolutions=[{"units": "mpp", "resolution": mpp}],
            patch_input_shape=[patch_size, patch_size],
            patch_output_shape=[patch_size, patch_size],
            stride_shape=[stride, stride],
            save_resolution={"units": "mpp", "resolution": mpp},
            ignore_index=0,
        )
        return TrainingArtifactManifest.from_model(
            model,
            task_type="semantic_segmentation",
            model_constructor=constructor,
            model_description=description,
            class_dict=class_dict,
            ioconfig=ioconfig,
            engine="SemanticSegmentor",
            metadata={
                **metadata,
                "semantic_background_label": 0,
                "semantic_min_confidence": 0.5,
            },
        )

    ioconfig = IOPatchPredictorConfig(
        input_resolutions=[{"units": "mpp", "resolution": mpp}],
        output_resolutions=[{"units": "mpp", "resolution": mpp}],
        patch_input_shape=[patch_size, patch_size],
        stride_shape=[stride, stride],
    )
    return TrainingArtifactManifest.from_model(
        model,
        task_type="classification",
        model_constructor=constructor,
        model_description=description,
        class_dict=class_dict,
        ioconfig=ioconfig,
        engine="PatchPredictor",
        run_kwargs={"return_probabilities": True},
        metadata=metadata,
    )


def _parse_slides(payload: list[dict[str, Any]]) -> list[_SlideSpec]:
    slides = [
        _SlideSpec(
            name=str(item["name"]),
            wsi_path=Path(item["wsi_path"]),
            geojson_path=Path(item["geojson_path"]),
            split=str(item["split"]),
        )
        for item in payload
    ]
    if not slides:
        raise ValueError("Training request contains no slides.")
    return slides


def _safe_store_stem(slide: _SlideSpec, index: int) -> str:
    stem = "".join(
        character if character.isalnum() or character in {"-", "_", "."} else "_"
        for character in slide.name
    ).strip("._")
    if not stem:
        stem = "slide"
    return f"{slide.split}-{index:04d}-{stem}"


def _build_stores(slides: list[_SlideSpec], stores_dir: Path, listener: Any) -> list[Path]:
    stores_dir.mkdir(parents=True, exist_ok=True)
    stores: list[Path] = []
    for index, slide in enumerate(slides):
        _safe_status(listener, f"Converting annotations: {slide.name}")
        store_path = stores_dir / f"{_safe_store_stem(slide, index)}.db"
        if store_path.exists():
            store_path.unlink()
        store = SQLiteStore(store_path)
        try:
            store.add_from_geojson(slide.geojson_path, transform=_qupath_transform)
            store.commit()
        finally:
            store.close()
        stores.append(store_path)
    return stores


def _build_dataset(
    *,
    slides: list[_SlideSpec],
    stores: list[Path],
    target_builder: Any,
    patch_size: int,
    stride: int,
    mpp: float,
    min_mask_ratio: float,
) -> SlideAnnotationPatchDataset:
    return _QuPathSlideAnnotationPatchDataset(
        slide_inputs=[slide.wsi_path for slide in slides],
        annotation_stores=stores,
        target_builder=target_builder,
        patch_size=patch_size,
        stride=stride,
        resolution=mpp,
        units="mpp",
        within_bound=True,
        input_masks=stores,
        min_mask_ratio=min_mask_ratio,
    )


def _capped_subset(
    dataset: SlideAnnotationPatchDataset,
    *,
    max_per_class_slide: int,
    seed: int,
    listener: Any,
    cancel_event: Any,
) -> Subset:
    if max_per_class_slide <= 0:
        raise ValueError("`max_patches_per_class_slide` must be positive.")

    output_shape = _target_output_shape(dataset)
    candidate_indices_by_slide: dict[int, list[int]] = {}
    for candidate_index, slide_index in enumerate(dataset.sample_slide_indices.tolist()):
        candidate_indices_by_slide.setdefault(int(slide_index), []).append(candidate_index)

    rng = random.Random(seed)
    groups: dict[tuple[int, int], list[int]] = {}
    ignored = 0
    evaluated = 0
    for slide_number, (slide_index, candidate_indices) in enumerate(
        sorted(candidate_indices_by_slide.items()),
        start=1,
    ):
        if cancel_event is not None and cancel_event.is_set():
            raise TrainingCancelled("Training cancelled.")

        store = dataset._get_store(slide_index)
        slide_labels = _store_labels(store, dataset.target_builder)
        selected_counts = {label: 0 for label in slide_labels}
        rng.shuffle(candidate_indices)
        _safe_status(
            listener,
            (
                f"Sampling slide {slide_number}/{len(candidate_indices_by_slide)} "
                f"from {len(candidate_indices)} candidates..."
            ),
        )

        for index in candidate_indices:
            if cancel_event is not None and cancel_event.is_set():
                raise TrainingCancelled("Training cancelled.")
            if selected_counts and all(
                count >= max_per_class_slide for count in selected_counts.values()
            ):
                break

            label = _candidate_label(dataset, index, output_shape)
            evaluated += 1
            if label < 0:
                ignored += 1
                continue

            key = (slide_index, label)
            if len(groups.get(key, [])) >= max_per_class_slide:
                continue
            groups.setdefault(key, []).append(index)
            selected_counts[label] = selected_counts.get(label, 0) + 1

    selected: list[int] = []
    for indices in groups.values():
        selected.extend(indices[:max_per_class_slide])
    selected.sort()
    if not selected:
        raise ValueError("No labelled patches were available after sampling.")
    _safe_status(
        listener,
        (
            f"Selected {len(selected)} patches after evaluating "
            f"{evaluated}/{len(dataset)} candidates ({ignored} ignored)."
        ),
    )
    return Subset(dataset, selected)


def _capped_segmentation_subset(
    dataset: SlideAnnotationPatchDataset,
    *,
    max_per_class_slide: int,
    seed: int,
    listener: Any,
    cancel_event: Any,
) -> Subset:
    if max_per_class_slide <= 0:
        raise ValueError("`max_patches_per_class_slide` must be positive.")

    output_shape = _target_output_shape(dataset)
    candidate_indices_by_slide: dict[int, list[int]] = {}
    for candidate_index, slide_index in enumerate(dataset.sample_slide_indices.tolist()):
        candidate_indices_by_slide.setdefault(int(slide_index), []).append(candidate_index)

    rng = random.Random(seed)
    selected: set[int] = set()
    ignored = 0
    evaluated = 0
    for slide_number, (slide_index, candidate_indices) in enumerate(
        sorted(candidate_indices_by_slide.items()),
        start=1,
    ):
        if cancel_event is not None and cancel_event.is_set():
            raise TrainingCancelled("Training cancelled.")

        store = dataset._get_store(slide_index)
        slide_labels = {label for label in _store_labels(store, dataset.target_builder) if label > 0}
        selected_counts = {label: 0 for label in slide_labels}
        rng.shuffle(candidate_indices)
        _safe_status(
            listener,
            (
                f"Sampling slide {slide_number}/{len(candidate_indices_by_slide)} "
                f"from {len(candidate_indices)} candidates..."
            ),
        )

        for index in candidate_indices:
            if cancel_event is not None and cancel_event.is_set():
                raise TrainingCancelled("Training cancelled.")
            if selected_counts and all(
                count >= max_per_class_slide for count in selected_counts.values()
            ):
                break

            labels = _candidate_segmentation_labels(dataset, index, output_shape)
            evaluated += 1
            labels = {label for label in labels if label in selected_counts}
            if not labels:
                ignored += 1
                continue

            useful_labels = [
                label
                for label in labels
                if selected_counts.get(label, 0) < max_per_class_slide
            ]
            if not useful_labels:
                continue

            selected.add(index)
            for label in useful_labels:
                selected_counts[label] = selected_counts.get(label, 0) + 1

    if not selected:
        raise ValueError("No labelled segmentation patches were available after sampling.")
    selected_indices = sorted(selected)
    _safe_status(
        listener,
        (
            f"Selected {len(selected_indices)} patches after evaluating "
            f"{evaluated}/{len(dataset)} candidates ({ignored} ignored)."
        ),
    )
    return Subset(dataset, selected_indices)


def _target_output_shape(dataset: SlideAnnotationPatchDataset) -> tuple[int, int]:
    patch_size = dataset.patch_size
    if isinstance(patch_size, (tuple, list)):
        width, height = patch_size
        return int(height), int(width)
    size = int(patch_size)
    return size, size


def _store_labels(
    store: SQLiteStore,
    target_builder: Any,
) -> set[int]:
    class_mapping = dict(getattr(target_builder, "class_mapping", {}) or {})
    class_property = str(getattr(target_builder, "class_property", "class"))
    try:
        values = store.pquery(lambda props: props.get(class_property))
    except Exception:  # noqa: BLE001
        logger.debug("Could not query slide labels from annotation store.", exc_info=True)
        values = set(class_mapping)

    if not isinstance(values, set):
        values = set(values)

    labels = {
        int(class_mapping[value])
        for value in values
        if value in class_mapping
    }
    if labels:
        return labels
    return {int(label) for label in class_mapping.values()}


def _candidate_label(
    dataset: SlideAnnotationPatchDataset,
    index: int,
    output_shape: tuple[int, int],
) -> int:
    slide_index = int(dataset.sample_slide_indices[index])
    bounds_at_resolution = tuple(
        int(value) for value in dataset.sample_bounds[index].tolist()
    )
    reader = dataset._get_reader(slide_index)
    bounds_at_baseline = tuple(
        float(value)
        for value in reader.bounds_at_resolution_to_baseline(
            bounds_at_resolution,
            dataset.resolution,
            dataset.units,
        )
    )
    target = dataset.target_builder.create_target(
        store=dataset._get_store(slide_index),
        patch_bounds=bounds_at_baseline,
        output_shape=output_shape,
    )
    if isinstance(target, torch.Tensor):
        return int(target.item())
    if isinstance(target, np.ndarray):
        return int(np.asarray(target).item())
    return int(target)


def _candidate_segmentation_labels(
    dataset: SlideAnnotationPatchDataset,
    index: int,
    output_shape: tuple[int, int],
) -> set[int]:
    slide_index = int(dataset.sample_slide_indices[index])
    bounds_at_resolution = tuple(
        int(value) for value in dataset.sample_bounds[index].tolist()
    )
    reader = dataset._get_reader(slide_index)
    bounds_at_baseline = tuple(
        float(value)
        for value in reader.bounds_at_resolution_to_baseline(
            bounds_at_resolution,
            dataset.resolution,
            dataset.units,
        )
    )
    target = dataset.target_builder.create_target(
        store=dataset._get_store(slide_index),
        patch_bounds=bounds_at_baseline,
        output_shape=output_shape,
    )
    if isinstance(target, torch.Tensor):
        values = target.detach().cpu().numpy()
    else:
        values = np.asarray(target)
    return {int(value) for value in np.unique(values) if int(value) > 0}


def _qupath_transform(annotation: Annotation) -> Annotation:
    props = annotation.properties
    classification = props.get("classification")
    if isinstance(classification, dict):
        name = classification.get("name")
        if name is not None:
            props["class"] = str(name)
    if "measurements" in props:
        measurements = props.pop("measurements")
        if isinstance(measurements, dict):
            props.update(measurements)
        elif isinstance(measurements, list):
            for measurement in measurements:
                if isinstance(measurement, dict) and "name" in measurement and "value" in measurement:
                    props[str(measurement["name"])] = measurement["value"]
    if "objectType" in props:
        props["type"] = props.pop("objectType")
    return annotation


def _safe_status(listener: Any, message: str) -> None:
    logger.info(message)
    if listener is None:
        return
    try:
        listener.onStatus(message)
    except Exception:  # noqa: BLE001
        logger.debug("Listener status callback failed", exc_info=True)


def response_json(result: dict[str, Any]) -> str:
    return json.dumps({"status": "ok", **result})
