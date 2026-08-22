"""Unit tests for the pure logic in ``qupath_tiatoolbox.bridge``.

Covers the error-payload shape, request validation, and the listener
dispatch helper. The happy path of ``runInference`` is not tested here as it
needs a real tiatoolbox engine.
"""

import json
import threading

from qupath_tiatoolbox import bridge
from qupath_tiatoolbox.bridge import TIATask


class TestErr:
    def test_error_payload_without_trace(self):
        payload = json.loads(bridge._err("boom"))
        assert payload == {"status": "error", "message": "boom"}

    def test_error_payload_with_trace(self):
        payload = json.loads(bridge._err("boom", trace="stack"))
        assert payload["status"] == "error"
        assert payload["trace"] == "stack"


class TestTaskState:
    def test_ping(self):
        task = TIATask(threading.Event())
        assert task.ping() == "pong"

    def test_shutdown_sets_event(self):
        event = threading.Event()
        TIATask(event).shutdown()
        assert event.is_set()

    def test_cancel_training_sets_flag(self):
        task = TIATask(threading.Event())
        task.cancelTraining()
        assert task._training_cancel.is_set()


class TestRunInferenceValidation:
    def test_invalid_json_returns_error(self):
        task = TIATask(threading.Event())
        payload = json.loads(task.runInference("{not json", listener=None))
        assert payload["status"] == "error"
        assert "invalid request JSON" in payload["message"]

    def test_missing_required_field_returns_error(self):
        task = TIATask(threading.Event())
        payload = json.loads(task.runInference('{"engine": "patch_predictor"}', listener=None))
        assert payload["status"] == "error"
        assert "missing required field" in payload["message"]


class TestSafeCall:
    def test_none_listener_is_noop(self):
        bridge._safe_call(None, "onStatus", "hi")  # must not raise

    def test_dispatches_to_listener(self):
        class Recorder:
            def __init__(self):
                self.calls = []

            def onStatus(self, message):
                self.calls.append(message)

        rec = Recorder()
        bridge._safe_call(rec, "onStatus", "hi")
        assert rec.calls == ["hi"]

    def test_swallows_listener_errors(self):
        class Boom:
            def onStatus(self, message):
                raise RuntimeError("listener down")

        bridge._safe_call(Boom(), "onStatus", "hi")  # must not raise

    def test_missing_method_is_swallowed(self):
        bridge._safe_call(object(), "onStatus", "hi")  # must not raise
