from __future__ import annotations

import os

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "phone-agent"
    websocket_path: str = "/ws"
    db_host: str = os.getenv("DB_HOST", "localhost")
    db_port: int = int(os.getenv("DB_PORT", "3306"))
    db_user: str = os.getenv("DB_USER", "root")
    db_password: str = os.getenv("DB_PASSWORD", "")
    db_name: str = os.getenv("DB_NAME", "xiaozhi")
    db_charset: str = "utf8mb4"
    db_collation: str = "utf8mb4_unicode_ci"
    db_pool_size: int = 10
    db_max_overflow: int = 20
    db_pool_recycle: int = 1800
    redis_host: str = os.getenv("REDIS_HOST", "localhost")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    redis_password: str = os.getenv("REDIS_PASSWORD", "")
    redis_db: int = 0
    admin_session_ttl_seconds: int = 60 * 60 * 24
    admin_session_secure_cookie: bool = False

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"

    @property
    def database_url(self) -> str:
        return (
            f"mysql+asyncmy://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
            f"?charset={self.db_charset}"
        )

    @property
    def redis_url(self) -> str:
        return f"redis://:{self.redis_password}@{self.redis_host}:{self.redis_port}/{self.redis_db}"


settings = Settings()
