from __future__ import annotations

import numpy as np
import pytest
from catboost import CatBoostRegressor, Pool

from app.model import Ensemble, _pool
from app.store import ModelStore, model_metrics


def tiny_ensemble(version: str = "v1") -> Ensemble:
    records = [
        {"platform": "VK Реклама", "budget": 1000.0, "duration_days": 30, "start_month": 1,
         "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
         "cost_last7": 0.0, "revenue_last7": 0.0, "target_ratio": 1.1},
        {"platform": "VK Реклама", "budget": 2000.0, "duration_days": 30, "start_month": 2,
         "cutoff_days": 15, "cost_to_date": 1000.0, "revenue_to_date": 1300.0,
         "cost_last7": 500.0, "revenue_last7": 650.0, "target_ratio": 1.3},
        {"platform": "Google Ads", "budget": 3000.0, "duration_days": 40, "start_month": 3,
         "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
         "cost_last7": 0.0, "revenue_last7": 0.0, "target_ratio": 1.5},
    ]
    pool = _pool(records, with_target=True)
    members = []
    for seed in (1, 2):
        model = CatBoostRegressor(iterations=10, depth=2, loss_function="RMSE",
                                  random_seed=seed, verbose=False)
        model.fit(pool)
        members.append(model)
    meta = {"name": "adforecast_roi", "version": version, "mape": 9.5, "r2": 0.9, "mae": 0.05,
            "holdout_size": 1, "ensemble_size": 2, "trained_at": "2026-10-27T00:00:00+00:00"}
    return Ensemble(version=version, members=members, meta=meta)


class TestVersions:
    def test_next_version_starts_at_v1(self, tmp_path):
        store = ModelStore(tmp_path)
        assert store.next_version() == "v1"

    def test_next_version_counts_existing_versions(self, tmp_path):
        store = ModelStore(tmp_path)
        store.save(tiny_ensemble("v1"))
        store.save(tiny_ensemble("v2"))
        assert store.next_version() == "v3"
        assert store.versions() == ["v1", "v2"]
        assert store.latest() == "v2"

    def test_versions_per_family_are_independent(self, tmp_path):
        store = ModelStore(tmp_path)
        store.save(tiny_ensemble("v1"), "adforecast_roi")
        store.save(tiny_ensemble("v1"), "adforecast_org_1")

        assert store.versions("adforecast_org_1") == ["v1"]
        assert store.next_version("adforecast_org_1") == "v2"
        assert store.families() == ["adforecast_org_1", "adforecast_roi"]

    def test_invalid_model_name_is_rejected(self, tmp_path):
        store = ModelStore(tmp_path)

        with pytest.raises(ValueError, match="недопустимое имя"):
            store.versions("../escape")
        with pytest.raises(ValueError, match="недопустимое имя"):
            store.versions("")

    def test_delete_removes_version_directory(self, tmp_path):
        store = ModelStore(tmp_path)
        store.save(tiny_ensemble("v1"))
        store.save(tiny_ensemble("v2"))

        store.delete("adforecast_roi", "v1")

        assert store.versions() == ["v2"]
        assert not (tmp_path / "adforecast_roi" / "v1").exists()

    def test_delete_unknown_version_is_an_error(self, tmp_path):
        store = ModelStore(tmp_path)

        with pytest.raises(ValueError, match="версия не найдена"):
            store.delete("adforecast_roi", "v9")

    def test_save_load_roundtrip_preserves_predictions(self, tmp_path):
        store = ModelStore(tmp_path)
        ensemble = tiny_ensemble("v1")
        record = {"platform": "VK Реклама", "budget": 1500.0, "duration_days": 30, "start_month": 1,
                  "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
                  "cost_last7": 0.0, "revenue_last7": 0.0}

        before = np.array([member.predict(_pool([record], with_target=False))[0]
                           for member in ensemble.members])
        store.save(ensemble)
        loaded = store.load("v1")
        after = np.array([member.predict(_pool([record], with_target=False))[0]
                          for member in loaded.members])

        assert loaded.version == "v1"
        assert loaded.meta["mape"] == 9.5
        assert np.allclose(before, after)
        assert len(store.list()) == 1

    def test_model_metrics_maps_meta_fields(self):
        meta = {"version": "v7", "trained_at": "2026-10-27T00:00:00+00:00", "mape": 8.1, "r2": 0.93,
                "mae": 0.04, "holdout_size": 200, "ensemble_size": 10,
                "mape_with_history": 5.5, "r2_with_history": 0.97,
                "mape_planned": 13.9, "r2_planned": 0.86,
                "interval_calibration": 5.05, "dataset_snapshot": {"rule": "все организации"}}

        metrics = model_metrics(meta, "adforecast_org_3")

        assert metrics["model_name"] == "adforecast_org_3"
        assert metrics["version"] == "v7"
        assert metrics["mape_with_history"] == 5.5
        assert metrics["r2_planned"] == 0.86
        assert metrics["dataset_snapshot"] == {"rule": "все организации"}

    def test_model_metrics_defaults_for_legacy_meta(self):
        metrics = model_metrics({"version": "v1", "trained_at": "t", "mape": 1, "r2": 2, "mae": 3,
                                  "holdout_size": 0, "ensemble_size": 10})

        assert metrics["model_name"] == "adforecast_roi"
        assert metrics["features"] != []
        assert metrics["mape_with_history"] is None
        assert metrics["dataset_snapshot"] is None
