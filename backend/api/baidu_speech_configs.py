from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import BaiduSpeechConfig

logger = logging.getLogger(__name__)
router = APIRouter()

UUID_PATTERN = r"^[a-fA-F0-9\-]{36}$"


class BaiduSpeechConfigBase(BaseModel):
    app_id: str = Field(..., min_length=1, max_length=128)
    api_key: str = Field(..., min_length=1, max_length=255)
    secret_key: str = Field(..., min_length=1, max_length=255)
    config: dict[str, Any] | None = None


class BaiduSpeechConfigCreate(BaiduSpeechConfigBase):
    owner_device_id: str = Field(..., min_length=36, max_length=36, pattern=UUID_PATTERN)


class BaiduSpeechConfigUpdate(BaseModel):
    app_id: str | None = Field(None, min_length=1, max_length=128)
    api_key: str | None = Field(None, min_length=1, max_length=255)
    secret_key: str | None = Field(None, min_length=1, max_length=255)
    config: dict[str, Any] | None = None


class BaiduSpeechConfigResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    owner_device_id: str
    app_id: str
    api_key: str
    secret_key: str
    config: dict[str, Any] | None
    created_at: Any
    updated_at: Any | None


async def _get_baidu_speech_config_or_404(session: AsyncSession, device_id: str) -> BaiduSpeechConfig:
    result = await session.execute(
        select(BaiduSpeechConfig).where(BaiduSpeechConfig.owner_device_id == device_id)
    )
    config = result.scalar_one_or_none()
    if not config:
        raise HTTPException(status_code=404, detail="未找到百度语音配置")
    return config


def _parse_config_value(raw: Any) -> dict[str, Any] | None:
    if raw is None:
        return None
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("百度语音配置 config JSON 解析失败")
            return None
    logger.warning("百度语音配置 config 类型无效: %s", type(raw))
    return None


def _dump_config_value(value: dict[str, Any] | None) -> str | None:
    if value is None:
        return None
    try:
        return json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        logger.warning("百度语音配置 config JSON 序列化失败")
        raise HTTPException(status_code=400, detail="config JSON 序列化失败")


def _to_response(config: BaiduSpeechConfig) -> BaiduSpeechConfigResponse:
    return BaiduSpeechConfigResponse(
        owner_device_id=config.owner_device_id,
        app_id=config.app_id,
        api_key=config.api_key,
        secret_key=config.secret_key,
        config=_parse_config_value(config.config),
        created_at=config.created_at,
        updated_at=config.updated_at,
    )


@router.get("/api/baidu-speech-configs", response_model=list[BaiduSpeechConfigResponse])
async def list_baidu_speech_configs(
    session: AsyncSession = Depends(get_session),
) -> list[BaiduSpeechConfigResponse]:
    """列出所有设备的百度语音配置（用于管理后台表格显示）"""
    result = await session.execute(select(BaiduSpeechConfig))
    return [_to_response(item) for item in result.scalars().all()]


@router.get("/api/baidu-speech-configs/{device_id}", response_model=BaiduSpeechConfigResponse)
async def get_baidu_speech_config(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=UUID_PATTERN),
    session: AsyncSession = Depends(get_session),
) -> BaiduSpeechConfigResponse:
    config = await _get_baidu_speech_config_or_404(session, device_id)
    return _to_response(config)


@router.post("/api/baidu-speech-configs", response_model=BaiduSpeechConfigResponse, status_code=201)
async def create_baidu_speech_config(
    payload: BaiduSpeechConfigCreate,
    session: AsyncSession = Depends(get_session),
) -> BaiduSpeechConfig:
    config = BaiduSpeechConfig(
        owner_device_id=payload.owner_device_id,
        app_id=payload.app_id,
        api_key=payload.api_key,
        secret_key=payload.secret_key,
        config=_dump_config_value(payload.config),
    )
    session.add(config)
    try:
        await session.commit()
        await session.refresh(config)
    except IntegrityError:
        await session.rollback()
        logger.exception("创建百度语音配置时发生完整性错误")
        raise HTTPException(status_code=422, detail="创建百度语音配置失败: 违反数据库约束")
    return _to_response(config)


@router.put("/api/baidu-speech-configs/{device_id}", response_model=BaiduSpeechConfigResponse)
async def update_baidu_speech_config(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=UUID_PATTERN),
    payload: BaiduSpeechConfigUpdate = ...,
    session: AsyncSession = Depends(get_session),
) -> BaiduSpeechConfig:
    updates = payload.model_dump(exclude_unset=True)
    if not updates:
        raise HTTPException(status_code=400, detail="没有需要更新的字段")
    if "config" in updates:
        updates["config"] = _dump_config_value(updates["config"])

    config = await _get_baidu_speech_config_or_404(session, device_id)
    for key, value in updates.items():
        setattr(config, key, value)

    try:
        await session.commit()
        await session.refresh(config)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新百度语音配置 {device_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新百度语音配置失败: 违反数据库约束")
    return _to_response(config)


@router.delete("/api/baidu-speech-configs/{device_id}", status_code=200)
async def delete_baidu_speech_config(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=UUID_PATTERN),
    session: AsyncSession = Depends(get_session),
) -> dict:
    config = await _get_baidu_speech_config_or_404(session, device_id)
    await session.delete(config)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        logger.exception(f"删除百度语音配置 {device_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="删除百度语音配置失败: 违反数据库约束")
    return {"status": "deleted", "device_id": device_id}
