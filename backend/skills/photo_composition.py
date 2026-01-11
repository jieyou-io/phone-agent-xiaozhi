from __future__ import annotations

from config.skill_prompts import PHOTO_COMPOSITION_COORDINATE_PROMPT, PHOTO_COMPOSITION_PROMPT
from skills.base import Skill, SkillEffect, SkillResult, SkillSchemaMetadata
from skills.model_helpers import call_skill_model
from skills.registry import registry


CONFIDENCE_THRESHOLD = 0.7  # 自动点击的置信度阈值


class PhotoCompositionSkill(Skill):
    id = "photo_composition"
    name = "构图大师"
    description = (
        "提供拍摄构图指导。适用于相机预览时的主体摆放、画面平衡、三分法/留白、"
        "横平竖直等构图优化建议。"
    )
    schema = SkillSchemaMetadata(
        input_schema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["task"],
            "properties": {
                "task": {"type": "string", "description": "构图指导请求"},
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
                    "minItems": 1,
                    "items": {
                        "type": "object",
                        "oneOf": [
                            {
                                "properties": {
                                    "type": {"const": "composition_hint"},
                                    "payload": {
                                        "type": "object",
                                        "required": ["region", "direction", "hint"],
                                    },
                                },
                            },
                            {
                                "properties": {
                                    "type": {"const": "composition_tap"},
                                    "payload": {
                                        "type": "object",
                                        "required": ["x_norm", "y_norm", "confidence"],
                                    },
                                },
                            },
                        ],
                    },
                },
            },
        },
        capabilities=["composition_guidance", "camera_assist"],
        version="1.0.0",
    )

    def analyze(self, task: str, context: dict) -> SkillResult:
        screenshot = context.get("screenshot")

        # 如果有截图，尝试坐标模式（自动选择）
        if screenshot:
            parsed = call_skill_model(task, context, PHOTO_COMPOSITION_COORDINATE_PROMPT)
            if parsed and all(k in parsed for k in ["x_norm", "y_norm", "confidence"]):
                x_norm = parsed.get("x_norm")
                y_norm = parsed.get("y_norm")
                confidence = parsed.get("confidence")

                # 验证数值范围
                if (
                    isinstance(x_norm, (int, float))
                    and isinstance(y_norm, (int, float))
                    and isinstance(confidence, (int, float))
                    and 0 <= x_norm <= 1
                    and 0 <= y_norm <= 1
                    and 0 <= confidence <= 1
                ):
                    # 高置信度：返回坐标用于自动点击
                    if confidence >= CONFIDENCE_THRESHOLD:
                        effects = [
                            SkillEffect(
                                type="composition_tap",
                                payload={
                                    "x_norm": float(x_norm),
                                    "y_norm": float(y_norm),
                                    "confidence": float(confidence),
                                    "rule": parsed.get("rule", "rule_of_thirds"),
                                    "note": parsed.get("note", "已识别出最佳主体位置"),
                                },
                            )
                        ]
                        return SkillResult(message="自动选点已准备就绪。", effects=effects)

        # 回退到静态提示模式（无截图或低置信度）
        region = "center"
        direction = "none"
        hint = "保持主体居中并保持画面水平。"
        parsed = call_skill_model(task, context, PHOTO_COMPOSITION_PROMPT)

        if parsed:
            region_value = parsed.get("region")
            if isinstance(region_value, str):
                region_value = region_value.lower()
                if region_value in {"center", "left", "right", "top", "bottom"}:
                    region = region_value

            direction_value = parsed.get("direction")
            if isinstance(direction_value, str):
                direction_value = direction_value.lower()
                if direction_value in {"up", "down", "left", "right", "none"}:
                    direction = direction_value

            hint_value = parsed.get("hint")
            if isinstance(hint_value, str) and hint_value.strip():
                hint = hint_value.strip()

        effects = [
            SkillEffect(
                type="composition_hint",
                payload={"region": region, "direction": direction, "hint": hint},
            )
        ]
        return SkillResult(message="构图指导已准备就绪。", effects=effects)


registry.register(PhotoCompositionSkill())
