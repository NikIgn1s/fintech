import asyncio
import contextlib
import logging
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config import Settings
from app.services.fraud_scoring import FraudScoringService, InvalidEventError

logger = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 1.0
DEAD_LETTER_SUFFIX = ".DLT"


class FraudConsumer:
    def __init__(self, settings: Settings, service: FraudScoringService) -> None:
        self._settings = settings
        self._service = service
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._task: asyncio.Task[None] | None = None

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            acks="all",
            enable_idempotence=True,
        )
        self._consumer = AIOKafkaConsumer(
            self._settings.check_topic,
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            group_id=self._settings.consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        await self._producer.start()
        await self._consumer.start()
        self._task = asyncio.create_task(self._run(), name="fraud-consumer")
        logger.info("Fraud consumer started on %s", self._settings.check_topic)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()

    async def _run(self) -> None:
        assert self._consumer is not None
        async for message in self._consumer:
            await self._process(message)
            await self._consumer.commit()

    async def _process(self, message: Any) -> None:
        assert self._producer is not None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                outcome = self._service.handle(message.value)
                await self._producer.send_and_wait(
                    self._settings.result_topic, key=outcome.key, value=outcome.value
                )
                logger.info(
                    "Scored transfer %s: %.4f %s",
                    outcome.key.decode(),
                    outcome.score,
                    outcome.decision,
                )
                return
            except InvalidEventError as error:
                logger.warning("Invalid check request at offset %s: %s", message.offset, error)
                await self._park(message, str(error))
                return
            except Exception:
                logger.exception("Scoring attempt %d of %d failed", attempt, MAX_ATTEMPTS)
                if attempt < MAX_ATTEMPTS:
                    await asyncio.sleep(RETRY_DELAY_SECONDS * attempt)
        await self._park(message, "scoring failed after retries")

    async def _park(self, message: Any, reason: str) -> None:
        assert self._producer is not None
        await self._producer.send_and_wait(
            message.topic + DEAD_LETTER_SUFFIX,
            key=message.key,
            value=message.value,
            headers=[("error", reason[:500].encode())],
        )
