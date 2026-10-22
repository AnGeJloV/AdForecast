from __future__ import annotations

import json
from pathlib import Path

from catboost import CatBoostRegressor

from .model import Ensemble, FEATURES


class ModelStore:
    def __init__(self, root: Path):
        self.root = root
        self.base = root / "adforecast_roi"

    def next_version(self) -> str:
        versions = self.versions()
        if not versions:
            return "v1"
        return f"v{int(versions[-1][1:]) + 1}"

    def versions(self) -> list[str]:
        if not self.base.exists():
            return []
        versions = [path.name for path in self.base.iterdir() if path.is_dir() and path.name.startswith("v")]
        return sorted(versions, key=lambda version: int(version[1:]))

    def latest(self) -> str | None:
        versions = self.versions()
        return versions[-1] if versions else None

    def save(self, ensemble: Ensemble) -> Path:
        directory = self.base / ensemble.version
        directory.mkdir(parents=True, exist_ok=True)
        for index, member in enumerate(ensemble.members):
            member.save_model(str(directory / f"member_{index}.cbm"))
        (directory / "meta.json").write_text(
            json.dumps(ensemble.meta, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return directory

    def load(self, version: str) -> Ensemble:
        directory = self.base / version
        meta = json.loads((directory / "meta.json").read_text(encoding="utf-8"))
        members = [
            CatBoostRegressor().load_model(str(path))
            for path in sorted(directory.glob("member_*.cbm"), key=lambda path: int(path.stem.split("_")[1]))
        ]
        return Ensemble(version=version, members=members, meta=meta)

    def list(self) -> list[dict]:
        return [self.load(version).meta for version in self.versions()]

    def metrics(self, version: str) -> dict:
        path = self.base / version / "meta.json"
        return json.loads(path.read_text(encoding="utf-8"))


def model_metrics(meta: dict) -> dict:
    return {
        "version": meta["version"],
        "trained_at": meta["trained_at"],
        "mape": meta["mape"],
        "r2": meta["r2"],
        "mae": meta["mae"],
        "holdout_size": meta["holdout_size"],
        "ensemble_size": meta["ensemble_size"],
        "features": FEATURES,
        "dataset_snapshot": meta.get("dataset_snapshot"),
    }
