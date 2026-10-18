import hmac
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings


def require_api_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_api_key: Annotated[str | None, Header()] = None,
) -> None:
    if not settings.ml_api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="API key is not configured"
        )
    if x_api_key is None or not hmac.compare_digest(x_api_key, settings.ml_api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid API key")
