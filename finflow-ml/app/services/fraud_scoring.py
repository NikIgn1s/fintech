import json
import secrets
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

from pydantic import ValidationError

from app.events import (
    CHECK_COMPLETED,
    CHECK_REQUESTED,
    EVENT_VERSION,
    Envelope,
    TransferCheckRequested,
)
from app.features.fraud_features import build_features, explain
from app.metrics import FRAUD_DECISIONS, FRAUD_SCORE, INFERENCE_LATENCY, INVALID_EVENTS
from app.models.fraud import FraudModel


class InvalidEventError(Exception):
    pass


@dataclass(frozen=True)
class Outcome:
    key: bytes
    value: bytes
    score: float
    decision: str


def uuid7() -> uuid.UUID:
    millis = int(time.time() * 1000)
    value = (millis & ((1 << 48) - 1)) << 80
    value |= 0x7 << 76
    value |= secrets.randbits(12) << 64
    value |= 0b10 << 62
    value |= secrets.randbits(62)
    return uuid.UUID(int=value)


class FraudScoringService:
    def __init__(self, model: FraudModel, hold_threshold: float) -> None:
        self._model = model
        self._hold_threshold = hold_threshold

    @property
    def model_version(self) -> str:
        return self._model.version

    def handle(self, raw: bytes) -> Outcome:
        event = self._parse(raw)
        started = time.perf_counter()
        score = self._model.score(build_features(event))
        INFERENCE_LATENCY.observe(time.perf_counter() - started)
        decision = "HOLD" if score >= self._hold_threshold else "APPROVE"
        FRAUD_SCORE.observe(score)
        FRAUD_DECISIONS.labels(decision).inc()

        payload: dict[str, object] = {
            "transferId": str(event.transferId),
            "score": round(score, 4),
            "decision": decision,
            "modelVersion": self._model.version,
        }
        if decision == "HOLD":
            payload["reasons"] = explain(event)
        message = {
            "eventId": str(uuid7()),
            "eventType": CHECK_COMPLETED,
            "version": EVENT_VERSION,
            "occurredAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "payload": payload,
        }
        return Outcome(
            key=str(event.transferId).encode(),
            value=json.dumps(message).encode(),
            score=score,
            decision=decision,
        )

    def _parse(self, raw: bytes) -> TransferCheckRequested:
        try:
            envelope = Envelope.model_validate_json(raw)
            if envelope.eventType != CHECK_REQUESTED:
                raise ValueError(f"Unexpected event type {envelope.eventType}")
            return TransferCheckRequested.model_validate(envelope.payload)
        except (ValidationError, ValueError) as error:
            INVALID_EVENTS.inc()
            raise InvalidEventError(str(error)) from error
