from __future__ import annotations

import json
import logging
import urllib.request
import urllib.error
from typing import Any, Dict, List, Optional


logger = logging.getLogger(__name__)


def _redact(value: Any, key: str | None = None) -> Any:
    if isinstance(value, dict):
        redacted: Dict[str, Any] = {}
        for entry_key, entry_value in value.items():
            if entry_key.lower() in {"api_key", "apikey", "authorization"}:
                redacted[entry_key] = "***"
            else:
                redacted[entry_key] = _redact(entry_value, key=entry_key)
        return redacted
    if isinstance(value, list):
        return [_redact(item) for item in value]
    if isinstance(value, str):
        lowered_key = (key or "").lower()
        if lowered_key in {"screenshot", "image", "image_base64"} and value:
            return f"<已省略图片 {len(value)} 个字符>"
        if value.startswith("data:image"):
            return f"<已省略图片 {len(value)} 个字符>"
    return value


def _log_json(prefix: str, payload: Dict[str, Any]) -> None:
    safe_payload = _redact(payload)
    logger.info("%s %s", prefix, json.dumps(safe_payload, ensure_ascii=False))


def _normalize_base_url(base_url: str) -> str:
    if base_url.endswith("/v1"):
        return base_url
    if base_url.endswith("/"):
        return base_url + "v1"
    return base_url + "/v1"

def _is_bigmodel(base_url: str) -> bool:
    return "open.bigmodel.cn" in base_url

def _build_url(base_url: str) -> str:
    if _is_bigmodel(base_url):
        base = base_url.rstrip("/")
        return base + "/chat/completions"
    return _normalize_base_url(base_url) + "/chat/completions"

def chat_completions(
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: List[Dict[str, Any]],
    timeout: int = 60,
) -> Dict[str, Any]:
    url = _build_url(base_url)
    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0.2,
    }

    _log_json("模型请求：", {"url": url, "payload": payload})

    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", f"Bearer {api_key}")

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8") if exc.fp else ""
        if body:
            _log_json("模型错误：", {"status": exc.code, "body": body})
        raise

    result = json.loads(body)
    _log_json("模型响应：", result)
    return result


def extract_content(response: Dict[str, Any]) -> Optional[str]:
    try:
        return response["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        return None
