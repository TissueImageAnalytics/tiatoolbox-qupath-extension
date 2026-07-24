"""Unit tests for the pure logic in ``qupath_tiatoolbox.runners``.

These cover validation, coordinate math, path collection and GeoJSON class
remapping. None of them import torch/tiatoolbox or download a model.
"""

import json

import numpy as np
import pytest

from qupath_tiatoolbox import runners


class TestClassIndex:
    @pytest.mark.parametrize(
        "value, expected",
        [
            (3, 3),
            (-1, -1),
            (3.0, 3),
            (3.5, None),
            (True, None),
            ("5", 5),
            (" 7 ", 7),
            ("x", None),
            ("", None),
            (None, None),
        ],
    )
    def test_parses_only_whole_numbers(self, value, expected):
        assert runners._class_index(value) == expected


class TestParseVisibleBounds:
    def test_valid_dict_returns_floats(self):
        bounds = {"x": 1, "y": 2, "width": 3, "height": 4}
        assert runners._parse_visible_bounds(bounds) == (1.0, 2.0, 3.0, 4.0)

    @pytest.mark.parametrize(
        "value",
        [
            None,
            [1, 2, 3, 4],
            {"x": 0, "y": 0, "width": 5},  # missing height
            {"x": 0, "y": 0, "width": 0, "height": 5},  # non-positive width
            {"x": 0, "y": 0, "width": 5, "height": 0},  # non-positive height
            {"x": "a", "y": 0, "width": 5, "height": 5},  # non-numeric
        ],
    )
    def test_invalid_returns_none(self, value):
        assert runners._parse_visible_bounds(value) is None


class TestScaleBound:
    def test_floor_and_ceil_round_as_named(self):
        assert runners._scale_bound_floor(55, 100, 10) == 5
        assert runners._scale_bound_ceil(55, 100, 10) == 6

    def test_clamped_to_target_range(self):
        assert runners._scale_bound_floor(200, 100, 10) == 10
        assert runners._scale_bound_ceil(-5, 100, 10) == 0


class TestNumClassesFromDict:
    def test_empty_is_one(self):
        assert runners._num_classes_from_dict({}) == 1

    def test_max_key_plus_one(self):
        assert runners._num_classes_from_dict({0: "a", 1: "b"}) == 2
        assert runners._num_classes_from_dict({0: "a", 3: "b"}) == 4

    def test_string_keys_are_coerced(self):
        assert runners._num_classes_from_dict({"0": "a", "2": "b"}) == 3


class TestFlattenPaths:
    def test_walks_nested_structures(self):
        from pathlib import Path

        value = {"a": "x.json", "b": ["y.geojson", ("z",)]}
        result = {p.name for p in runners._flatten_paths(value)}
        assert result == {"x.json", "y.geojson", "z"}
        assert all(isinstance(p, Path) for p in runners._flatten_paths(value))

    def test_none_yields_nothing(self):
        assert list(runners._flatten_paths(None)) == []


class TestCollectGeojsonPaths:
    def test_returns_existing_paths_from_result(self, tmp_path):
        out_dir = tmp_path / "run"
        out_dir.mkdir()
        geojson = out_dir / "0.json"
        geojson.write_text("{}")

        found = runners._collect_geojson_paths(str(geojson), out_dir)

        assert found == [geojson]

    def test_falls_back_to_timestamp_suffixed_sibling(self, tmp_path):
        out_dir = tmp_path / "run"
        out_dir.mkdir()
        sibling = tmp_path / "run_2026-01-01"  # tiatoolbox appends a timestamp
        sibling.mkdir()
        geojson = sibling / "0.geojson"
        geojson.write_text("{}")

        found = runners._collect_geojson_paths(None, out_dir)

        assert geojson in found


class TestRelabelGeojson:
    def test_numeric_names_become_labels(self, tmp_path):
        path = tmp_path / "out.geojson"
        path.write_text(
            json.dumps(
                {
                    "type": "FeatureCollection",
                    "features": [
                        {"properties": {"classification": {"name": "0"}}},
                        {"properties": {"classification": {"name": "2"}}},
                        {"properties": {"classification": {"name": "tissue"}, "class_value": 1}},
                        {"properties": {"classification": {"name": "99"}}},  # out of range
                    ],
                }
            )
        )
        classes = ["Tumor", "Stroma", "Immune cells"]

        runners._relabel_geojson_in_place(path, classes)

        features = json.loads(path.read_text())["features"]
        assert features[0]["properties"]["classification"]["name"] == "Tumor"
        assert features[1]["properties"]["classification"]["name"] == "Immune cells"
        assert features[2]["properties"]["classification"]["name"] == "Stroma"
        assert features[3]["properties"]["classification"]["name"] == "99"  # unchanged


class TestLoad:
    def test_unknown_engine_raises(self):
        with pytest.raises(ValueError, match="Unknown engine"):
            runners._load("not_an_engine")


class TestNumpyHelpers:
    def test_training_preproc_scales_integers(self):
        out = runners._qupath_training_preproc(np.array([0, 255], dtype=np.uint8))
        assert out.dtype == np.float32
        np.testing.assert_allclose(out, [0.0, 1.0])

    def test_training_preproc_passes_floats_through(self):
        out = runners._qupath_training_preproc(np.array([0.25, 0.5], dtype=np.float64))
        assert out.dtype == np.float32
        np.testing.assert_allclose(out, [0.25, 0.5])

    def test_class_score_map_without_probabilities(self):
        labels = np.array([[0, 1], [2, 1]])
        score = runners._class_score_map(labels, None, class_id=1)
        np.testing.assert_array_equal(score, [[0.0, 1.0], [0.0, 1.0]])
