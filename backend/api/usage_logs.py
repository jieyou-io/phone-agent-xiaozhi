from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import case, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import DeviceSession, UsageLog

logger = logging.getLogger(__name__)
router = APIRouter()


class UsageLogCreate(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=36)
    skill_id: str = Field(..., min_length=1, max_length=64)
    status: Literal[1, 0]
    task_text: str | None = None
    execution_ms: int | None = Field(None, ge=0)


class UsageLogResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    device_id: str
    skill_id: str
    task_text: str | None
    status: int
    execution_ms: int | None
    created_at: Any


class TopSkill(BaseModel):
    skill_id: str
    count: int


class UsageLogStatsResponse(BaseModel):
    total_count: int
    success_count: int
    failure_count: int
    avg_execution_ms: float | None
    top_skills: list[TopSkill]


class DeviceSessionCreate(BaseModel):
    session_id: str = Field(..., min_length=1, max_length=36)
    device_id: str = Field(..., min_length=1, max_length=36)
    ip_address: str | None = Field(None, max_length=45)
    user_agent: str | None = Field(None, max_length=255)


class DeviceSessionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    session_id: str
    device_id: str
    connected_at: Any
    disconnected_at: Any | None
    ip_address: str | None
    user_agent: str | None


async def _get_device_session_or_404(session: AsyncSession, session_id: str) -> DeviceSession:
    result = await session.execute(select(DeviceSession).where(DeviceSession.session_id == session_id))
    device_session = result.scalar_one_or_none()
    if not device_session:
        raise HTTPException(status_code=404, detail="未找到设备会话")
    return device_session


@router.post("/api/usage-logs", response_model=UsageLogResponse, status_code=201)
async def create_usage_log(
    payload: UsageLogCreate,
    session: AsyncSession = Depends(get_session),
) -> UsageLog:
    usage_log = UsageLog(
        device_id=payload.device_id,
        skill_id=payload.skill_id,
        status=payload.status,
        task_text=payload.task_text,
        execution_ms=payload.execution_ms,
    )
    session.add(usage_log)
    try:
        await session.commit()
        await session.refresh(usage_log)
    except IntegrityError:
        await session.rollback()
        logger.exception("创建使用日志时发生完整性错误")
        raise HTTPException(status_code=422, detail="创建使用日志失败: 违反数据库约束")
    return usage_log


@router.get("/api/usage-logs", response_model=list[UsageLogResponse])
async def list_usage_logs(
    device_id: str | None = Query(None, description="按设备 ID 过滤"),
    skill_id: str | None = Query(None, description="按技能 ID 过滤"),
    status: Literal[1, 0] | None = Query(None, description="按状态过滤"),
    start_date: datetime | None = Query(None, description="筛选在此日期之后创建的日志"),
    end_date: datetime | None = Query(None, description="筛选在此日期之前创建的日志"),
    limit: int = Query(100, ge=1, le=500, description="最大返回数量"),
    offset: int = Query(0, ge=0, description="跳过的结果数量"),
    session: AsyncSession = Depends(get_session),
) -> list[UsageLog]:
    stmt = select(UsageLog)
    if device_id is not None:
        stmt = stmt.where(UsageLog.device_id == device_id)
    if skill_id is not None:
        stmt = stmt.where(UsageLog.skill_id == skill_id)
    if status is not None:
        stmt = stmt.where(UsageLog.status == status)
    if start_date is not None:
        stmt = stmt.where(UsageLog.created_at >= start_date)
    if end_date is not None:
        stmt = stmt.where(UsageLog.created_at <= end_date)
    stmt = stmt.order_by(UsageLog.created_at.desc()).limit(limit).offset(offset)
    result = await session.execute(stmt)
    return list(result.scalars().all())


@router.get("/api/usage-logs/stats", response_model=UsageLogStatsResponse)
async def get_usage_log_stats(
    device_id: str | None = Query(None, description="按设备 ID 过滤"),
    skill_id: str | None = Query(None, description="按技能 ID 过滤"),
    start_date: datetime | None = Query(None, description="筛选在此日期之后创建的日志"),
    end_date: datetime | None = Query(None, description="筛选在此日期之前创建的日志"),
    session: AsyncSession = Depends(get_session),
) -> UsageLogStatsResponse:
    filters = []
    if device_id is not None:
        filters.append(UsageLog.device_id == device_id)
    if skill_id is not None:
        filters.append(UsageLog.skill_id == skill_id)
    if start_date is not None:
        filters.append(UsageLog.created_at >= start_date)
    if end_date is not None:
        filters.append(UsageLog.created_at <= end_date)

    stats_stmt = select(
        func.count(UsageLog.id).label("total_count"),
        func.sum(case((UsageLog.status == 1, 1), else_=0)).label("success_count"),
        func.sum(case((UsageLog.status == 0, 1), else_=0)).label("failure_count"),
        func.avg(UsageLog.execution_ms).label("avg_execution_ms"),
    )
    if filters:
        stats_stmt = stats_stmt.where(*filters)
    stats_row = (await session.execute(stats_stmt)).one()

    avg_execution_ms = stats_row.avg_execution_ms
    if avg_execution_ms is not None:
        avg_execution_ms = float(avg_execution_ms)

    top_stmt = select(
        UsageLog.skill_id,
        func.count(UsageLog.id).label("count"),
    )
    if filters:
        top_stmt = top_stmt.where(*filters)
    top_stmt = top_stmt.group_by(UsageLog.skill_id).order_by(func.count(UsageLog.id).desc()).limit(10)
    top_result = await session.execute(top_stmt)
    top_skills = [TopSkill(skill_id=row.skill_id, count=row.count) for row in top_result.all()]

    return UsageLogStatsResponse(
        total_count=int(stats_row.total_count or 0),
        success_count=int(stats_row.success_count or 0),
        failure_count=int(stats_row.failure_count or 0),
        avg_execution_ms=avg_execution_ms,
        top_skills=top_skills,
    )


@router.post("/api/device-sessions", response_model=DeviceSessionResponse, status_code=201)
async def create_device_session(
    payload: DeviceSessionCreate,
    session: AsyncSession = Depends(get_session),
) -> DeviceSession:
    device_session = DeviceSession(
        session_id=payload.session_id,
        device_id=payload.device_id,
        ip_address=payload.ip_address,
        user_agent=payload.user_agent,
    )
    session.add(device_session)
    try:
        await session.commit()
        await session.refresh(device_session)
    except IntegrityError:
        await session.rollback()
        logger.exception("创建设备会话时发生完整性错误")
        raise HTTPException(status_code=422, detail="创建设备会话失败: 违反数据库约束")
    return device_session


@router.put("/api/device-sessions/{session_id}", response_model=DeviceSessionResponse)
async def disconnect_device_session(
    session_id: str = Path(..., min_length=1, max_length=36),
    session: AsyncSession = Depends(get_session),
) -> DeviceSession:
    device_session = await _get_device_session_or_404(session, session_id)
    device_session.disconnected_at = func.now()
    try:
        await session.commit()
        await session.refresh(device_session)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新设备会话 {session_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新设备会话失败: 违反数据库约束")
    return device_session


@router.get("/api/device-sessions", response_model=list[DeviceSessionResponse])
async def list_device_sessions(
    device_id: str | None = Query(None, description="按设备 ID 过滤"),
    start_date: datetime | None = Query(None, description="筛选在此日期之后连接的会话"),
    end_date: datetime | None = Query(None, description="筛选在此日期之前连接的会话"),
    limit: int = Query(100, ge=1, le=500, description="最大返回数量"),
    offset: int = Query(0, ge=0, description="跳过的结果数量"),
    session: AsyncSession = Depends(get_session),
) -> list[DeviceSession]:
    stmt = select(DeviceSession)
    if device_id is not None:
        stmt = stmt.where(DeviceSession.device_id == device_id)
    if start_date is not None:
        stmt = stmt.where(DeviceSession.connected_at >= start_date)
    if end_date is not None:
        stmt = stmt.where(DeviceSession.connected_at <= end_date)
    stmt = stmt.order_by(DeviceSession.connected_at.desc()).limit(limit).offset(offset)
    result = await session.execute(stmt)
    return list(result.scalars().all())
