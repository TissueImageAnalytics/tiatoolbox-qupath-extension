"""Py4J entry-point object exposed to QuPath.

The QuPath side connects to a ``ClientServer`` running here and calls
``run_inference(...)`` synchronously. While inference runs, we periodically
invoke the Java-side listener (passed as a parameter) so the UI can stay
responsive.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import traceback
from typing import Any

from . import runners

logger = logging.getLogger(__name__)


class TIATask:
    """Implements ``qupath.ext.tiatoolbox.core.TiaRunner`` over Py4J."""

    class Java:
        implements = ["qupath.ext.tiatoolbox.core.TiaRunner"]

    def __init__(self, shutdown_event: threading.Event):
        self._shutdown = shutdown_event
        self._training_cancel = threading.Event()

    def ping(self) -> str:
        """Quick reachability check used by the Java side after spawn."""
        return "pong"

    def runInference(self, request_json: str, listener: Any) -> str:  # noqa: N802
        """Run one engine on one WSI. Returns a JSON response string.

        ``listener`` is a Java object implementing
        ``qupath.ext.tiatoolbox.core.ProgressListener``. We call ``onStatus``
        once at start and once at end, and ``onHeartbeat`` periodically while
        the engine is running.
        """
        try:
            req = json.loads(request_json)
        except json.JSONDecodeError as exc:
            return _err(f"invalid request JSON: {exc}")

        engine = req.get("engine")
        model = req.get("model")
        wsi_path = req.get("wsi_path")
        save_dir = req.get("save_dir")
        if not all([engine, model, wsi_path, save_dir]):
            return _err("request missing required field (engine/model/wsi_path/save_dir)")

        _safe_call(listener, "onStatus", f"Loading {engine}/{model}…")

        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(
            target=_heartbeat_loop,
            args=(listener, stop_heartbeat),
            daemon=True,
        )
        heartbeat.start()

        try:
            result = runners.run_engine(
                engine=engine,
                model=model,
                wsi_path=wsi_path,
                save_dir=save_dir,
                device=req.get("device", "cpu"),
                batch_size=int(req.get("batch_size", 8)),
                num_workers=int(req.get("num_workers", 0)),
                classes=req.get("classes"),
                artifact_path=req.get("artifact_path"),
            )
        except Exception as exc:  # noqa: BLE001 — cross-process boundary
            logger.exception("Inference failed")
            return _err(f"{type(exc).__name__}: {exc}", trace=traceback.format_exc())
        finally:
            stop_heartbeat.set()

        _safe_call(listener, "onStatus", "Done.")
        return json.dumps({"status": "ok", **result})

    def runTraining(self, request_json: str, listener: Any) -> str:  # noqa: N802
        """Run one QuPath project training request."""
        try:
            req = json.loads(request_json)
        except json.JSONDecodeError as exc:
            return _err(f"invalid request JSON: {exc}")

        self._training_cancel.clear()
        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(
            target=_heartbeat_loop,
            args=(listener, stop_heartbeat),
            daemon=True,
        )
        heartbeat.start()
        try:
            from . import training

            result = training.run_training(
                req,
                listener=listener,
                cancel_event=self._training_cancel,
            )
        except Exception as exc:  # noqa: BLE001 — cross-process boundary
            logger.exception("Training failed")
            return _err(f"{type(exc).__name__}: {exc}", trace=traceback.format_exc())
        finally:
            stop_heartbeat.set()
            self._training_cancel.clear()

        return json.dumps({"status": "ok", **result})

    def cancelTraining(self) -> None:  # noqa: N802
        """Request cancellation of the active training job."""
        self._training_cancel.set()

    def shutdown(self) -> None:
        """Signal the main thread to stop the ClientServer and exit."""
        logger.info("shutdown() called by Java client")
        self._shutdown.set()


def _heartbeat_loop(listener: Any, stop: threading.Event) -> None:
    start = time.monotonic()
    while not stop.wait(2.0):
        elapsed = int(time.monotonic() - start)
        _safe_call(listener, "onHeartbeat", elapsed)


def _safe_call(listener: Any, method: str, *args: Any) -> None:
    if listener is None:
        return
    try:
        getattr(listener, method)(*args)
    except Exception:  # noqa: BLE001
        logger.debug("Listener %s call failed (ignored)", method, exc_info=True)


def _err(message: str, trace: str | None = None) -> str:
    payload: dict[str, Any] = {"status": "error", "message": message}
    if trace is not None:
        payload["trace"] = trace
    return json.dumps(payload)
