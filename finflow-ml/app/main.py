import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import __version__
from app.api import categorize, health
from app.config import get_settings
from app.kafka.fraud_consumer import FraudConsumer
from app.metrics import setup_metrics
from app.models.categorizer import MODEL_FILE as CATEGORIZER_FILE
from app.models.categorizer import CategorizerModel
from app.models.fraud import FraudModel
from app.services.fraud_scoring import FraudScoringService

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    consumer: FraudConsumer | None = None
    if (settings.model_dir / CATEGORIZER_FILE).exists():
        categorizer = CategorizerModel.load(settings.model_dir)
        app.state.categorizer = categorizer
        logger.info("Categorizer %s loaded", categorizer.version)
    else:
        logger.warning("Categorizer model not found in %s", settings.model_dir)
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
    app.include_router(categorize.router)
    setup_metrics(app)
    return app


app = create_app()
