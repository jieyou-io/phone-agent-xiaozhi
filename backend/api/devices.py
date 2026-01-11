from __future__ import annotations

import json
import logging
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import Device, ModelConfig

logger = logging.getLogger(__name__)
router = APIRouter()

MANAGER_MODEL_SKILL_ID = "manager"


class DeviceRegister(BaseModel):
    device_id: str = Field(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$")
    user_id: int | None = None
    model: str | None = Field(None, max_length=128)
    os_version: str | None = Field(None, max_length=64)
    app_version: str | None = Field(None, max_length=32)


class DeviceUpdate(BaseModel):
    user_id: int | None = None
    model: str | None = Field(None, max_length=128)
    os_version: str | None = Field(None, max_length=64)
    app_version: str | None = Field(None, max_length=32)
    last_seen: Any | None = None


class DeviceStatusUpdate(BaseModel):
    status: Literal[1, 0]


class DefaultModelPayload(BaseModel):
    provider: str = Field(..., min_length=1, max_length=64)
    base_url: str = Field(..., min_length=1, max_length=255)
    api_key: str = Field(..., min_length=1, max_length=255)
    model: str = Field(..., min_length=1, max_length=128)
    config: dict | None = None


class DefaultModelResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    owner_device_id: str
    skill_id: str | None
    provider: str
    base_url: str
    api_key: str
    model: str
    config: dict | None
    created_at: Any
    updated_at: Any | None


class DeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    device_id: str
    user_id: int | None
    model: str | None
    os_version: str | None
    app_version: str | None
    status: int
    last_seen: Any | None
    created_at: Any
    updated_at: Any | None


async def _get_device_or_404(session: AsyncSession, device_id: str) -> Device:
    result = await session.execute(select(Device).where(Device.device_id == device_id))
    device = result.scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=404, detail="未找到设备")
    return device


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
            logger.warning("默认模型 config JSON 解析失败")
            return None
    logger.warning("默认模型 config 类型无效: %s", type(raw))
    return None


def _dump_config_value(value: dict[str, Any] | None) -> str:
    if value is None:
        return "{}"
    try:
        return json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        logger.warning("默认模型 config JSON 序列化失败")
        raise HTTPException(status_code=400, detail="config JSON 序列化失败")


def _to_response(config: ModelConfig) -> DefaultModelResponse:
    return DefaultModelResponse(
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
    )


@router.post("/api/devices", response_model=DeviceResponse)
async def register_device(
    payload: DeviceRegister,
    session: AsyncSession = Depends(get_session),
) -> Device:
    result = await session.execute(select(Device).where(Device.device_id == payload.device_id))
    device = result.scalar_one_or_none()

    if device:
        updates = payload.model_dump(exclude_none=True, exclude={"device_id"})
        for key, value in updates.items():
            setattr(device, key, value)
        device.status = 1
        device.last_seen = func.now()
    else:
        device = Device(
            device_id=payload.device_id,
            user_id=payload.user_id,
            model=payload.model,
            os_version=payload.os_version,
            app_version=payload.app_version,
            status=1,
            last_seen=func.now(),
        )
        session.add(device)

    try:
        await session.commit()
        await session.refresh(device)
    except IntegrityError:
        await session.rollback()
        logger.exception("注册设备时发生完整性错误，尝试更新重试")
        try:
            result = await session.execute(select(Device).where(Device.device_id == payload.device_id))
            device = result.scalar_one_or_none()
            if device:
                updates = payload.model_dump(exclude_none=True, exclude={"device_id"})
                for key, value in updates.items():
                    setattr(device, key, value)
                device.status = 1
                device.last_seen = func.now()
                await session.commit()
                await session.refresh(device)
            else:
                raise HTTPException(status_code=422, detail="注册设备失败: 违反数据库约束")
        except (IntegrityError, SQLAlchemyError):
            await session.rollback()
            logger.exception("重试注册设备失败")
            raise HTTPException(status_code=422, detail="注册设备失败: 违反数据库约束")
    return device


@router.get("/api/devices/{device_id}", response_model=DeviceResponse)
async def get_device(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    session: AsyncSession = Depends(get_session),
) -> Device:
    return await _get_device_or_404(session, device_id)


@router.get("/api/devices", response_model=list[DeviceResponse])
async def list_devices(
    user_id: int | None = Query(None, description="按用户 ID 过滤"),
    status: Literal[1, 0] | None = Query(None, description="按状态过滤"),
    limit: int = Query(100, ge=1, le=500, description="最大返回数量"),
    offset: int = Query(0, ge=0, description="跳过的结果数量"),
    session: AsyncSession = Depends(get_session),
) -> list[Device]:
    stmt = select(Device)
    if user_id is not None:
        stmt = stmt.where(Device.user_id == user_id)
    if status is not None:
        stmt = stmt.where(Device.status == status)
    stmt = stmt.order_by(Device.last_seen.desc()).limit(limit).offset(offset)
    result = await session.execute(stmt)
    return list(result.scalars().all())


@router.put("/api/devices/{device_id}", response_model=DeviceResponse)
async def update_device(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    payload: DeviceUpdate = ...,
    session: AsyncSession = Depends(get_session),
) -> Device:
    updates = payload.model_dump(exclude_none=True)
    if not updates:
        raise HTTPException(status_code=400, detail="没有需要更新的字段")

    device = await _get_device_or_404(session, device_id)
    for key, value in updates.items():
        setattr(device, key, value)

    try:
        await session.commit()
        await session.refresh(device)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新设备 {device_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新设备失败: 违反数据库约束")
    return device


@router.put("/api/devices/{device_id}/status", response_model=DeviceResponse)
async def update_device_status(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    payload: DeviceStatusUpdate = ...,
    session: AsyncSession = Depends(get_session),
) -> Device:
    device = await _get_device_or_404(session, device_id)
    device.status = payload.status

    try:
        await session.commit()
        await session.refresh(device)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新设备状态 {device_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新设备状态失败: 违反数据库约束")
    return device


@router.get("/api/devices/{device_id}/default-model", response_model=DefaultModelResponse)
async def get_device_default_model(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    session: AsyncSession = Depends(get_session),
) -> DefaultModelResponse:
    """获取设备的默认模型配置"""
    await _get_device_or_404(session, device_id)

    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id.is_(None)
        )
    )
    model_config = result.scalar_one_or_none()
    if not model_config:
        raise HTTPException(status_code=404, detail="未找到默认模型配置")

    return _to_response(model_config)


@router.post("/api/devices/{device_id}/default-model", response_model=DefaultModelResponse)
async def set_device_default_model(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    payload: DefaultModelPayload = ...,
    session: AsyncSession = Depends(get_session),
) -> DefaultModelResponse:
    await _get_device_or_404(session, device_id)

    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id.is_(None)
        )
    )
    model_config = result.scalar_one_or_none()

    updates = payload.model_dump(exclude_unset=True)
    if "config" in updates:
        updates["config"] = _dump_config_value(updates["config"])
    if model_config:
        for key, value in updates.items():
            setattr(model_config, key, value)
    else:
        payload_data = payload.model_dump()
        payload_data["config"] = _dump_config_value(payload_data.get("config"))
        model_config = ModelConfig(
            owner_device_id=device_id,
            skill_id=None,
            **payload_data,
        )
        session.add(model_config)

    try:
        await session.commit()
        await session.refresh(model_config)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"为设备 {device_id} 设置默认模型时发生完整性错误")
        raise HTTPException(status_code=422, detail="设置默认模型失败: 违反数据库约束")

    return _to_response(model_config)


@router.get("/api/devices/{device_id}/manager-model", response_model=DefaultModelResponse)
async def get_device_manager_model(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    session: AsyncSession = Depends(get_session),
) -> DefaultModelResponse:
    """获取设备的规划模型配置（manager_model）"""
    await _get_device_or_404(session, device_id)

    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id == MANAGER_MODEL_SKILL_ID
        )
    )
    model_config = result.scalar_one_or_none()
    if not model_config:
        raise HTTPException(status_code=404, detail="未找到规划模型配置")

    return _to_response(model_config)


@router.post("/api/devices/{device_id}/manager-model", response_model=DefaultModelResponse)
async def set_device_manager_model(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    payload: DefaultModelPayload = ...,
    session: AsyncSession = Depends(get_session),
) -> DefaultModelResponse:
    """设置设备的规划模型配置（manager_model）"""
    await _get_device_or_404(session, device_id)

    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id == MANAGER_MODEL_SKILL_ID
        )
    )
    model_config = result.scalar_one_or_none()

    updates = payload.model_dump(exclude_unset=True)
    if "config" in updates:
        updates["config"] = _dump_config_value(updates["config"])
    if model_config:
        for key, value in updates.items():
            setattr(model_config, key, value)
    else:
        payload_data = payload.model_dump()
        payload_data["config"] = _dump_config_value(payload_data.get("config"))
        model_config = ModelConfig(
            owner_device_id=device_id,
            skill_id=MANAGER_MODEL_SKILL_ID,
            **payload_data,
        )
        session.add(model_config)

    try:
        await session.commit()
        await session.refresh(model_config)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"为设备 {device_id} 设置规划模型时发生完整性错误")
        raise HTTPException(status_code=422, detail="设置规划模型失败: 违反数据库约束")

    return _to_response(model_config)


@router.delete("/api/devices/{device_id}/manager-model", status_code=204)
async def delete_device_manager_model(
    device_id: str = Path(..., min_length=36, max_length=36, pattern=r"^[a-fA-F0-9\-]{36}$"),
    session: AsyncSession = Depends(get_session),
) -> None:
    """删除设备的规划模型配置（恢复使用 default_model）"""
    await _get_device_or_404(session, device_id)

    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id == MANAGER_MODEL_SKILL_ID
        )
    )
    model_config = result.scalar_one_or_none()

    if model_config:
        await session.delete(model_config)
        try:
            await session.commit()
            logger.info(f"设备 {device_id} 的规划模型配置已删除")
        except SQLAlchemyError:
            await session.rollback()
            logger.exception(f"删除设备 {device_id} 的规划模型配置失败")
            raise HTTPException(status_code=500, detail="删除规划模型配置失败")
    else:
        logger.debug(f"设备 {device_id} 没有规划模型配置,无需删除")

