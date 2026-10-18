from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app import __version__
from app.config import Settings, get_settings

router = APIRouter()


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str


@router.get("/health", response_model=HealthResponse)
def health(settings: Annotated[Settings, Depends(get_settings)]) -> HealthResponse:
    return HealthResponse(status="UP", service=settings.service_name, version=__version__)
