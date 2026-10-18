import logging

from fastapi import FastAPI

from app import __version__
from app.api import health
from app.config import get_settings
from app.metrics import setup_metrics


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    app = FastAPI(title="FinFlow ML", version=__version__)
    app.include_router(health.router)
    setup_metrics(app)
    return app


app = create_app()
