import argparse
import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.pipeline import Pipeline

from app.config import get_settings
from app.models.categorizer import CategorizerModel
from training.generate_merchants import generate

DEFAULT_SEED = 42
HOLDOUT_SHARE = 0.2


def build_pipeline() -> Pipeline:
    return Pipeline(
        [
            (
                "tfidf",
                TfidfVectorizer(
                    analyzer="char_wb", ngram_range=(2, 5), lowercase=True, sublinear_tf=True
                ),
            ),
            ("classifier", LogisticRegression(max_iter=2000, C=12.0)),
        ]
    )


def _accuracy(expected: list[str], predicted: Any, mask: Any) -> float:
    selected = np.flatnonzero(mask)
    return float(np.mean([expected[i] == predicted[i] for i in selected]))


def train(seed: int) -> tuple[CategorizerModel, dict[str, Any]]:
    texts, labels, merchants = generate(seed)
    rng = np.random.default_rng(seed)
    unique = sorted(set(merchants))
    held_out = set(rng.choice(unique, size=int(len(unique) * HOLDOUT_SHARE), replace=False))
    is_test = np.array([merchant in held_out for merchant in merchants])
    train_idx = np.flatnonzero(~is_test)
    test_idx = np.flatnonzero(is_test)

    evaluation = build_pipeline()
    evaluation.fit([texts[i] for i in train_idx], [labels[i] for i in train_idx])
    predicted = evaluation.predict([texts[i] for i in test_idx])
    expected = [labels[i] for i in test_idx]
    with_mcc = np.array(["MCC" in texts[i] for i in test_idx])

    final = build_pipeline()
    final.fit(texts, labels)
    version = "tfidf-lr-" + datetime.now(UTC).strftime("%Y-%m-%d")
    metadata: dict[str, Any] = {
        "algorithm": "TfidfVectorizer(char_wb 2-5) + LogisticRegression",
        "trained_at": datetime.now(UTC).isoformat(),
        "rows": len(texts),
        "merchants": len(unique),
        "held_out_merchants": sorted(held_out),
        "categories": [str(name) for name in final.classes_],
        "dataset_hash": hashlib.sha256("\n".join(texts).encode()).hexdigest(),
        "metrics": {
            "accuracy_on_unseen_merchants": float(accuracy_score(expected, predicted)),
            "macro_f1_on_unseen_merchants": float(f1_score(expected, predicted, average="macro")),
            "accuracy_unseen_with_mcc": _accuracy(expected, predicted, with_mcc),
            "accuracy_unseen_without_mcc": _accuracy(expected, predicted, ~with_mcc),
        },
    }
    return CategorizerModel(final, version), metadata


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the baseline transaction categorizer")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--output", type=Path, default=get_settings().model_dir)
    args = parser.parse_args()

    model, metadata = train(args.seed)
    model.save(args.output, metadata)
    print(json.dumps(metadata["metrics"], indent=2))


if __name__ == "__main__":
    main()
