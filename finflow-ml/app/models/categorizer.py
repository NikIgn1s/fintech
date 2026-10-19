import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np

MODEL_FILE = "categorizer_model.joblib"
METADATA_FILE = "categorizer_metadata.json"
TOP_K = 3


def compose_text(description: str, mcc: str | None) -> str:
    text = description.strip()
    return f"{text} MCC{mcc}" if mcc else text


@dataclass(frozen=True)
class Prediction:
    category: str
    confidence: float
    top3: list[tuple[str, float]]


class CategorizerModel:
    def __init__(self, pipeline: Any, version: str) -> None:
        self._pipeline = pipeline
        self.version = version

    @property
    def categories(self) -> list[str]:
        return [str(name) for name in self._pipeline.classes_]

    def predict(self, texts: list[str]) -> list[Prediction]:
        probabilities = self._pipeline.predict_proba(texts)
        classes = self.categories
        predictions: list[Prediction] = []
        for row in probabilities:
            order = np.argsort(row)[::-1][:TOP_K]
            top = [(classes[i], round(float(row[i]), 4)) for i in order]
            predictions.append(Prediction(top[0][0], top[0][1], top))
        return predictions

    def save(self, directory: Path, metadata: dict[str, Any]) -> None:
        directory.mkdir(parents=True, exist_ok=True)
        joblib.dump({"pipeline": self._pipeline, "version": self.version}, directory / MODEL_FILE)
        (directory / METADATA_FILE).write_text(
            json.dumps({**metadata, "version": self.version}, indent=2, sort_keys=True),
            encoding="utf-8",
        )

    @classmethod
    def load(cls, directory: Path) -> "CategorizerModel":
        path = directory / MODEL_FILE
        if not path.exists():
            raise FileNotFoundError(f"Categorizer model is missing: {path}")
        stored = joblib.load(path)
        return cls(stored["pipeline"], stored["version"])
