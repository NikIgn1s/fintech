import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from numpy.typing import NDArray

MODEL_FILE = "fraud_model.joblib"
METADATA_FILE = "fraud_metadata.json"

HOLD_REFERENCE_QUANTILE = 0.98
HOLD_REFERENCE_SCORE = 0.7
EXTREME_QUANTILE = 0.999
EXTREME_SCORE = 0.95


@dataclass(frozen=True)
class Calibration:
    center: float
    scale: float

    @classmethod
    def from_anomaly_scores(cls, scores: NDArray[np.float64]) -> "Calibration":
        reference = float(np.quantile(scores, HOLD_REFERENCE_QUANTILE))
        extreme = float(np.quantile(scores, EXTREME_QUANTILE))
        hold_logit = math.log(HOLD_REFERENCE_SCORE / (1 - HOLD_REFERENCE_SCORE))
        extreme_logit = math.log(EXTREME_SCORE / (1 - EXTREME_SCORE))
        scale = max(extreme - reference, 1e-6) / (extreme_logit - hold_logit)
        return cls(center=reference - hold_logit * scale, scale=scale)

    def apply(self, anomaly: float) -> float:
        exponent = -(anomaly - self.center) / self.scale
        if exponent > 50:
            return 0.0
        return 1.0 / (1.0 + math.exp(exponent))

    def apply_batch(self, anomalies: NDArray[np.float64]) -> NDArray[np.float64]:
        exponent = np.clip(-(anomalies - self.center) / self.scale, -50.0, 50.0)
        result: NDArray[np.float64] = 1.0 / (1.0 + np.exp(exponent))
        return result


class FraudModel:
    def __init__(self, estimator: Any, calibration: Calibration, version: str) -> None:
        self._estimator = estimator
        self._calibration = calibration
        self.version = version

    def score(self, features: NDArray[np.float64]) -> float:
        anomaly = float(-self._estimator.score_samples(features)[0])
        return min(1.0, max(0.0, self._calibration.apply(anomaly)))

    def score_batch(self, features: NDArray[np.float64]) -> NDArray[np.float64]:
        anomalies: NDArray[np.float64] = -self._estimator.score_samples(features)
        return np.clip(self._calibration.apply_batch(anomalies), 0.0, 1.0)

    def save(self, directory: Path, metadata: dict[str, Any]) -> None:
        directory.mkdir(parents=True, exist_ok=True)
        joblib.dump(
            {
                "estimator": self._estimator,
                "center": self._calibration.center,
                "scale": self._calibration.scale,
                "version": self.version,
            },
            directory / MODEL_FILE,
        )
        (directory / METADATA_FILE).write_text(
            json.dumps({**metadata, "version": self.version}, indent=2, sort_keys=True),
            encoding="utf-8",
        )

    @classmethod
    def load(cls, directory: Path) -> "FraudModel":
        path = directory / MODEL_FILE
        if not path.exists():
            raise FileNotFoundError(f"Fraud model is missing: {path}")
        stored = joblib.load(path)
        return cls(
            stored["estimator"], Calibration(stored["center"], stored["scale"]), stored["version"]
        )
