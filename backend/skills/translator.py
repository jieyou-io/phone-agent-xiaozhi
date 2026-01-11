from __future__ import annotations

import re

from config.skill_prompts import TRANSLATOR_PROMPT
from skills.base import Skill, SkillEffect, SkillResult, SkillSchemaMetadata
from skills.model_helpers import call_skill_model
from skills.registry import registry
from utils.image_utils import crop_base64_image


def _detect_language(text: str) -> str:
    """基于字符特征检测语言。"""
    if re.search(r"[\u4e00-\u9fff]", text):
        return "Chinese"
    if re.search(r"[A-Za-z]", text):
        return "English"
    return "Unknown"


def _infer_target_language(task: str, source_language: str) -> str:
    """根据任务或源语言推断目标语言。"""
    lowered = (task or "").lower()
    if "英文" in task or "英语" in task or "english" in lowered:
        return "English"
    if "中文" in task or "汉语" in task or "chinese" in lowered:
        return "Chinese"
    if source_language == "Chinese":
        return "English"
    if source_language == "English":
        return "Chinese"
    return "Chinese"


def _is_generic_request(task: str) -> bool:
    """判断是否为不含具体文本的泛化翻译请求。"""
    stripped = (task or "").strip()
    return stripped in {"翻译", "请翻译", "帮我翻译"}


class TranslatorSkill(Skill):
    id = "translator"
    name = "翻译"
    description = (
        "识别并翻译屏幕文字或用户输入。适用场景：外语应用界面、菜单/路牌/文档截图、"
        "跨语言沟通、学习翻译。支持中英互译及常见语种互译。"
    )
    schema = SkillSchemaMetadata(
        input_schema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["task"],
            "properties": {
                "task": {"type": "string", "description": "待翻译的文本或翻译请求"},
                "context": {
                    "type": "object",
                    "properties": {
                        "screenshot": {"type": ["string", "null"]},
                        "translation_region": {
                            "type": ["object", "null"],
                            "properties": {
                                "x": {"type": "number"},
                                "y": {"type": "number"},
                                "width": {"type": "number", "minimum": 1},
                                "height": {"type": "number", "minimum": 1},
                            },
                        },
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
                "effects": {"type": "array", "minItems": 1},
            },
        },
        capabilities=["translation", "language_detection", "ocr_assisted_translation"],
        version="1.0.0",
    )

    def analyze(self, task: str, context: dict) -> SkillResult:
        region = context.get("translation_region")
        screenshot = context.get("screenshot")
        needs_region = bool(screenshot) and not region and _is_generic_request(task)

        if needs_region:
            effects = [SkillEffect(type="translation_request", payload={})]
            return SkillResult(message="请选择要翻译的区域。", effects=effects)

        if screenshot and region:
            cropped = crop_base64_image(screenshot, region)
            if cropped:
                context = {**context, "screenshot": cropped}

        text = ""
        source_language = ""
        target_language = ""
        parsed = call_skill_model(task, context, TRANSLATOR_PROMPT)

        if parsed:
            parsed_text = parsed.get("text")
            if isinstance(parsed_text, str):
                text = parsed_text.strip()
            parsed_source = parsed.get("source_language")
            if isinstance(parsed_source, str):
                source_language = parsed_source.strip()
            parsed_target = parsed.get("target_language")
            if isinstance(parsed_target, str):
                target_language = parsed_target.strip()

        if not text:
            source_text = (task or "").strip()
            if not source_text:
                source_text = "未提供需要翻译的文本。"
            source_language = source_language or _detect_language(source_text)
            target_language = target_language or _infer_target_language(task, source_language)
            text = source_text

        effects = [
            SkillEffect(
                type="translation",
                payload={
                    "text": text,
                    "source_language": source_language,
                    "target_language": target_language,
                    "fallback": parsed is None,
                },
            )
        ]

        message = "翻译结果已准备好。"
        return SkillResult(message=message, effects=effects)


registry.register(TranslatorSkill())
