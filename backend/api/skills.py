from __future__ import annotations

import logging
from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import Skill
from skills.user_loader import clear_user_skills_cache

logger = logging.getLogger(__name__)
router = APIRouter()


class SkillBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=128)
    description: str = Field(..., min_length=1)
    definition: dict[str, Any]


class SkillCreate(SkillBase):
    owner_device_id: str | None = Field(None, min_length=1, max_length=36, description="设备 ID，留空表示全局技能")


class SkillUpdate(BaseModel):
    name: str | None = Field(None, min_length=1, max_length=128)
    description: str | None = Field(None, min_length=1)
    definition: dict[str, Any] | None = None
    is_active: bool | None = None


class SkillResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    skill_id: str
    owner_device_id: str | None
    name: str
    description: str
    definition: dict[str, Any]
    is_builtin: bool
    is_active: bool
    created_at: Any
    updated_at: Any | None


async def _get_skill_or_404(session: AsyncSession, skill_id: str) -> Skill:
    result = await session.execute(select(Skill).where(Skill.skill_id == skill_id))
    skill = result.scalar_one_or_none()
    if not skill:
        raise HTTPException(status_code=404, detail="未找到技能")
    return skill


def _ensure_owner_can_modify(skill: Skill, device_id: str) -> None:
    if skill.is_builtin:
        raise HTTPException(status_code=403, detail="不能修改内置技能")
    if skill.owner_device_id != device_id:
        raise HTTPException(status_code=403, detail="权限不足: 不是技能所有者")


@router.get("/api/skills", response_model=list[SkillResponse])
async def get_skills(
    device_id: str | None = Query(None, description="设备 ID，留空返回所有技能"),
    session: AsyncSession = Depends(get_session),
) -> list[Skill]:
    """
    获取技能列表
    - 如果提供 device_id：返回内置技能 + 该设备的自定义技能
    - 如果不提供 device_id：返回所有技能（管理后台使用）
    """
    if device_id:
        # 移动端调用：返回内置技能 + 该设备的技能
        stmt = (
            select(Skill)
            .where(
                Skill.is_active.is_(True),
                or_(Skill.is_builtin.is_(True), Skill.owner_device_id == device_id),
            )
            .order_by(Skill.is_builtin.desc(), Skill.created_at.desc())
        )
    else:
        # 管理后台调用：返回所有技能
        stmt = select(Skill).order_by(Skill.is_builtin.desc(), Skill.created_at.desc())

    result = await session.execute(stmt)
    return list(result.scalars().all())


@router.post("/api/skills", response_model=SkillResponse, status_code=201)
async def create_skill(
    payload: SkillCreate,
    session: AsyncSession = Depends(get_session),
) -> Skill:
    skill = Skill(
        skill_id=str(uuid4()),
        owner_device_id=payload.owner_device_id,
        name=payload.name,
        description=payload.description,
        definition=payload.definition,
        is_builtin=False,
        is_active=True,
    )
    session.add(skill)
    try:
        await session.commit()
        await session.refresh(skill)
    except IntegrityError:
        await session.rollback()
        logger.exception("创建技能时发生完整性错误")
        raise HTTPException(status_code=422, detail="创建技能失败: 违反数据库约束")

    # 清除设备或全局缓存
    if skill.owner_device_id:
        clear_user_skills_cache(skill.owner_device_id)
    else:
        clear_user_skills_cache()

    return skill


@router.put("/api/skills/{skill_id}", response_model=SkillResponse)
async def update_skill(
    skill_id: str,
    payload: SkillUpdate,
    device_id: str = Query(..., description="用于权限校验的设备 ID"),
    session: AsyncSession = Depends(get_session),
) -> Skill:
    updates = payload.model_dump(exclude_none=True)
    if not updates:
        raise HTTPException(status_code=400, detail="没有需要更新的字段")

    skill = await _get_skill_or_404(session, skill_id)
    _ensure_owner_can_modify(skill, device_id)

    for key, value in updates.items():
        setattr(skill, key, value)

    try:
        await session.commit()
        await session.refresh(skill)
    except IntegrityError:
        await session.rollback()
        logger.exception(f"更新技能 {skill_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="更新技能失败: 违反数据库约束")

    # 清除技能所有者缓存
    clear_user_skills_cache(skill.owner_device_id or device_id)

    return skill


@router.delete("/api/skills/{skill_id}", status_code=200)
async def delete_skill(
    skill_id: str,
    device_id: str = Query(..., description="用于权限校验的设备 ID"),
    session: AsyncSession = Depends(get_session),
) -> dict:
    skill = await _get_skill_or_404(session, skill_id)
    _ensure_owner_can_modify(skill, device_id)

    skill.is_active = False
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        logger.exception(f"删除技能 {skill_id} 时发生完整性错误")
        raise HTTPException(status_code=422, detail="删除技能失败: 违反数据库约束")

    # 清除技能所有者缓存
    clear_user_skills_cache(skill.owner_device_id or device_id)

    return {"status": "deleted", "skill_id": skill_id}
