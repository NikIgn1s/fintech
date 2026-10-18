import json
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from app.events import Envelope, TransferCheckRequested
from app.features.fraud_features import FEATURE_NAMES, build_features, explain
from app.models.fraud import FraudModel
from app.services.fraud_scoring import FraudScoringService, InvalidEventError
from training.train_fraud import train

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def load(name: str) -> dict[str, Any]:
    return json.loads(name_path(name).read_text(encoding="utf-8"))  # type: ignore[no-any-return]


def name_path(name: str) -> Path:
    return CONTRACTS / name


@pytest.fixture(scope="session")
def trained() -> tuple[FraudModel, dict[str, Any]]:
    return train(rows=12000, fraud_rate=0.02, seed=7)


def request_event(**overrides: Any) -> dict[str, Any]:
    event = load("examples/transfer-check-requested.json")
    event["payload"].update(overrides)
    return event


def normal_payload() -> dict[str, Any]:
    return {
        "amount": "1800.0000",
        "avgTransferAmount30d": "1700.0000",
        "fromAccountAgeDays": 300,
        "isFirstTransferToRecipient": False,
        "transfersLast1h": 0,
        "transfersLast24h": 1,
        "amountLast24h": "1700.0000",
        "hourOfDay": 13,
        "recipientAccountAgeDays": 250,
        "recipientIncomingCount24h": 1,
    }


def suspicious_payload() -> dict[str, Any]:
    return {
        "amount": "120000.0000",
        "avgTransferAmount30d": "2000.0000",
        "fromAccountAgeDays": 1,
        "isFirstTransferToRecipient": True,
        "transfersLast1h": 8,
        "transfersLast24h": 9,
        "amountLast24h": "500000.0000",
        "hourOfDay": 3,
        "recipientAccountAgeDays": 0,
        "recipientIncomingCount24h": 25,
    }


def encoded(overrides: dict[str, Any]) -> bytes:
    return json.dumps(request_event(**overrides)).encode()


def payload_of(raw: dict[str, Any]) -> TransferCheckRequested:
    return TransferCheckRequested.model_validate(Envelope.model_validate(raw).payload)


def test_features_follow_the_documented_order() -> None:
    features = build_features(payload_of(request_event()))

    assert features.shape == (1, len(FEATURE_NAMES))
    row = dict(zip(FEATURE_NAMES, features[0], strict=True))
    assert row["amount"] == 15000.0
    assert row["amount_to_avg_30d"] == pytest.approx(15000 / 2100)
    assert row["is_first_to_recipient"] == 1.0
    assert row["is_night"] == 1.0


def test_new_users_without_history_get_neutral_ratio() -> None:
    features = build_features(payload_of(request_event(avgTransferAmount30d="0.0000")))

    assert features[0][FEATURE_NAMES.index("amount_to_avg_30d")] == 1.0


def test_trained_model_ranks_fraud_above_normal_traffic(
    trained: tuple[FraudModel, dict[str, Any]],
) -> None:
    model, metadata = trained
    normal = model.score(build_features(payload_of(request_event(**normal_payload()))))
    fraud = model.score(build_features(payload_of(request_event(**suspicious_payload()))))

    assert 0.0 <= normal < 0.7 <= fraud <= 1.0
    assert metadata["metrics"]["pr_auc"] > 0.2
    assert metadata["metrics"]["roc_auc"] > 0.8


def test_model_survives_save_and_load(
    trained: tuple[FraudModel, dict[str, Any]], tmp_path: Path
) -> None:
    model, metadata = trained
    model.save(tmp_path, metadata)
    reloaded = FraudModel.load(tmp_path)
    features = build_features(payload_of(request_event(**suspicious_payload())))

    assert reloaded.version == model.version
    assert reloaded.score(features) == pytest.approx(model.score(features))
    stored = json.loads((tmp_path / "fraud_metadata.json").read_text(encoding="utf-8"))
    assert stored["version"] == model.version
    assert len(stored["dataset_hash"]) == 64


def test_result_matches_the_contract_and_carries_reasons_for_hold(
    trained: tuple[FraudModel, dict[str, Any]],
) -> None:
    service = FraudScoringService(trained[0], hold_threshold=0.7)
    validator = Draft202012Validator(
        load("fraud-check-completed.schema.json"),
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )

    held = json.loads(service.handle(encoded(suspicious_payload())).value)
    approved = json.loads(service.handle(encoded(normal_payload())).value)

    assert list(validator.iter_errors(held)) == []
    assert list(validator.iter_errors(approved)) == []
    assert held["payload"]["decision"] == "HOLD"
    assert 1 <= len(held["payload"]["reasons"]) <= 3
    assert approved["payload"]["decision"] == "APPROVE"
    assert "reasons" not in approved["payload"]
    assert held["payload"]["modelVersion"] == trained[0].version


def test_outcome_keeps_the_transfer_id_as_message_key(
    trained: tuple[FraudModel, dict[str, Any]],
) -> None:
    service = FraudScoringService(trained[0], hold_threshold=0.7)
    event = request_event()

    outcome = service.handle(json.dumps(event).encode())

    assert outcome.key == event["payload"]["transferId"].encode()
    assert json.loads(outcome.value)["payload"]["transferId"] == event["payload"]["transferId"]


def test_threshold_decides_between_approve_and_hold(
    trained: tuple[FraudModel, dict[str, Any]],
) -> None:
    raw = json.dumps(request_event(**normal_payload())).encode()

    assert FraudScoringService(trained[0], hold_threshold=0.0).handle(raw).decision == "HOLD"
    assert FraudScoringService(trained[0], hold_threshold=1.01).handle(raw).decision == "APPROVE"


@pytest.mark.parametrize(
    "raw",
    [
        b"not json",
        b"{}",
        json.dumps({**request_event(), "eventType": "SomethingElse"}).encode(),
        json.dumps(
            {**request_event(), "payload": {"transferId": "0192f1b2-7c39-7f0e-8a44-0c9d2b6e1f22"}}
        ).encode(),
        json.dumps(request_event(amount="lots")).encode(),
    ],
)
def test_invalid_events_are_rejected(
    trained: tuple[FraudModel, dict[str, Any]], raw: bytes
) -> None:
    service = FraudScoringService(trained[0], hold_threshold=0.7)

    with pytest.raises(InvalidEventError):
        service.handle(raw)


def test_reasons_point_to_the_risky_features() -> None:
    reasons = explain(payload_of(request_event(**suspicious_payload())))

    assert len(reasons) == 3
    assert "amount far above average" in reasons
    calm = explain(payload_of(request_event(**normal_payload())))
    assert calm == ["unusual combination of features"]
