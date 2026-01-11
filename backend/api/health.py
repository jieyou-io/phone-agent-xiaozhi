from __future__ import annotations

from fastapi import APIRouter, HTTPException
from sqlalchemy import text

from db.connection import async_engine
from db.redis_client import get_redis

router = APIRouter()


@router.get("/api/health/db")
async def health_db() -> dict:
    try:
        async with async_engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return {"database": "ok"}
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"数据库错误: {str(e)}")


@router.get("/api/health/redis")
async def health_redis() -> dict:
    try:
        client = get_redis()
        await client.ping()
        return {"redis": "ok"}
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Redis 错误: {str(e)}")


@router.get("/api/health")
async def health_all() -> dict:
    results = {"database": "ok", "redis": "ok"}
    errors = []

    try:
        async with async_engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
    except Exception as e:
        results["database"] = "error"
        errors.append(f"数据库: {str(e)}")

    try:
        client = get_redis()
        await client.ping()
    except Exception as e:
        results["redis"] = "error"
        errors.append(f"Redis: {str(e)}")

    if errors:
        raise HTTPException(status_code=503, detail={"status": results, "errors": errors})

    return results
