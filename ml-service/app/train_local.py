from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from .model import train_ensemble
from .store import ModelStore


def main() -> None:
    parser = argparse.ArgumentParser(description="Локальное обучение ансамбля AdForecast")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--models-dir", type=Path, default=Path(__file__).resolve().parent.parent / "models")
    args = parser.parse_args()

    frame = pd.read_csv(args.dataset)
    records = frame.to_dict("records")
    store = ModelStore(args.models_dir)
    ensemble = train_ensemble(records, store.next_version())
    store.save(ensemble)
    print(json.dumps(ensemble.meta, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
