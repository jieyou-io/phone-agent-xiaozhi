from __future__ import annotations

import re

from config.skill_prompts import ANTI_SCAM_PROMPT
from skills.base import Skill, SkillEffect, SkillResult, SkillSchemaMetadata
from skills.model_helpers import call_skill_model
from skills.registry import registry


HIGH_RISK_KEYWORDS = (
    "转账", "汇款", "打款", "验证码", "otp", "verification code",
    "安全账户", "账号异常", "冻结", "解冻", "刷流水", "贷款",
    "中奖", "返利", "刷单", "客服", "链接", "二维码", "扫码",
    "公安", "检察院", "法院", "警察", "通缉", "退税", "退款",
    "返现", "投资", "博彩", "裸聊", "网贷", "征信", "银行卡",
    "信用卡", "密码", "remote control", "anydesk", "teamviewer",
)

MEDIUM_RISK_KEYWORDS = (
    "借钱", "朋友", "熟人", "兼职", "招聘", "群", "红包",
    "快递", "包裹", "补贴", "理赔", "客服热线", "异常登录", "钓鱼",
)


def _normalize_text(text: str) -> str:
    """移除空白并转为小写，便于关键词匹配。"""
    return re.sub(r"\s+", "", text.lower())


def _collect_hits(text: str, keywords: tuple[str, ...]) -> list[str]:
    """收集文本中出现的所有关键词。"""
    return [keyword for keyword in keywords if keyword and keyword in text]


class AntiScamSkill(Skill):
    id = "anti_scam"
    name = "防诈骗"
    description = (
        "检测短信、通知、聊天中诈骗风险。关键场景：转账/汇款/验证码、账号异常/冻结、"
        "中奖/退税/退款、冒充客服/公安/法院、刷单兼职、可疑链接/二维码、"
        "远程控制软件诱导等。任何涉及资金与账号安全的可疑内容都适用。"
    )
    schema = SkillSchemaMetadata(
        input_schema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["task"],
            "properties": {
                "task": {"type": "string", "description": "待分析的文本内容"},
                "context": {
                    "type": "object",
                    "properties": {
                        "screenshot": {"type": ["string", "null"]},
                        "model_config": {"type": ["object", "null"]},
                    },
                },
            },
        },
        output_schema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["message", "effects"],
            "properties": {
                "message": {"type": "string"},
                "effects": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "type": {"const": "alert"},
                            "payload": {
                                "type": "object",
                                "required": ["level", "intensity", "color", "duration_ms"],
                            },
                        },
                    },
                },
            },
        },
        capabilities=["risk_detection", "anti_scam_alert", "text_analysis"],
        version="1.0.0",
    )

    def analyze(self, task: str, context: dict) -> SkillResult:
        normalized = _normalize_text(task or "")
        signals = []
        signals.extend(_collect_hits(normalized, HIGH_RISK_KEYWORDS))
        signals.extend(_collect_hits(normalized, MEDIUM_RISK_KEYWORDS))

        risk_level = "low"
        if any(keyword in normalized for keyword in HIGH_RISK_KEYWORDS):
            risk_level = "high"
        elif signals:
            risk_level = "medium"

        message = ""
        parsed = call_skill_model(task, context, ANTI_SCAM_PROMPT)

        if parsed:
            level_value = parsed.get("risk_level")
            if isinstance(level_value, str) and level_value.lower() in {"low", "medium", "high"}:
                model_level = level_value.lower()
                level_rank = {"low": 0, "medium": 1, "high": 2}
                if level_rank.get(model_level, 0) > level_rank.get(risk_level, 0):
                    risk_level = model_level

            msg_value = parsed.get("message")
            if isinstance(msg_value, str) and msg_value.strip():
                message = msg_value.strip()

            model_signals = parsed.get("signals")
            if isinstance(model_signals, list):
                for item in model_signals:
                    if isinstance(item, str) and item.strip():
                        signals.append(item.strip())

        if not message:
            if risk_level == "high":
                message = "检测到高诈骗风险，请勿提供验证码或转账。"
            elif risk_level == "medium":
                message = "检测到疑似诈骗信号，操作前请先核实。"
            else:
                message = "未发现明显的诈骗信号。"

        unique_signals = []
        for signal in signals:
            if signal not in unique_signals:
                unique_signals.append(signal)

        if unique_signals:
            message = f"{message} 信号: {', '.join(unique_signals[:4])}。"

        intensity = "high" if risk_level == "high" else "medium"
        duration = 1600 if risk_level == "high" else 1000
        effects = []

        if risk_level in {"medium", "high"}:
            effects.append(
                SkillEffect(
                    type="alert",
                    payload={
                        "level": risk_level,
                        "intensity": intensity,
                        "color": "#FF3B30",
                        "duration_ms": duration,
                    },
                )
            )

        return SkillResult(message=message, effects=effects)


registry.register(AntiScamSkill())
