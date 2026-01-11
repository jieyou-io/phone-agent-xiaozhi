from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)

try:
    import jsonschema
except ImportError:
    jsonschema = None

SCHEMA_DRAFT = "http://json-schema.org/draft-07/schema#"

EFFECT_TYPE_REGISTRY: dict[str, dict[str, Any]] = {
    "alert": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "required": ["level", "intensity", "color", "duration_ms"],
        "properties": {
            "level": {"type": "string", "enum": ["low", "medium", "high"]},
            "intensity": {"type": "string", "enum": ["low", "medium", "high"]},
            "color": {"type": "string", "pattern": "^#[0-9A-Fa-f]{6}$"},
            "duration_ms": {"type": "integer", "minimum": 0},
        },
        "additionalProperties": False,
    },
    "translation": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "required": ["text", "source_language", "target_language"],
        "properties": {
            "text": {"type": "string"},
            "source_language": {"type": "string"},
            "target_language": {"type": "string"},
            "fallback": {"type": "boolean"},
        },
        "additionalProperties": False,
    },
    "translation_request": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "properties": {},
        "additionalProperties": False,
    },
    "composition_hint": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "required": ["region", "direction", "hint"],
        "properties": {
            "region": {"type": "string", "enum": ["center", "left", "right", "top", "bottom"]},
            "direction": {"type": "string", "enum": ["up", "down", "left", "right", "none"]},
            "hint": {"type": "string"},
        },
        "additionalProperties": False,
    },
    "doudizhu_suggestion": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "required": ["text", "play_type", "risk"],
        "properties": {
            "text": {"type": "string"},
            "play_type": {
                "type": "string",
                "enum": ["single", "pair", "triple", "sequence", "bomb", "rocket", "control", "support"],
            },
            "risk": {"type": "string", "enum": ["low", "medium", "high"]},
        },
        "additionalProperties": False,
    },
    "composition_tap": {
        "$schema": SCHEMA_DRAFT,
        "type": "object",
        "required": ["x_norm", "y_norm", "confidence"],
        "properties": {
            "x_norm": {"type": "number", "minimum": 0, "maximum": 1},
            "y_norm": {"type": "number", "minimum": 0, "maximum": 1},
            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
            "rule": {"type": "string"},
            "note": {"type": "string"},
        },
        "additionalProperties": False,
    },
}


def validate_effect(effect_type: str, payload: dict[str, Any]) -> tuple[bool, str]:
    """
    校验 effect payload 是否符合已注册的架构定义。

    返回值:
        (is_valid, error_message): is_valid 为 True 表示校验通过，
                                   error_message 在失败时包含原因。
    """
    schema = EFFECT_TYPE_REGISTRY.get(effect_type)
    if not schema:
        return False, f"未知的 effect 类型: {effect_type}"

    if jsonschema is None:
        logger.warning("未安装 jsonschema，跳过严格校验")
        if not isinstance(payload, dict):
            return False, "payload 必须是对象"
        required = schema.get("required", [])
        for field in required:
            if field not in payload:
                return False, f"缺少必填字段: {field}"
        return True, ""

    try:
        jsonschema.validate(instance=payload, schema=schema)
        return True, ""
    except jsonschema.ValidationError as e:
        return False, f"架构校验失败: {e.message}"
    except jsonschema.SchemaError as e:
        return False, f"架构定义无效: {e.message}"


def validate_effects(effects: list[dict[str, Any]]) -> tuple[bool, list[str]]:
    """
    校验 effects 列表。

    返回值:
        (all_valid, error_messages): all_valid 为 True 表示全部通过，
                                     error_messages 包含所有校验错误。
    """
    errors: list[str] = []
    for i, effect in enumerate(effects):
        effect_type = effect.get("type")
        if not effect_type:
            errors.append(f"效果 {i}: 缺少 'type' 字段")
            continue

        payload = effect.get("payload", {})
        is_valid, error_msg = validate_effect(effect_type, payload)
        if not is_valid:
            errors.append(f"效果 {i}（{effect_type}）：{error_msg}")

    return len(errors) == 0, errors
