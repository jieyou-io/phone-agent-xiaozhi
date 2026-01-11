from __future__ import annotations

from typing import Any, Dict, List, Optional
import json
import logging
import re

from skills.registry import registry
from utils import model_client

logger = logging.getLogger(__name__)


TRANSLATOR_KEYWORDS = (
    "翻译", "翻成", "翻译成", "翻译一下", "译成", "变成", "改成", "英文", "英语",
    "日语", "韩语", "德语", "法语", "西班牙语", "英文版", "怎么说", "用英语怎么说",
    "怎么讲", "怎么读", "英文怎么写", "in english", "translate", "translation",
)
ANTI_SCAM_KEYWORDS = (
    "诈骗", "被骗", "骗钱", "反诈", "钓鱼", "冒充", "转账", "汇款", "验证码", "中奖",
    "刷单", "贷款", "冻结", "异常账户", "可疑链接", "二维码", "远程控制", "诈骗短信",
    "冒充客服", "刷流水", "刷流水任务", "刷流水兼职", "刷流水返利", "银行风控",
    "账户异常", "解封", "人脸认证", "安全验证", "fraud", "phishing", "fake support",
    "teamviewer", "anydesk",
)
DOUDIZHU_KEYWORDS = (
    "斗地主", "出牌", "牌型", "炸弹", "顺子", "飞机", "地主", "农民", "叫牌", "控牌",
    "叫地主", "抢地主", "提示出牌", "打牌", "牌局", "牌面", "牌权", "记牌",
)
PHOTO_COMPOSITION_KEYWORDS = (
    "构图", "拍照", "摄影", "相机预览", "相机构图", "相机取景", "取景", "画面", "主体",
    "三分法", "留白", "平衡", "取景框", "拍摄", "拍得更好", "主体居中", "构图建议",
    "构图指导", "三分构图", "对焦", "横平竖直", "composition",
)


def _normalize_text(text: str) -> str:
    """将文本统一为小写并去除空白/标点，用于关键词匹配。"""
    text = text.lower()
    text = re.sub(r"\s+", "", text)
    text = re.sub(r"[,。、!?！？;；:：]", "", text)
    return text


def _has_any(haystack: str, keywords: tuple[str, ...]) -> bool:
    normalized = _normalize_text(haystack)
    return any(keyword and _normalize_text(keyword) in normalized for keyword in keywords)


class PlannerAgent:
    def __init__(self) -> None:
        pass

    def _get_all_skill_ids(self, user_skills: List[Any] = None) -> set[str]:
        """动态获取所有技能ID（包括运行时注册的用户技能）"""
        skill_ids = {skill.id for skill in registry.all()}
        if user_skills:
            skill_ids.update(skill.id for skill in user_skills)
        return skill_ids

    def select_skills(self, task: str, user_skills: List[Any] = None) -> List[str]:
        lowered = task.lower()
        selected: List[str] = []
        all_skill_ids = self._get_all_skill_ids(user_skills)

        # 内置技能关键词匹配
        if _has_any(lowered, TRANSLATOR_KEYWORDS):
            if "translator" in all_skill_ids:
                selected.append("translator")
        if _has_any(lowered, ANTI_SCAM_KEYWORDS) or "scam" in lowered:
            if "anti_scam" in all_skill_ids:
                selected.append("anti_scam")
        if _has_any(lowered, DOUDIZHU_KEYWORDS) or "doudizhu" in lowered:
            if "doudizhu" in all_skill_ids:
                selected.append("doudizhu")
        if _has_any(lowered, PHOTO_COMPOSITION_KEYWORDS):
            if "photo_composition" in all_skill_ids:
                selected.append("photo_composition")

        # 用户技能名称匹配
        if user_skills:
            for skill in user_skills:
                if skill.id not in selected:
                    skill_name_normalized = _normalize_text(skill.name)
                    if skill_name_normalized in _normalize_text(lowered):
                        selected.append(skill.id)

        return selected

    def select_user_agent(self, task: str, user_agents: List[dict]) -> Optional[dict]:
        if not user_agents:
            return None
        lowered = task.lower()
        for agent in user_agents:
            name = str(agent.get("name", "")).lower()
            if name and name in lowered:
                return agent
            agent_id = str(agent.get("id", "")).lower()
            if agent_id and agent_id in lowered:
                return agent
        return None

    def plan(self, task: str) -> List[str]:
        return [f"分析任务: {task}", "执行已选技能", "汇报动作"]

    def run(
        self,
        task: str,
        user_agents: Optional[List[dict]] = None,
        model_config: Optional[Dict[str, Any]] = None,
        user_skills: Optional[List[Any]] = None,
    ) -> Dict[str, Any]:
        user_agents = user_agents or []
        user_skills = user_skills or []
        if model_config and model_config.get("base_url") and model_config.get("api_key") and model_config.get("model"):
            logger.info(f"Planner 使用模型规划: {model_config.get('model')} @ {model_config.get('base_url')}")
            selected = self._run_with_model(task, user_agents, model_config, user_skills)
            if selected:
                logger.info(f"Planner 模型规划成功, 选择技能: {selected.get('skills')}, 智能体: {selected.get('agent', {}).get('id') if selected.get('agent') else None}")
                return selected
            logger.warning("Planner 模型规划失败 (JSON 解析失败), 回退到关键词匹配")
        else:
            logger.info("Planner 未配置模型或配置不完整, 使用关键词匹配")

        selected_agent = self.select_user_agent(task, user_agents)
        selected_skills = self.select_skills(task, user_skills)
        logger.info(f"Planner 关键词匹配结果: 技能={selected_skills}, 智能体={selected_agent.get('id') if selected_agent else None}")
        return {
            "plan": self.plan(task),
            "skills": selected_skills,
            "agent": selected_agent,
        }

    def _run_with_model(
        self,
        task: str,
        user_agents: List[dict],
        model_config: Dict[str, Any],
        user_skills: List[Any],
    ) -> Optional[Dict[str, Any]]:
        # 合并内置技能和用户技能
        all_skills = list(registry.all())
        if user_skills:
            all_skills.extend(user_skills)

        all_skills_data = sorted(
            [
                {"id": skill.id, "name": skill.name, "description": skill.description}
                for skill in all_skills
            ],
            key=lambda x: x["id"],
        )
        agent_choices = [
            {"id": agent.get("id"), "name": agent.get("name")}
            for agent in user_agents
        ]
        system_prompt = (
            "你是一个规划器。选择相关技能（内置或用户自定义），并可选选择一个用户智能体。"
            "请根据技能描述做出判断。"
            "如果没有适用技能，请返回空的 skills 数组。"
            "仅返回 JSON：{\"skills\": [\"skill_id\"]}。"
            f"可用技能：{json.dumps(all_skills_data, ensure_ascii=False)}。"
            f"用户智能体：{json.dumps(agent_choices, ensure_ascii=False)}。"
        )
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": task},
        ]
        logger.debug(f"Planner 调用模型, 可用技能数={len(all_skills_data)}, 用户智能体数={len(agent_choices)}")
        response = model_client.chat_completions(
            base_url=model_config["base_url"],
            api_key=model_config["api_key"],
            model=model_config["model"],
            messages=messages,
        )
        raw = model_client.extract_content(response) or ""
        logger.debug(f"Planner 模型原始响应 (前500字符): {raw[:500] if raw else '(空)'}")

        parsed = self._extract_json(raw)
        if not isinstance(parsed, dict):
            safe_preview = re.sub(r"\s+", " ", raw)[:200] if raw else "(空响应)"
            logger.warning(
                "Planner 模型返回的不是有效 JSON，回退到关键词匹配。"
                "模型: %s, base_url: %s, 响应长度: %d, 响应前200字符: %s",
                model_config.get("model"),
                model_config.get("base_url"),
                len(raw),
                safe_preview
            )
            return None

        logger.info(f"Planner 模型 JSON 解析成功: {json.dumps(parsed, ensure_ascii=False)}")

        skills = parsed.get("skills") or []
        if not isinstance(skills, list):
            logger.warning(f"Planner 模型返回的 skills 不是列表: {type(skills)}, 使用空列表")
            skills = []
        agent_id = parsed.get("agent_id") or parsed.get("agent_name")
        selected_agent = None
        if agent_id:
            lowered = str(agent_id).lower()
            for agent in user_agents:
                if str(agent.get("id", "")).lower() == lowered or str(agent.get("name", "")).lower() == lowered:
                    selected_agent = agent
                    break

        all_skill_ids = self._get_all_skill_ids(user_skills)
        valid_skills = [s for s in skills if s in all_skill_ids]
        if len(valid_skills) < len(skills):
            invalid = [s for s in skills if s not in all_skill_ids]
            logger.warning(f"Planner 模型返回了无效技能 ID: {invalid}, 已过滤")

        return {
            "plan": self.plan(task),
            "skills": valid_skills,
            "agent": selected_agent,
        }

    def _extract_json(self, raw: str) -> Optional[Any]:
        raw = raw.strip()
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            pass
        start = raw.find("{")
        end = raw.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return None
        try:
            return json.loads(raw[start : end + 1])
        except json.JSONDecodeError:
            return None
