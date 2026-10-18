import numpy as np
from numpy.typing import NDArray

from app.events import TransferCheckRequested

FEATURE_NAMES = [
    "amount",
    "log_amount",
    "amount_to_avg_30d",
    "account_age_days",
    "transfers_last_1h",
    "transfers_last_24h",
    "amount_last_24h",
    "is_first_to_recipient",
    "hour_of_day",
    "is_night",
    "recipient_account_age_days",
    "recipient_incoming_count_24h",
]

NIGHT_END_HOUR = 6


def compute_features(
    amount: NDArray[np.float64],
    avg_30d: NDArray[np.float64],
    account_age_days: NDArray[np.float64],
    transfers_1h: NDArray[np.float64],
    transfers_24h: NDArray[np.float64],
    amount_24h: NDArray[np.float64],
    first_to_recipient: NDArray[np.float64],
    hour: NDArray[np.float64],
    recipient_age_days: NDArray[np.float64],
    recipient_incoming_24h: NDArray[np.float64],
) -> NDArray[np.float64]:
    ratio = np.where(avg_30d > 0, amount / np.where(avg_30d > 0, avg_30d, 1.0), 1.0)
    is_night = (hour < NIGHT_END_HOUR).astype(np.float64)
    return np.column_stack(
        [
            amount,
            np.log1p(amount),
            ratio,
            account_age_days,
            transfers_1h,
            transfers_24h,
            amount_24h,
            first_to_recipient,
            hour,
            is_night,
            recipient_age_days,
            recipient_incoming_24h,
        ]
    )


def build_features(event: TransferCheckRequested) -> NDArray[np.float64]:
    def column(value: float) -> NDArray[np.float64]:
        return np.array([value], dtype=np.float64)

    return compute_features(
        column(float(event.amount)),
        column(float(event.avgTransferAmount30d)),
        column(event.fromAccountAgeDays),
        column(event.transfersLast1h),
        column(event.transfersLast24h),
        column(float(event.amountLast24h)),
        column(1.0 if event.isFirstTransferToRecipient else 0.0),
        column(event.hourOfDay),
        column(event.recipientAccountAgeDays),
        column(event.recipientIncomingCount24h),
    )


def explain(event: TransferCheckRequested, limit: int = 3) -> list[str]:
    avg = float(event.avgTransferAmount30d)
    candidates = [
        (avg > 0 and float(event.amount) > 5 * avg, "amount far above average"),
        (
            event.fromAccountAgeDays < 7 and float(event.amount) >= 10000,
            "large amount from a new account",
        ),
        (event.isFirstTransferToRecipient, "new recipient"),
        (event.hourOfDay < NIGHT_END_HOUR, "night transfer"),
        (event.transfersLast1h >= 5, "many transfers in the last hour"),
        (event.recipientIncomingCount24h >= 10, "recipient receives many transfers"),
        (event.recipientAccountAgeDays < 3, "recipient account is very new"),
    ]
    reasons = [text for matched, text in candidates if matched][:limit]
    return reasons or ["unusual combination of features"]
