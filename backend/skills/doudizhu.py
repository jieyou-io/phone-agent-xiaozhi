from __future__ import annotations

from config.skill_prompts import DOUDIZHU_PROMPT
from skills.base import Skill, SkillEffect, SkillResult, SkillSchemaMetadata
from skills.model_helpers import call_skill_model
from skills.registry import registry


VALID_PLAY_TYPES = {"single", "pair", "triple", "sequence", "bomb", "rocket", "control", "support"}


class DoudizhuSkill(Skill):
    id = "doudizhu"
    name = "斗地主大师"
    description = (
        "分析斗地主牌局并给出出牌建议。适用于对局过程中的牌型判断、出牌时机、"
        "控牌与风险评估（地主/农民策略不同）。"
    )
    schema = SkillSchemaMetadata(
        input_schema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["task"],
            "properties": {
                "task": {"type": "string", "description": "牌局描述或手牌情况"},
                "context": {
                    "type": "object",
                    "properties": {
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
                    "minItems": 1,
                    "items": {
                        "type": "object",
                        "properties": {
                            "type": {"const": "doudizhu_suggestion"},
                            "payload": {
                                "type": "object",
                                "required": ["text", "play_type", "risk"],
                            },
                        },
                    },
                },
            },
        },
        capabilities=["game_advice", "doudizhu_strategy"],
        version="1.0.0",
    )

    def analyze(self, task: str, context: dict) -> SkillResult:
        suggestion = "建议出最小单牌。"
        play_type = "single"
        risk = "medium"
        parsed = call_skill_model(task, context, DOUDIZHU_PROMPT)

        if parsed:
            text_value = parsed.get("text")
            if isinstance(text_value, str) and text_value.strip():
                suggestion = text_value.strip()

            play_value = parsed.get("play_type")
            if isinstance(play_value, str) and play_value.strip():
                normalized_play = play_value.strip().lower()
                if normalized_play in VALID_PLAY_TYPES:
                    play_type = normalized_play

            risk_value = parsed.get("risk")
            if isinstance(risk_value, str) and risk_value.lower() in {"low", "medium", "high"}:
                risk = risk_value.lower()
        else:
            if "地主" in task:
                suggestion = "建议稳住牌权，优先处理对手可能的连牌。"
                play_type = "control"
                risk = "medium"
            elif "农民" in task:
                suggestion = "建议配合队友压制地主，优先消耗高牌。"
                play_type = "support"
                risk = "medium"

        effects = [
            SkillEffect(
                type="doudizhu_suggestion",
                payload={"text": suggestion, "play_type": play_type, "risk": risk},
            )
        ]
        return SkillResult(message="已生成出牌建议。", effects=effects)


registry.register(DoudizhuSkill())
