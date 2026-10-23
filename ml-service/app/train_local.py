from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from .model import train_ensemble
from .store import DEFAULT_MODEL_NAME, ModelStore, model_metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Локальное обучение ансамбля AdForecast на записях отсечек")
    parser.add_argument("dataset", type=Path, help="CSV формата cutoff_records (см. db/generator)")
    parser.add_argument("--models-dir", type=Path, default=Path(__file__).resolve().parent.parent / "models")
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME)
    args = parser.parse_args()

    frame = pd.read_csv(args.dataset)
    records = frame.to_dict("records")
    store = ModelStore(args.models_dir)
    version = store.next_version(args.model_name)
    ensemble = train_ensemble(records, version)
    ensemble.meta["name"] = args.model_name
    store.save(ensemble, args.model_name)
    print(json.dumps(model_metrics(ensemble.meta, args.model_name), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
