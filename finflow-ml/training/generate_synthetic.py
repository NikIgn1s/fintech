import numpy as np
from numpy.typing import NDArray

from app.features.fraud_features import compute_features

FRAUD_PATTERNS = ("new_account_large", "burst_to_new_recipients", "night_spike", "mule")


def generate(
    rows: int, fraud_rate: float, seed: int
) -> tuple[NDArray[np.float64], NDArray[np.int64]]:
    rng = np.random.default_rng(seed)

    avg_30d = rng.lognormal(mean=7.2, sigma=0.7, size=rows)
    amount = avg_30d * rng.lognormal(mean=0.0, sigma=0.45, size=rows)
    account_age = rng.exponential(scale=240.0, size=rows).round()
    transfers_24h = rng.poisson(lam=1.6, size=rows).astype(np.float64)
    transfers_1h = np.minimum(transfers_24h, rng.poisson(lam=0.25, size=rows)).astype(np.float64)
    amount_24h = transfers_24h * avg_30d * rng.uniform(0.6, 1.2, size=rows)
    first_to_recipient = (rng.random(rows) < 0.18).astype(np.float64)
    hour = rng.choice(24, size=rows, p=_hour_distribution()).astype(np.float64)
    recipient_age = rng.exponential(scale=200.0, size=rows).round()
    recipient_incoming = rng.poisson(lam=1.2, size=rows).astype(np.float64)

    labels = (rng.random(rows) < fraud_rate).astype(np.int64)
    fraud_idx = np.flatnonzero(labels)
    pattern = rng.integers(0, len(FRAUD_PATTERNS), size=fraud_idx.size)

    for index, kind in zip(fraud_idx, pattern, strict=True):
        if kind == 0:
            account_age[index] = rng.integers(0, 4)
            amount[index] = rng.uniform(40000, 150000)
            first_to_recipient[index] = 1.0
        elif kind == 1:
            transfers_1h[index] = rng.integers(6, 15)
            transfers_24h[index] = transfers_1h[index] + rng.integers(0, 6)
            first_to_recipient[index] = 1.0
            amount_24h[index] = amount[index] * transfers_24h[index]
        elif kind == 2:
            hour[index] = rng.integers(0, 6)
            amount[index] = avg_30d[index] * rng.uniform(8, 20)
            first_to_recipient[index] = 1.0
        else:
            recipient_incoming[index] = rng.integers(12, 40)
            recipient_age[index] = rng.integers(0, 3)
            amount[index] = rng.uniform(15000, 90000)

    features = compute_features(
        amount,
        avg_30d,
        account_age,
        transfers_1h,
        transfers_24h,
        amount_24h,
        first_to_recipient,
        hour,
        recipient_age,
        recipient_incoming,
    )
    return features, labels


def _hour_distribution() -> NDArray[np.float64]:
    weights = np.array(
        [1, 1, 1, 1, 1, 2, 4, 6, 8, 9, 9, 9, 9, 9, 9, 9, 9, 9, 8, 7, 5, 3, 2, 1], dtype=np.float64
    )
    distribution: NDArray[np.float64] = weights / weights.sum()
    return distribution
