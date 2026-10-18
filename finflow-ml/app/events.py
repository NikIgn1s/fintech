from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict

CHECK_REQUESTED = "TransferCheckRequested"
CHECK_COMPLETED = "FraudCheckCompleted"
EVENT_VERSION = 1


class Envelope(BaseModel):
    model_config = ConfigDict(extra="ignore")

    eventId: UUID
    eventType: str
    version: int
    occurredAt: datetime
    payload: dict[str, Any]


class TransferCheckRequested(BaseModel):
    model_config = ConfigDict(extra="ignore")

    transferId: UUID
    fromUserId: UUID
    toUserId: UUID
    amount: Decimal
    currency: str
    fromAccountAgeDays: int
    isFirstTransferToRecipient: bool
    transfersLast1h: int
    transfersLast24h: int
    amountLast24h: Decimal
    avgTransferAmount30d: Decimal
    hourOfDay: int
    recipientAccountAgeDays: int
    recipientIncomingCount24h: int
    createdAt: datetime
