from __future__ import annotations

import logging
from typing import List

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import Skill as SkillModel
from skills.generic import GenericSkill
from utils.validators import validate_model_config

logger = logging.getLogger(__name__)

_user_skills_cache: dict[str, list[GenericSkill]] = {}


async def load_user_skills(device_id: str | None, session: AsyncSession) -> List[GenericSkill]:
    """从数据库加载用户自定义技能"""
    if not device_id:
        return []

    if device_id in _user_skills_cache:
        logger.debug(f"设备 {device_id} 的用户技能命中缓存")
        return _user_skills_cache[device_id]

    stmt = (
        select(SkillModel)
        .where(
            SkillModel.owner_device_id == device_id,
            SkillModel.is_builtin.is_(False),
            SkillModel.is_active.is_(True),
        )
        .order_by(SkillModel.created_at.desc())
    )

    result = await session.execute(stmt)
    db_skills = result.scalars().all()

    user_skills = []
    for db_skill in db_skills:
        definition = db_skill.definition or {}
        system_prompt = definition.get("system_prompt")

        if not system_prompt:
            logger.warning(f"用户技能 {db_skill.id} 缺少 system_prompt，已跳过")
            continue

        model_config = definition.get("model") or definition.get("model_config")
        if model_config:
            valid, reason = validate_model_config(model_config)
            if not valid:
                logger.warning(f"用户技能 {db_skill.id} 的模型配置无效: {reason}")
                model_config = None

        effects = definition.get("effects") or []
        sub_skills = definition.get("skills") or []

        generic_skill = GenericSkill(
            skill_id=f"user:{db_skill.id}",
            name=db_skill.name,
            description=db_skill.description,
            system_prompt=system_prompt,
            icon=definition.get("icon"),
            model_config=model_config,
            effects=effects,
            sub_skills=sub_skills,
            db_skill_id=db_skill.id,
        )
        user_skills.append(generic_skill)

    _user_skills_cache[device_id] = user_skills
    logger.info(f"已为设备 {device_id} 加载 {len(user_skills)} 个用户技能")

    return user_skills


def clear_user_skills_cache(device_id: str | None = None):
    """清除用户技能缓存"""
    global _user_skills_cache
    if device_id:
        _user_skills_cache.pop(device_id, None)
        logger.info(f"已清除设备 {device_id} 的用户技能缓存")
    else:
        _user_skills_cache.clear()
        logger.info("已清除所有用户技能缓存")


def get_cached_user_skills(device_id: str | None) -> List[GenericSkill]:
    """获取缓存的用户技能（不触发数据库查询）"""
    if not device_id:
        return []
    return _user_skills_cache.get(device_id, [])
