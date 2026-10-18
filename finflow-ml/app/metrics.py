import time
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, Response
from prometheus_client import Counter, Histogram, make_asgi_app

REQUESTS = Counter(
    "ml_http_requests_total", "HTTP requests handled by finflow-ml", ["method", "path", "status"]
)
LATENCY = Histogram("ml_http_request_duration_seconds", "HTTP request latency", ["method", "path"])
INFERENCE_LATENCY = Histogram("ml_fraud_inference_seconds", "Fraud scoring latency")
FRAUD_SCORE = Histogram(
    "ml_fraud_score",
    "Distribution of fraud scores, used to notice model drift",
    buckets=(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0),
)
FRAUD_DECISIONS = Counter("ml_fraud_decisions_total", "Fraud decisions", ["decision"])
INVALID_EVENTS = Counter("ml_invalid_events_total", "Events rejected as invalid")


def setup_metrics(app: FastAPI) -> None:
    app.mount("/metrics", make_asgi_app())

    @app.middleware("http")
    async def record(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        started = time.perf_counter()
        response = await call_next(request)
        route = request.scope.get("route")
        path = getattr(route, "path", request.url.path)
        if not path.startswith("/metrics"):
            LATENCY.labels(request.method, path).observe(time.perf_counter() - started)
            REQUESTS.labels(request.method, path, str(response.status_code)).inc()
        return response
