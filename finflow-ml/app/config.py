from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", protected_namespaces=())

    service_name: str = "finflow-ml"
    log_level: str = "INFO"
    ml_api_key: str = ""
    kafka_bootstrap_servers: str = "localhost:9092"
    fraud_hold_threshold: float = 0.7
    model_dir: Path = Path("artifacts")
    fraud_consumer_enabled: bool = True
    check_topic: str = "transfers.check"
    result_topic: str = "transfers.check.result"
    consumer_group: str = "finflow-ml"


@lru_cache
def get_settings() -> Settings:
    return Settings()
