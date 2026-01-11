from __future__ import annotations

import secrets

from config.settings import settings

SESSION_COOKIE = "admin_session"
SESSION_TTL_SECONDS = settings.admin_session_ttl_seconds


def new_session_id() -> str:
    """生成安全的随机 session ID"""
    return secrets.token_urlsafe(32)


def session_key(session_id: str) -> str:
    """构建 Redis session key"""
    return f"admin:session:{session_id}"
