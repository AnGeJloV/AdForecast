from __future__ import annotations

import math

import pytest

from app.model import (
    FEATURES,
    MONTH_FACTORS,
    budget_out_of_range,
    day_budget_bounds,
    has_history,
    payback_days_observed,
    payback_days_planned,
    platform_unknown,
    predict_ensemble,
    seasonality_index,
    _common,
)


class TestFeatureEngineering:
    def test_common_builds_features_from_cutoff_record(self):
        record = {
            "platform": "VK Реклама",
            "budget": 6000.0,
            "duration_days": 60,
            "start_month": 11,
            "cutoff_days": 30,
            "cost_to_date": 3000.0,
            "revenue_to_date": 3900.0,
            "cost_last7": 700.0,
            "revenue_last7": 910.0,
        }

        features = _common(record)

        assert set(features) == set(FEATURES)
        assert features["platform"] == "VK Реклама"
        assert features["budget"] == 6000.0
        assert features["day_budget_planned"] == 100.0
        assert features["has_history"] == 1.0
        assert features["hist_days"] == 30.0
        assert features["hist_ratio"] == pytest.approx(1.3)
        assert features["hist_ratio_last7"] == pytest.approx(1.3)
        assert features["hist_spent_fraction"] == pytest.approx(0.5)
        assert features["hist_daily_cost"] == pytest.approx(100.0)
        assert features["hist_daily_revenue"] == pytest.approx(130.0)

    def test_common_prefers_observed_state_over_cutoff_fields(self):
        record = {
            "platform": "Google Ads",
            "budget": 10000.0,
            "duration_days": 40,
            "start_month": 3,
            "cutoff_days": 99,
            "cost_to_date": 999.0,
            "revenue_to_date": 999.0,
            "cost_last7": 999.0,
            "revenue_last7": 999.0,
            "observed": {
                "days": 8,
                "cost_to_date": 1000.0,
                "revenue_to_date": 1700.0,
                "cost_last7": 800.0,
                "revenue_last7": 1300.0,
            },
        }

        features = _common(record)

        assert features["hist_days"] == 8.0
        assert features["hist_ratio"] == pytest.approx(1.7)
        assert features["hist_ratio_last7"] == pytest.approx(1.625)
        assert features["hist_spent_fraction"] == pytest.approx(0.1)

    def test_common_survives_zero_cost_and_zero_history(self):
        record = {
            "platform": "Telegram Ads",
            "budget": 3000.0,
            "duration_days": 30,
            "start_month": 1,
            "cutoff_days": 0,
            "cost_to_date": 0.0,
            "revenue_to_date": 0.0,
            "cost_last7": 0.0,
            "revenue_last7": 0.0,
        }

        features = _common(record)

        assert features["has_history"] == 0.0
        assert features["hist_ratio"] == 0.0
        assert features["hist_spent_fraction"] == 0.0
        assert features["hist_daily_cost"] == 0.0

    def test_has_history_follows_observed_state(self):
        planned = {"cutoff_days": 0}
        active = {"cutoff_days": 10}
        from_observed = {"observed": {"days": 0, "cost_to_date": 0, "revenue_to_date": 0,
                                      "cost_last7": 0, "revenue_last7": 0}}

        assert has_history(planned) is False
        assert has_history(active) is True
        assert has_history(from_observed) is False

    def test_seasonality_index_averages_month_factors_of_campaign(self):
        two_months = (MONTH_FACTORS[10] + MONTH_FACTORS[11]) / 2
        three_months = (MONTH_FACTORS[10] + MONTH_FACTORS[11] + MONTH_FACTORS[0]) / 3

        assert seasonality_index(11, 60) == pytest.approx(two_months)
        assert seasonality_index(11, 61) == pytest.approx(three_months)
        assert seasonality_index(11, 1) == pytest.approx(MONTH_FACTORS[10])


class TestPayback:
    def test_planned_payback_within_campaign(self):
        assert payback_days_planned(2.0, 60) == pytest.approx(60.0)

    def test_planned_payback_beyond_campaign_returns_tail_formula(self):
        assert payback_days_planned(1.2, 30) == pytest.approx(30 / 1.2 + 15.0)

    def test_planned_payback_absent_for_unprofitable_ratio(self):
        assert payback_days_planned(0.9, 60) is None
        assert payback_days_planned(1.0, 60) is None

    def test_observed_payback_when_already_profitable(self):
        observed = {"days": 8, "cost_to_date": 1000.0, "revenue_to_date": 1700.0}

        assert payback_days_observed(1.5, 38, 10000.0, observed) == pytest.approx(8.0)

    def test_observed_payback_counts_remaining_flow(self):
        observed = {"days": 10, "cost_to_date": 1000.0, "revenue_to_date": 500.0}

        result = payback_days_observed(2.0, 30, 3000.0, observed)

        daily_cost = 3000.0 / 30
        total_cost = 1000.0 + daily_cost * 20
        daily_revenue = (2.0 * total_cost - 500.0) / 20
        expected = 10 + (1000.0 - 500.0) / (daily_revenue - daily_cost)
        assert result == pytest.approx(expected)

    def test_observed_payback_impossible_when_daily_revenue_below_cost(self):
        observed = {"days": 10, "cost_to_date": 1000.0, "revenue_to_date": 800.0}

        assert payback_days_observed(0.9, 30, 3000.0, observed) is None

    def test_observed_payback_absent_after_campaign_end(self):
        observed = {"days": 30, "cost_to_date": 1000.0, "revenue_to_date": 500.0}

        assert payback_days_observed(2.0, 30, 3000.0, observed) is None


class TestModelGuards:
    def make_ensemble_meta(self, **meta):
        return {"platforms": ["VK Реклама", "Google Ads"], "day_budget_p05": 100.0,
                "day_budget_p95": 1000.0, "budget_p05": 3000.0, "budget_p95": 60000.0, **meta}

    def test_platform_unknown_for_channel_outside_training_data(self):
        ensemble = type("Ensemble", (), {"meta": self.make_ensemble_meta()})()

        assert platform_unknown(ensemble, {"platform": "Telegram Ads"}) is True
        assert platform_unknown(ensemble, {"platform": "VK Реклама"}) is False

    def test_platform_unknown_disabled_without_known_channels(self):
        ensemble = type("Ensemble", (), {"meta": {"platforms": []}})()

        assert platform_unknown(ensemble, {"platform": "Авито"}) is False

    def test_budget_out_of_range_uses_day_budget_with_tolerance(self):
        ensemble = type("Ensemble", (), {"meta": self.make_ensemble_meta()})()

        assert budget_out_of_range(ensemble, {"budget": 1000.0, "duration_days": 60}) is True
        assert budget_out_of_range(ensemble, {"budget": 300000.0, "duration_days": 60}) is True
        assert budget_out_of_range(ensemble, {"budget": 30000.0, "duration_days": 60}) is False

    def test_budget_out_of_range_disabled_without_bounds(self):
        ensemble = type("Ensemble", (), {"meta": {"day_budget_p05": 0, "day_budget_p95": 0}})()

        assert budget_out_of_range(ensemble, {"budget": 10.0, "duration_days": 30}) is False

    def test_day_budget_bounds_reads_meta(self):
        ensemble = type("Ensemble", (), {"meta": self.make_ensemble_meta()})()

        assert day_budget_bounds(ensemble) == (100.0, 1000.0)

    def test_payback_months_constant_matches_documented_value(self):
        from app.model import DAYS_PER_MONTH, LAG_FRACTION

        assert math.isclose(DAYS_PER_MONTH, 30.44)
        assert LAG_FRACTION == 0.5
