import argparse
import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score, roc_auc_score

from app.config import get_settings
from app.features.fraud_features import FEATURE_NAMES
from app.models.fraud import Calibration, FraudModel
from training.generate_synthetic import generate

DEFAULT_ROWS = 60000
DEFAULT_FRAUD_RATE = 0.015
DEFAULT_SEED = 42


def train(rows: int, fraud_rate: float, seed: int) -> tuple[FraudModel, dict[str, Any]]:
    features, labels = generate(rows, fraud_rate, seed)
    estimator = IsolationForest(
        n_estimators=200, max_samples=512, contamination="auto", random_state=seed, n_jobs=-1
    )
    estimator.fit(features)

    anomaly = -estimator.score_samples(features)
    calibration = Calibration.from_anomaly_scores(anomaly)
    model = FraudModel(estimator, calibration, "iforest-" + datetime.now(UTC).strftime("%Y-%m-%d"))
    scores = model.score_batch(features[:20000])
    evaluated = labels[:20000]
    metadata = {
        "algorithm": "IsolationForest",
        "trained_at": datetime.now(UTC).isoformat(),
        "features": FEATURE_NAMES,
        "rows": rows,
        "fraud_rate": fraud_rate,
        "seed": seed,
        "dataset_hash": hashlib.sha256(features.tobytes()).hexdigest(),
        "metrics": {
            "pr_auc": float(average_precision_score(evaluated, scores)),
            "roc_auc": float(roc_auc_score(evaluated, scores)),
            "share_above_0_7": float(np.mean(scores >= 0.7)),
            "recall_at_0_7": float(np.mean(scores[evaluated == 1] >= 0.7)),
        },
    }
    return model, metadata


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the baseline fraud model")
    parser.add_argument("--rows", type=int, default=DEFAULT_ROWS)
    parser.add_argument("--fraud-rate", type=float, default=DEFAULT_FRAUD_RATE)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--output", type=Path, default=get_settings().model_dir)
    args = parser.parse_args()

    model, metadata = train(args.rows, args.fraud_rate, args.seed)
    model.save(args.output, metadata)
    print(json.dumps(metadata["metrics"], indent=2))


if __name__ == "__main__":
    main()
