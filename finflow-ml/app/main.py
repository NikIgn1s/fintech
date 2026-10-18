import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import __version__
from app.api import health
from app.config import get_settings
from app.kafka.fraud_consumer import FraudConsumer
from app.metrics import setup_metrics
from app.models.fraud import FraudModel
from app.services.fraud_scoring import FraudScoringService

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    consumer: FraudConsumer | None = None
    if settings.fraud_consumer_enabled:
        model = FraudModel.load(settings.model_dir)
        scoring = FraudScoringService(model, settings.fraud_hold_threshold)
        consumer = FraudConsumer(settings, scoring)
        app.state.fraud_scoring = scoring
        app.state.fraud_consumer = consumer
        await consumer.start()
        logger.info("Fraud model %s loaded", model.version)
    try:
        yield
    finally:
        if consumer is not None:
            await consumer.stop()


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    app = FastAPI(title="FinFlow ML", version=__version__, lifespan=lifespan)
    app.include_router(health.router)
    setup_metrics(app)
    return app


app = create_app()
