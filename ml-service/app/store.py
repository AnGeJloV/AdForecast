from __future__ import annotations

import json
import re
from pathlib import Path

from catboost import CatBoostRegressor

from .model import Ensemble, FEATURES

DEFAULT_MODEL_NAME = "adforecast_roi"
_NAME_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class ModelStore:
    """Версионированное хранилище ансамблей, сгруппированных по семействам.

    Семейство (model_name) — независимая линия моделей: общая («adforecast_roi»)
    или модель организации («adforecast_org_<id>»). Версии нумеруются v1, v2, ...
    внутри семейства.
    """

    def __init__(self, root: Path):
        self.root = root

    def _base(self, model_name: str) -> Path:
        if not _NAME_PATTERN.match(model_name):
            raise ValueError(f"недопустимое имя модели: {model_name!r}")
        return self.root / model_name

    def next_version(self, model_name: str = DEFAULT_MODEL_NAME) -> str:
        versions = self.versions(model_name)
        if not versions:
            return "v1"
        return f"v{int(versions[-1][1:]) + 1}"

    def versions(self, model_name: str = DEFAULT_MODEL_NAME) -> list[str]:
        base = self._base(model_name)
        if not base.exists():
            return []
        versions = [path.name for path in base.iterdir() if path.is_dir() and path.name.startswith("v")]
        return sorted(versions, key=lambda version: int(version[1:]))

    def latest(self, model_name: str = DEFAULT_MODEL_NAME) -> str | None:
        versions = self.versions(model_name)
        return versions[-1] if versions else None

    def save(self, ensemble: Ensemble, model_name: str = DEFAULT_MODEL_NAME) -> Path:
        directory = self._base(model_name) / ensemble.version
        directory.mkdir(parents=True, exist_ok=True)
        for index, member in enumerate(ensemble.members):
            member.save_model(str(directory / f"member_{index}.cbm"))
        (directory / "meta.json").write_text(
            json.dumps(ensemble.meta, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return directory

    def load(self, version: str, model_name: str = DEFAULT_MODEL_NAME) -> Ensemble:
        directory = self._base(model_name) / version
        meta = json.loads((directory / "meta.json").read_text(encoding="utf-8"))
        members = [
            CatBoostRegressor().load_model(str(path))
            for path in sorted(directory.glob("member_*.cbm"), key=lambda path: int(path.stem.split("_")[1]))
        ]
        return Ensemble(version=version, members=members, meta=meta)

    def families(self) -> list[str]:
        if not self.root.exists():
            return []
        return sorted(path.name for path in self.root.iterdir() if path.is_dir() and _NAME_PATTERN.match(path.name))

    def list(self) -> list[dict]:
        metas = []
        for family in self.families():
            for version in self.versions(family):
                metas.append((family, self.load(version, family).meta))
        return [model_metrics(meta, family) for family, meta in metas]


def model_metrics(meta: dict, model_name: str = DEFAULT_MODEL_NAME) -> dict:
    return {
        "model_name": model_name,
        "version": meta["version"],
        "trained_at": meta["trained_at"],
        "mape": meta["mape"],
        "r2": meta["r2"],
        "mae": meta["mae"],
        "holdout_size": meta["holdout_size"],
        "ensemble_size": meta["ensemble_size"],
        "features": meta.get("features", FEATURES),
        "mape_with_history": meta.get("mape_with_history"),
        "r2_with_history": meta.get("r2_with_history"),
        "mape_planned": meta.get("mape_planned"),
        "r2_planned": meta.get("r2_planned"),
        "interval_calibration": meta.get("interval_calibration"),
        "dataset_snapshot": meta.get("dataset_snapshot"),
    }
