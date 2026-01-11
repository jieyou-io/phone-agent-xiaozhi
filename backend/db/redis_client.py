from __future__ import annotations

from redis.asyncio import Redis
import redis.asyncio as redis

from config.settings import settings

_redis: Redis | None = None


def get_redis() -> Redis:
    global _redis
    if _redis is None:
        _redis = redis.from_url(settings.redis_url, decode_responses=True)
    return _redis
