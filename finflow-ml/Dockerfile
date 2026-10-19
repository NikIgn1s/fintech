FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY pyproject.toml ./
COPY app app
COPY training training
RUN pip install . \
    && mkdir -p artifacts \
    && python -m training.train_fraud --output artifacts \
    && python -m training.train_categorizer_baseline --output artifacts

RUN useradd --system --no-create-home finflow \
    && chown -R finflow:finflow /app
USER finflow

EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=3)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
