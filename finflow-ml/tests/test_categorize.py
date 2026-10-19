from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app
from app.models.categorizer import CategorizerModel
from training.train_categorizer_baseline import train

API_KEY = "test-key"
HEADERS = {"X-Api-Key": API_KEY}


@pytest.fixture(scope="module")
def trained() -> tuple[CategorizerModel, dict[str, Any]]:
    return train(seed=3)


@pytest.fixture
def client(trained: tuple[CategorizerModel, dict[str, Any]]) -> Iterator[TestClient]:
    app.dependency_overrides[get_settings] = lambda: Settings(ml_api_key=API_KEY)
    app.state.categorizer = trained[0]
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
    del app.state.categorizer


def body(*descriptions: str, mcc: str | None = None) -> dict[str, Any]:
    return {
        "items": [
            {
                "id": str(index),
                "description": text,
                "amount": -540.2,
                **({"mcc": mcc} if mcc else {}),
            }
            for index, text in enumerate(descriptions)
        ]
    }


def test_model_generalizes_to_merchants_it_has_never_seen(
    trained: tuple[CategorizerModel, dict[str, Any]],
) -> None:
    metrics = trained[1]["metrics"]

    assert metrics["accuracy_on_unseen_merchants"] > 0.4
    assert metrics["accuracy_unseen_with_mcc"] > 0.7


def test_categorizes_a_batch_with_top3_and_confidence(client: TestClient) -> None:
    response = client.post(
        "/categorize",
        headers=HEADERS,
        json=body("PYATEROCHKA 1234 MOSCOW RUS", "YANDEX TAXI*SPB", "LUKOIL AZS 55 KAZAN"),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["modelVersion"].startswith("tfidf-lr-")
    by_id = {item["id"]: item for item in payload["results"]}
    assert [by_id[key]["category"] for key in ("0", "1", "2")] == ["GROCERIES", "TAXI", "FUEL"]
    first = by_id["0"]
    assert 0.0 < first["confidence"] <= 1.0
    assert len(first["top3"]) == 3
    assert first["top3"][0][0] == first["category"]
    assert first["top3"][0][1] == first["confidence"]


def test_mcc_helps_with_unknown_descriptions(client: TestClient) -> None:
    with_mcc = client.post(
        "/categorize", headers=HEADERS, json=body("SOME NEW PLACE 77", mcc="5411")
    ).json()["results"][0]

    assert with_mcc["category"] == "GROCERIES"


def test_requires_a_valid_api_key(client: TestClient) -> None:
    assert client.post("/categorize", json=body("X")).status_code == 401
    assert (
        client.post("/categorize", headers={"X-Api-Key": "bad"}, json=body("X")).status_code == 401
    )


def test_rejects_invalid_requests(client: TestClient) -> None:
    assert client.post("/categorize", headers=HEADERS, json={"items": []}).status_code == 422
    too_many = {"items": [{"id": str(i), "description": "x"} for i in range(501)]}
    assert client.post("/categorize", headers=HEADERS, json=too_many).status_code == 422
    assert client.post("/categorize", headers=HEADERS, json=body("x", mcc="54")).status_code == 422
    blank = {"items": [{"id": "1", "description": ""}]}
    assert client.post("/categorize", headers=HEADERS, json=blank).status_code == 422


def test_reports_unavailable_when_model_is_not_loaded() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(ml_api_key=API_KEY)
    with TestClient(app) as test_client:
        response = test_client.post("/categorize", headers=HEADERS, json=body("PYATEROCHKA"))
    app.dependency_overrides.clear()

    assert response.status_code == 503
