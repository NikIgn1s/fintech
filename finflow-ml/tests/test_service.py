from collections.abc import Iterator

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app
from app.security import require_api_key


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


def test_health_reports_service_and_version(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "finflow-ml", "version": "0.1.0"}


def test_metrics_expose_request_counters(client: TestClient) -> None:
    client.get("/health")

    body = client.get("/metrics/").text

    assert 'ml_http_requests_total{method="GET",path="/health",status="200"}' in body


def test_settings_defaults() -> None:
    settings = Settings()

    assert settings.fraud_hold_threshold == 0.7
    assert settings.kafka_bootstrap_servers == "localhost:9092"


def protected_client(api_key: str) -> TestClient:
    guarded = FastAPI()

    @guarded.get("/secret", dependencies=[Depends(require_api_key)])
    def secret() -> dict[str, str]:
        return {"ok": "yes"}

    guarded.dependency_overrides[get_settings] = lambda: Settings(ml_api_key=api_key)
    return TestClient(guarded)


def test_api_key_is_required_and_checked() -> None:
    client = protected_client("s3cret")

    assert client.get("/secret").status_code == 401
    assert client.get("/secret", headers={"X-Api-Key": "wrong"}).status_code == 401
    assert client.get("/secret", headers={"X-Api-Key": "s3cret"}).status_code == 200


def test_unconfigured_api_key_rejects_everything() -> None:
    client = protected_client("")

    assert client.get("/secret", headers={"X-Api-Key": ""}).status_code == 503
