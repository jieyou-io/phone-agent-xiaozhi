from __future__ import annotations

from typing import Any, Dict, List, Optional

from skills.base import Skill, SkillEffect, SkillResult
from skills.model_helpers import call_skill_model
from utils.validators import validate_model_config


# 安全限制：防止 DoS 攻击
MAX_SUB_SKILL_DEPTH = 3  # 最大递归深度
MAX_TOTAL_SUB_SKILL_CALLS = 10  # 单次任务最多调用次数


class GenericSkill(Skill):
    """通用用户自定义技能执行器"""

    def __init__(
        self,
        skill_id: str,
        name: str,
        description: str,
        system_prompt: str,
        icon: str | None = None,
        model_config: Dict[str, Any] | None = None,
        effects: List[Dict[str, Any]] | None = None,
        sub_skills: List[Dict[str, Any]] | None = None,
        db_skill_id: str | None = None,
    ):
        self.id = skill_id
        self.name = name
        self.description = description
        self.system_prompt = system_prompt
        self.icon = icon
        self.deletable = True
        self.model_config = model_config
        self.default_effects = self._coerce_effects(effects or [])
        self.sub_skills = self._normalize_sub_skills(sub_skills)
        self.db_skill_id = db_skill_id

    def _coerce_effects(self, raw: Any) -> list[SkillEffect]:
        """将原始 effects 数据转换为 SkillEffect 对象。"""
        if not raw:
            return []
        items = raw if isinstance(raw, list) else [raw]
        effects: list[SkillEffect] = []
        for item in items:
            if isinstance(item, SkillEffect):
                effects.append(item)
                continue
            if isinstance(item, dict):
                effect_type = item.get("type")
                if not effect_type:
                    continue
                payload = item.get("payload")
                if not isinstance(payload, dict):
                    payload = {}
                effects.append(SkillEffect(type=str(effect_type), payload=payload))
        return effects

    def _normalize_sub_skills(self, raw: Any) -> list[dict]:
        """标准化子技能定义。"""
        if not raw:
            return []
        items = raw if isinstance(raw, list) else [raw]
        normalized: list[dict] = []
        for index, item in enumerate(items):
            if not isinstance(item, dict):
                continue
            system_prompt = item.get("system_prompt")
            if not isinstance(system_prompt, str) or not system_prompt.strip():
                continue
            sub_id = item.get("id") or f"sub_{index}"
            normalized.append({
                "id": str(sub_id),
                "system_prompt": system_prompt,
                "model": item.get("model") or item.get("model_config"),
                "effects": item.get("effects"),
            })
        return normalized

    def _resolve_model_config(self, override: Any, fallback: Any) -> Optional[Dict[str, Any]]:
        """解析模型配置，优先使用 override，其次 fallback。"""
        if isinstance(override, dict):
            valid, _ = validate_model_config(override)
            if valid:
                return override
        if isinstance(fallback, dict):
            valid, _ = validate_model_config(fallback)
            if valid:
                return fallback
        return None

    def _execute_sub_skills(
        self,
        task: str,
        context: Dict[str, Any],
        depth: int = 0,
        call_count: Dict[str, int] = None
    ) -> tuple[list[SkillEffect], list[str]]:
        """递归执行子技能，带深度与调用次数限制。"""
        if call_count is None:
            call_count = {"count": 0}

        # 深度限制
        if depth >= MAX_SUB_SKILL_DEPTH:
            return [], []

        effects: list[SkillEffect] = []
        messages: list[str] = []

        for sub in self.sub_skills:
            # 调用次数限制
            if call_count["count"] >= MAX_TOTAL_SUB_SKILL_CALLS:
                break

            system_prompt = sub.get("system_prompt")
            if not system_prompt:
                continue

            # 解析子技能的模型配置
            model_config = self._resolve_model_config(
                sub.get("model"),
                context.get("model_config")
            )
            sub_context = {**context, "model_config": model_config}

            # 调用子技能模型
            call_count["count"] += 1
            try:
                parsed = call_skill_model(task, sub_context, system_prompt)
                if parsed:
                    text_value = parsed.get("text") or parsed.get("message") or parsed.get("response")
                    if isinstance(text_value, str) and text_value.strip():
                        messages.append(text_value.strip())
                    effects.extend(self._coerce_effects(parsed.get("effects")))
            except Exception:
                # 子技能失败不影响其他子技能
                continue

        return effects, messages

    def analyze(self, task: str, context: Dict[str, Any]) -> SkillResult:
        """执行用户自定义技能。"""
        message = "已执行用户技能。"
        effects = list(self.default_effects)

        # 执行主技能
        parsed = call_skill_model(task, context, self.system_prompt)

        if parsed:
            text_value = parsed.get("text") or parsed.get("message") or parsed.get("response")
            if isinstance(text_value, str) and text_value.strip():
                message = text_value.strip()
            effects.extend(self._coerce_effects(parsed.get("effects")))

        # 执行子技能
        if self.sub_skills:
            sub_effects, sub_messages = self._execute_sub_skills(task, context)
            effects.extend(sub_effects)
            if sub_messages:
                if message == "已执行用户技能。":
                    message = sub_messages[0]
                else:
                    message = f"{message} {' '.join(sub_messages)}"

        return SkillResult(message=message, effects=effects)
