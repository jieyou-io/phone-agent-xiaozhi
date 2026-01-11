from __future__ import annotations

from typing import Any, Dict, Optional
import json

from utils import model_client
from utils.validators import validate_model_config


def _extract_json(raw: str) -> Optional[Any]:
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


def call_skill_model(
    task: str,
    context: Dict[str, Any],
    system_prompt: str,
) -> Optional[Dict[str, Any]]:
    model_config = context.get("model_config")
    if not model_config:
        return None
    valid, _ = validate_model_config(model_config)
    if not valid:
        return None
    content = [{"type": "text", "text": task}]
    screenshot = context.get("screenshot")
    if screenshot:
        content.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{screenshot}"},
        })
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": content},
    ]
    response = model_client.chat_completions(
        base_url=model_config["base_url"],
        api_key=model_config["api_key"],
        model=model_config["model"],
        messages=messages,
    )
    raw = model_client.extract_content(response) or ""
    parsed = _extract_json(raw)
    return parsed if isinstance(parsed, dict) else None
