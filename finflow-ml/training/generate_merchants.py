import numpy as np

from app.models.categorizer import compose_text
from training.merchants import CITIES, MCC_BY_CATEGORY, MERCHANTS

SAMPLES_PER_MERCHANT = 60
MCC_PRESENT_RATE = 0.5
MCC_NOISE_RATE = 0.1


def _describe(name: str, rng: np.random.Generator) -> str:
    number = int(rng.integers(1, 9999))
    city = str(rng.choice(CITIES))
    compact = name.replace(" ", "")
    templates = [
        f"{name} {number}",
        f"{name}*{city}",
        f"{name} {number} {city} RUS",
        f"{name} {city}",
        f"OOO {name}",
        f"PAYMENT {name} {number}",
        f"{compact} {number}",
        f"{name} {number}/{int(rng.integers(1, 99))}",
    ]
    return str(rng.choice(templates))


def generate(seed: int) -> tuple[list[str], list[str], list[str]]:
    rng = np.random.default_rng(seed)
    all_codes = sorted(set(MCC_BY_CATEGORY.values()))
    texts: list[str] = []
    labels: list[str] = []
    merchants: list[str] = []
    for category, names in MERCHANTS.items():
        for name in names:
            for _ in range(SAMPLES_PER_MERCHANT):
                mcc: str | None = None
                if rng.random() < MCC_PRESENT_RATE:
                    mcc = MCC_BY_CATEGORY[category]
                    if rng.random() < MCC_NOISE_RATE:
                        mcc = str(rng.choice(all_codes))
                texts.append(compose_text(_describe(name, rng), mcc))
                labels.append(category)
                merchants.append(name)
    return texts, labels, merchants
