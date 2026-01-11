from __future__ import annotations

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.connection import get_session
from db.models import User
from db.redis_client import get_redis
from utils.auth import SESSION_COOKIE, session_key


def _extract_session_id(request: Request) -> str | None:
    cookie_session = request.cookies.get(SESSION_COOKIE)
    if cookie_session:
        return cookie_session

    header_session = request.headers.get("x-admin-session") or request.headers.get("x-session-id")
    if header_session:
        return header_session.strip()

    auth_header = request.headers.get("authorization")
    if not auth_header:
        return None
    auth_header = auth_header.strip()
    if auth_header.lower().startswith("bearer "):
        return auth_header[7:].strip()
    if auth_header.lower().startswith("session "):
        return auth_header[8:].strip()
    return None


async def get_current_user(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> User:
    """认证依赖：从 cookie 或 header 中获取 session，验证并返回当前用户"""
    session_id = _extract_session_id(request)
    if not session_id:
        raise HTTPException(status_code=401, detail="未认证")

    redis = get_redis()
    user_id = await redis.get(session_key(session_id))
    if not user_id:
        raise HTTPException(status_code=401, detail="无效会话")

    try:
        user_id_int = int(user_id)
    except (ValueError, TypeError):
        await redis.delete(session_key(session_id))
        raise HTTPException(status_code=401, detail="无效会话")

    result = await session.execute(select(User).where(User.id == user_id_int))
    user = result.scalar_one_or_none()
    if not user or user.status != 1:
        raise HTTPException(status_code=401, detail="无效会话")

    request.state.session_id = session_id
    return user
