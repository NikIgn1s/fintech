import time
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.metrics import CATEGORIZE_CONFIDENCE, CATEGORIZE_ITEMS, CATEGORIZE_LATENCY
from app.models.categorizer import CategorizerModel, compose_text
from app.security import require_api_key

router = APIRouter()

MAX_BATCH = 500


class CategorizeItem(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    description: str = Field(min_length=1, max_length=500)
    amount: float | None = None
    mcc: str | None = Field(default=None, pattern=r"^\d{4}$")


class CategorizeRequest(BaseModel):
    items: list[CategorizeItem] = Field(min_length=1, max_length=MAX_BATCH)


class CategoryResult(BaseModel):
    id: str
    category: str
    confidence: float
    top3: list[tuple[str, float]]


class CategorizeResponse(BaseModel):
    modelVersion: str
    results: list[CategoryResult]


def get_categorizer(request: Request) -> CategorizerModel:
    model: CategorizerModel | None = getattr(request.app.state, "categorizer", None)
    if model is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Categorizer is not loaded"
        )
    return model


@router.post(
    "/categorize", response_model=CategorizeResponse, dependencies=[Depends(require_api_key)]
)
def categorize(
    request: CategorizeRequest, model: Annotated[CategorizerModel, Depends(get_categorizer)]
) -> CategorizeResponse:
    started = time.perf_counter()
    texts = [compose_text(item.description, item.mcc) for item in request.items]
    predictions = model.predict(texts)
    CATEGORIZE_LATENCY.observe(time.perf_counter() - started)
    CATEGORIZE_ITEMS.inc(len(predictions))
    for prediction in predictions:
        CATEGORIZE_CONFIDENCE.observe(prediction.confidence)
    return CategorizeResponse(
        modelVersion=model.version,
        results=[
            CategoryResult(
                id=item.id,
                category=prediction.category,
                confidence=prediction.confidence,
                top3=prediction.top3,
            )
            for item, prediction in zip(request.items, predictions, strict=True)
        ],
    )
