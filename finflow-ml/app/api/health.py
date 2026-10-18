from typing import Annotated

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from app import __version__
from app.config import Settings, get_settings

router = APIRouter()


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    fraud_model: str | None = None
    fraud_consumer: str = "DISABLED"


@router.get("/health", response_model=HealthResponse)
def health(
    request: Request, settings: Annotated[Settings, Depends(get_settings)]
) -> HealthResponse:
    scoring = getattr(request.app.state, "fraud_scoring", None)
    consumer = getattr(request.app.state, "fraud_consumer", None)
    if consumer is None:
        consumer_state = "DISABLED"
    else:
        consumer_state = "RUNNING" if consumer.running else "STOPPED"
    return HealthResponse(
        status="UP" if consumer_state != "STOPPED" else "DEGRADED",
        service=settings.service_name,
        version=__version__,
        fraud_model=scoring.model_version if scoring is not None else None,
        fraud_consumer=consumer_state,
    )
