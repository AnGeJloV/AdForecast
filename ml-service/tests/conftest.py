"""Общие фикстуры и генераторы синтетических данных для тестов ml-service."""

from __future__ import annotations

import pytest

PLATFORMS = ["VK Реклама", "Email-платформа", "Google Ads"]


def make_records(campaigns: int = 20, cutoffs: int = 2, base_ratio: float = 1.2) -> list[dict]:
    """Синтетический датасет «на отсечках»: итог окупаемости зависит от платформы,
    дневного бюджета и месяца старта, чтобы модель могла чему-то научиться."""
    records = []
    for campaign in range(1, campaigns + 1):
        platform = PLATFORMS[campaign % len(PLATFORMS)]
        target = base_ratio + (campaign % 5) * 0.08
        for step in range(cutoffs):
            cutoff = 0 if step == 0 else 10 * step
            cost = 100.0 * (step + 1)
            records.append({
                "campaign_id": campaign,
                "platform": platform,
                "budget": 1000.0 + campaign * 100,
                "duration_days": 30 + (campaign % 4) * 10,
                "start_month": (campaign % 12) + 1,
                "cutoff_days": cutoff,
                "cost_to_date": cost,
                "revenue_to_date": cost * target,
                "cost_last7": 70.0,
                "revenue_last7": 70.0 * target,
                "target_ratio": target,
            })
    return records


@pytest.fixture(scope="module")
def records() -> list[dict]:
    return make_records()


@pytest.fixture(scope="session")
def profitable_records() -> list[dict]:
    return make_records(base_ratio=2.0)


@pytest.fixture(scope="session")
def unprofitable_records() -> list[dict]:
    return make_records(base_ratio=0.5)