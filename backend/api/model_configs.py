from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import ModelConfig

logger = logging.getLogger(__name__)
router = APIRouter()


class ModelConfigBase(BaseModel):
    provider: str = Field(..., min_length=1, max_length=64)
    base_url: str = Field(..., min_length=1, max_length=255)
    api_key: str = Field(..., min_length=1, max_length=255)
    model: str = Field(..., min_length=1, max_length=128)
    config: dict[str, Any] | None = None


class ModelConfigCreate(ModelConfigBase):
    owner_device_id: str = Field(..., min_length=36, max_length=36, description="设备 ID（必填）")
    skill_id: str | None = Field(None, min_length=1, max_length=64)


class ModelConfigUpdate(BaseModel):
    provider: str | None = Field(None, min_length=1, max_length=64)
    base_url: str | None = Field(None, min_length=1, max_length=255)
    api_key: str | None = Field(None, min_length=1, max_length=255)
    model: str | None = Field(None, min_length=1, max_length=128)
    config: dict[str, Any] | None = None


class ModelConfigResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    owner_device_id: str
    skill_id: str | None
    provider: str
    base_url: str
    api_key: str
    model: str
    config: dict[str, Any] | None
    created_at: Any
    updated_at: Any | None
    scope_key: str


async def _get_model_config_or_404(session: AsyncSession, config_id: int) -> ModelConfig:
    result = await session.execute(select(ModelConfig).where(ModelConfig.id == config_id))
    config = result.scalar_one_or_none()
    if not config:
        raise HTTPException(status_code=404, detail="未找到模型配置")
    return config


def _parse_nullable_param(value: str | None) -> tuple[bool, str | None]:
    if value is None:
        return False, None
    value = value.strip()
    if value.lower() == "null":
        return True, None
    return False, value


def _validate_scope(owner_device_id: str | None, skill_id: str | None) -> None:
    """验证配置作用域：owner_device_id 必须存在"""
    if owner_device_id is None:
        raise HTTPException(status_code=422, detail="owner_device_id 为必填项")


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
            logger.warning("模型配置 config JSON 解析失败")
            return None
    logger.warning("模型配置 config 类型无效: %s", type(raw))
    return None


def _dump_config_value(value: dict[str, Any] | None) -> str:
    if value is None:
        return "{}"
    try:
        return json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        logger.warning("模型配置 config JSON 序列化失败")
        raise HTTPException(status_code=400, detail="config JSON 序列化失败")


def _to_response(config: ModelConfig) -> ModelConfigResponse:
    return ModelConfigResponse(
        id=config.id,
        owner_device_id=config.owner_device_id,
        skill_id=config.skill_id,
        provider=config.provider,
        base_url=config.base_url,
        api_key=config.api_key,
        model=config.model,
        config=_parse_config_value(config.config),
        created_at=config.created_at,
        updated_at=config.updated_at,
        scope_key=config.scope_key,
    )


@router.get("/api/model-configs/resolve", response_model=ModelConfigResponse)
async def resolve_model_config(
    device_id: str = Query(..., min_length=36, max_length=36, description="用于解析的设备 ID"),
    skill_id: str = Query(..., min_length=1, max_length=64, description="用于解析的技能 ID"),
    session: AsyncSession = Depends(get_session),
) -> ModelConfigResponse:
    """解析模型配置：优先级 skill-level > device-level"""
    # 1. 尝试查找 skill-level 配置
    stmt = select(ModelConfig).where(
        ModelConfig.owner_device_id == device_id,
        ModelConfig.skill_id == skill_id,
    )
    result = await session.execute(stmt)
    config = result.scalar_one_or_none()
    if config:
        return _to_response(config)

    # 2. 回退到 device-level 默认配置
    stmt = select(ModelConfig).where(
        ModelConfig.owner_device_id == device_id,
        ModelConfig.skill_id.is_(None),
    )
    result = await session.execute(stmt)
    config = result.scalar_one_or_none()
    if config:
        return _to_response(config)

    # 3. 未找到任何配置
    raise HTTPException(status_code=404, detail="未找到设备与技能对应的模型配置")


@router.get("/api/model-configs", response_model=list[ModelConfigResponse])
async def list_model_configs(
    owner_device_id: str | None = Query(None, min_length=36, max_length=36, description="按设备 ID 过滤"),
    skill_id: str | None = Query(None, description="按技能 ID 过滤，或使用 'null' 查询设备默认配置"),
    session: AsyncSession = Depends(get_session),
) -> list[ModelConfigResponse]:
    """列出模型配置（所有配置都绑定设备）"""
    stmt = select(ModelConfig)

    # 按设备 ID 过滤
    if owner_device_id is not None:
        stmt = stmt.where(ModelConfig.owner_device_id == owner_device_id)

    # 按技能 ID 过滤（支持 'null' 查询设备级默认配置）
    skill_is_null, skill_value = _parse_nullable_param(skill_id)
    if skill_is_null:
        stmt = stmt.where(ModelConfig.skill_id.is_(None))
    elif skill_value is not None:
        stmt = stmt.where(ModelConfig.skill_id == skill_value)

    stmt = stmt.order_by(ModelConfig.created_at.desc())
    result = await session.execute(stmt)
    return [_to_response(config) for config in result.scalars().all()]


@router.post("/api/model-configs", response_model=ModelConfigResponse, status_code=201)
async def create_model_config(
    payload: ModelConfigCreate,
    session: AsyncSession = Depends(get_session),
) -> ModelConfigResponse:
    _validate_scope(payload.owner_device_id, payload.skill_id)

    payload_data = payload.model_dump()
    payload_data["config"] = _dump_config_value(payload_data.get("config"))
    config = ModelConfig(
        owner_device_id=payload_data["owner_device_id"],
        skill_id=payload_data["skill_id"],
        provider=payload_data["provider"],
        base_url=payload_data["base_url"],
        api_key=payload_data["api_key"],
        model=payload_data["model"],
        config=payload_data["config"],
    )
    session.add(config)
    try:
        await session.commit()
        await session.refresh(config)
    except IntegrityError:
        await session.rollback()
        logger.exception("创建模型配置时发生完整性错误")
        raise HTTPException(status_code=422, detail="创建模型配置失败: 违反数据库约束")
    return _to_response(config)


@router.put("/api/model-configs/{config_id}", response_model=ModelConfigResponse)
async def update_model_config(
    config_id: int = Path(..., ge=1),
    payload: ModelConfigUpdate = ...,
    session: AsyncSession = Depends(get_session),
) -> ModelConfigResponse:
    updates = payload.model_dump(exclude_unset=True)
    if not updates:
        raise HTTPException(status_code=400, detail="没有需要更新的字段")
    if "config" in updates:
        updates["config"] = _dump_config_value(updates["config"])

    config = await _get_model_config_or_404(session, config_id)
    for key, value in updates.items():
        setattr(config, key, value)

    try:
        await session.commit()
        await session.refresh(config)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新模型配置 {config_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新模型配置失败: 违反数据库约束")
    return _to_response(config)


@router.delete("/api/model-configs/{config_id}", status_code=200)
async def delete_model_config(
    config_id: int = Path(..., ge=1),
    session: AsyncSession = Depends(get_session),
) -> dict:
    config = await _get_model_config_or_404(session, config_id)
    await session.delete(config)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        logger.exception(f"删除模型配置 {config_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="删除模型配置失败: 违反数据库约束")
    return {"status": "deleted", "id": config_id}
