from __future__ import annotations

import json
import logging
from typing import Any, Dict, List

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import ModelConfig, Skill as SkillModel
from skills.base import Skill
from skills.generic import GenericSkill
from skills.registry import registry

logger = logging.getLogger(__name__)


async def _load_model_config(
    owner_device_id: str | None,
    skill_id: str | None,
    session: AsyncSession
) -> Dict[str, Any] | None:
    """
    加载模型配置（三级优先级：skill > device > global）

    Args:
        owner_device_id: 设备ID，NULL 表示全局
        skill_id: 技能ID，NULL 表示默认
        session: 数据库会话

    Returns:
        模型配置字典，如果没有找到则返回 None
    """
    # 1. 尝试查询技能专用配置 (owner_device_id, skill_id)
    if owner_device_id is not None and skill_id is not None:
        result = await session.execute(
            select(ModelConfig).where(
                ModelConfig.owner_device_id == owner_device_id,
                ModelConfig.skill_id == skill_id
            )
        )
        config = result.scalar_one_or_none()
        if config:
            logger.debug(f"找到技能专用配置: device={owner_device_id}, skill={skill_id}")
            return _format_model_config(config)

    # 2. 尝试查询设备默认配置 (owner_device_id, skill_id=NULL)
    if owner_device_id is not None:
        result = await session.execute(
            select(ModelConfig).where(
                ModelConfig.owner_device_id == owner_device_id,
                ModelConfig.skill_id.is_(None)
            )
        )
        config = result.scalar_one_or_none()
        if config:
            logger.debug(f"找到设备默认配置: device={owner_device_id}")
            return _format_model_config(config)

    # 3. 查询全局默认配置 (owner_device_id=NULL, skill_id=NULL)
    result = await session.execute(
        select(ModelConfig).where(
            ModelConfig.owner_device_id.is_(None),
            ModelConfig.skill_id.is_(None)
        )
    )
    config = result.scalar_one_or_none()
    if config:
        logger.debug("找到全局默认配置")
        return _format_model_config(config)

    logger.warning(f"未找到模型配置: device={owner_device_id}, skill={skill_id}")
    return None


def _format_model_config(config: ModelConfig) -> Dict[str, Any]:
    """格式化模型配置为字典"""
    return {
        "provider": config.provider,
        "base_url": config.base_url,
        "api_key": config.api_key,
        "model": config.model,
        "config": json.loads(config.config) if isinstance(config.config, str) else config.config,
    }


async def _resolve_skill_config(
    skill: SkillModel,
    session: AsyncSession,
    visited: set[str] | None = None
) -> Dict[str, Any]:
    """递归解析技能配置（支持继承）"""
    if visited is None:
        visited = set()

    # 防止循环引用
    if skill.skill_id in visited:
        logger.warning(f"检测到技能循环继承: {skill.skill_id}")
        return {}
    visited.add(skill.skill_id)

    # 解析定义（JSON）
    definition = skill.definition
    if isinstance(definition, str):
        definition = json.loads(definition)

    # 如果有父技能，递归获取父配置
    parent_config = {}
    if skill.parent_skill_id:
        result = await session.execute(
            select(SkillModel).where(
                SkillModel.skill_id == skill.parent_skill_id,
                SkillModel.is_builtin == True,
                SkillModel.is_active == True
            )
        )
        parent_skill = result.scalar_one_or_none()
        if parent_skill:
            parent_config = await _resolve_skill_config(parent_skill, session, visited)
        else:
            logger.warning(f"技能 {skill.skill_id} 的父技能 {skill.parent_skill_id} 不存在")

    # 合并配置（子覆盖父）
    merged_definition = {**parent_config.get("definition", {}), **definition}

    # 模型配置（优先级：当前技能 > 父技能 > 全局默认）
    model_config = await _load_model_config(skill.owner_device_id, skill.skill_id, session)
    if not model_config and parent_config.get("model_config"):
        model_config = parent_config["model_config"]

    return {
        "skill_id": skill.skill_id,
        "name": skill.name,
        "description": skill.description,
        "definition": merged_definition,
        "model_config": model_config,
        "is_builtin": skill.is_builtin,
    }


async def load_builtin_skills() -> List[Skill]:
    """从数据库加载所有内置技能"""
    builtin_skills: List[Skill] = []

    async for session in get_session():
        # 查询所有激活的内置技能
        result = await session.execute(
            select(SkillModel).where(
                SkillModel.is_builtin == True,
                SkillModel.is_active == True,
                SkillModel.owner_device_id.is_(None)  # 全局内置技能
            ).order_by(SkillModel.skill_id)
        )
        skills = result.scalars().all()

        if not skills:
            logger.warning("数据库中没有内置技能，请先执行初始化 SQL")
            break

        logger.info(f"从数据库加载了 {len(skills)} 个内置技能")

        # 解析每个技能
        for skill_row in skills:
            try:
                config = await _resolve_skill_config(skill_row, session)

                # 提取配置
                skill_id = config["skill_id"]
                name = config["name"]
                description = config["description"]
                definition = config["definition"]

                system_prompt = definition.get("system_prompt", "")
                effects = definition.get("effects", [])
                sub_skills = definition.get("sub_skills", [])

                # 创建 GenericSkill 实例
                skill = GenericSkill(
                    skill_id=skill_id,
                    name=name,
                    description=description,
                    system_prompt=system_prompt,
                    effects=effects,
                    sub_skills=sub_skills,
                )
                skill.deletable = False  # 内置技能不可删除

                builtin_skills.append(skill)
                logger.debug(f"加载内置技能: {skill_id} ({name})")

            except Exception as e:
                logger.exception(f"加载内置技能 {skill_row.skill_id} 失败: {e}")

        break

    return builtin_skills


async def register_builtin_skills() -> None:
    """加载并注册所有内置技能到全局 registry"""
    skills = await load_builtin_skills()

    for skill in skills:
        registry.register(skill)
        logger.info(f"注册内置技能: {skill.id} - {skill.name}")

    if not skills:
        logger.error("未能加载任何内置技能！请检查数据库初始化")
    else:
        logger.info(f"成功注册 {len(skills)} 个内置技能")
