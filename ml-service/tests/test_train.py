from __future__ import annotations

import pytest

from app.model import (
    train_ensemble,
    predict_ensemble,
    profitable_budget_range,
)



@pytest.fixture(scope="module")
def trained(records) -> object:
    return train_ensemble(records, "v1", ensemble_size=3)


class TestTrainEnsemble:
    def test_holdout_is_split_by_campaigns_not_by_records(self, trained):
        meta = trained.meta
        # 20 кампаний × 2 отсечки; в holdout уходит max(5, 20 % кампаний) = 5 кампаний
        assert meta["holdout_size"] == 10
        assert meta["ensemble_size"] == 3
        assert len(meta["platforms"]) == 3

    def test_metrics_are_computed_separately_for_plan_and_history(self, trained):
        meta = trained.meta
        assert 0 <= meta["mape"] < 100
        assert meta["mape_planned"] is not None
        assert meta["mape_with_history"] is not None
        assert meta["interval_calibration_planned"] > 0
        assert meta["interval_calibration_history"] > 0
        assert meta["interval_calibration_planned"] != meta["interval_calibration_history"]

    def test_meta_carries_data_bounds_and_channels(self, trained):
        meta = trained.meta
        assert meta["budget_p05"] > 0
        assert meta["budget_p95"] > meta["budget_p05"]
        assert meta["day_budget_p05"] > 0
        assert meta["day_budget_p95"] >= meta["day_budget_p05"]
        assert set(meta["platforms"]) == {"VK Реклама", "Email-платформа", "Google Ads"}
        assert len(meta["features"]) == 13

    def test_calibration_quantile_matches_requested_coverage(self, trained):
        # калибровка интервала подобрана так, чтобы 80 % ошибок укладывались в интервал
        assert trained.meta["interval_calibration"] > 0
        assert trained.meta["interval_absolute_floor"] > 0


class TestPredictEnsemble:
    def test_prediction_contains_point_and_interval(self, trained):
        record = {
            "platform": "VK Реклама",
            "budget": 20000.0,
            "duration_days": 60,
            "start_month": 11,
            "cutoff_days": 0,
            "cost_to_date": 0.0,
            "revenue_to_date": 0.0,
            "cost_last7": 0.0,
            "revenue_last7": 0.0,
        }

        prediction = predict_ensemble(trained, record)

        assert prediction["interval_lower"] <= prediction["payback_ratio"] <= prediction["interval_upper"]
        assert prediction["interval_lower"] >= 0.0
        assert 0.0 <= prediction["agreement"] <= 1.0
        assert prediction["member_spread"] >= 0.0

    def test_interval_uses_separate_calibration_for_plan_and_history(self, trained):
        planned = {
            "platform": "VK Реклама", "budget": 20000.0, "duration_days": 60, "start_month": 11,
            "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
            "cost_last7": 0.0, "revenue_last7": 0.0,
        }
        with_history = {
            "platform": "VK Реклама", "budget": 20000.0, "duration_days": 60, "start_month": 11,
            "cutoff_days": 30, "cost_to_date": 10000.0, "revenue_to_date": 18000.0,
            "cost_last7": 2000.0, "revenue_last7": 3600.0,
        }

        plan_interval = predict_ensemble(trained, planned)
        history_interval = predict_ensemble(trained, with_history)

        plan_width = plan_interval["interval_upper"] - plan_interval["interval_lower"]
        history_width = history_interval["interval_upper"] - history_interval["interval_lower"]
        # интервал для плана строится на менее надёжных данных и не уже, чем с историей
        assert plan_width >= history_width * 0.5

    def test_old_model_without_calibration_keys_falls_back_to_defaults(self, trained):
        trained.meta.pop("interval_calibration_planned", None)
        trained.meta.pop("interval_floor_planned", None)
        record = {
            "platform": "VK Реклама", "budget": 20000.0, "duration_days": 60, "start_month": 11,
            "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
            "cost_last7": 0.0, "revenue_last7": 0.0,
        }

        prediction = predict_ensemble(trained, record)

        assert prediction["payback_ratio"] > 0
        trained.meta["interval_calibration_planned"] = 2.0
        trained.meta["interval_floor_planned"] = 0.1


class TestProfitableBudgetRange:
    def test_always_profitable_target_reports_any_budget(self, profitable_records):
        ensemble = train_ensemble(profitable_records, "vAny", ensemble_size=2)
        record = {
            "platform": "VK Реклама", "budget": 20000.0, "duration_days": 60, "start_month": 11,
            "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
            "cost_last7": 0.0, "revenue_last7": 0.0,
        }

        lower, upper, any_budget = profitable_budget_range(ensemble, record)

        assert (lower, upper, any_budget) == (None, None, True)

    def test_never_profitable_target_reports_no_window(self, unprofitable_records):
        ensemble = train_ensemble(unprofitable_records, "vNone", ensemble_size=2)
        record = {
            "platform": "VK Реклама", "budget": 20000.0, "duration_days": 60, "start_month": 11,
            "cutoff_days": 0, "cost_to_date": 0.0, "revenue_to_date": 0.0,
            "cost_last7": 0.0, "revenue_last7": 0.0,
        }

        lower, upper, any_budget = profitable_budget_range(ensemble, record)

        assert (lower, upper, any_budget) == (None, None, False)
