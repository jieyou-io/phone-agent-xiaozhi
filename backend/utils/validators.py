from __future__ import annotations

from typing import Any, Dict, List, Tuple


def validate_model_config(model: Dict[str, Any]) -> Tuple[bool, str]:
    if not model:
        return False, "缺少模型配置"
    if not model.get("base_url"):
        return False, "缺少 base_url"
    if not model.get("api_key"):
        return False, "缺少 api_key"
    if not model.get("model"):
        return False, "缺少 model"
    return True, ""


def validate_user_agents(
    user_agents: List[Dict[str, Any]],
    default_model: Dict[str, Any] | None = None,
) -> Tuple[bool, str]:
    for agent in user_agents:
        if not agent.get("name"):
            return False, "用户智能体缺少 name"
        if not agent.get("system_prompt"):
            return False, "用户智能体缺少 system_prompt"
        agent_model = agent.get("model")
        if agent_model:
            ok, msg = validate_model_config(agent_model)
            if not ok:
                return False, f"用户智能体模型无效：{msg}"
        skills = agent.get("skills", [])
        for skill in skills:
            if not skill.get("system_prompt"):
                return False, "用户技能缺少 system_prompt"
            skill_model = skill.get("model")
            if skill_model:
                ok, msg = validate_model_config(skill_model)
                if not ok:
                    return False, f"用户技能模型无效：{msg}"
            if not skill_model and not agent_model and default_model:
                ok, msg = validate_model_config(default_model)
                if not ok:
                    return False, f"默认模型无效：{msg}"
    return True, ""


def validate_action(action: Dict[str, Any]) -> Tuple[bool, str]:
    metadata = action.get("_metadata")
    if metadata not in {"do", "finish"}:
        return False, "无效的 _metadata"
    if metadata == "finish":
        return True, ""
    if metadata == "do" and not action.get("action"):
        return False, "缺少 action"
    return True, ""
