from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from config.settings import settings
from db.connection import get_session
from db.models import User, Device
from db.redis_client import get_redis
from utils.auth import SESSION_COOKIE, SESSION_TTL_SECONDS, new_session_id, session_key
from utils.auth_dependency import get_current_user, _extract_session_id
from utils.security import hash_password, verify_password

router = APIRouter()

UUID_PATTERN = r"^[a-fA-F0-9\-]{36}$"


class LoginPayload(BaseModel):
    username: str
    password: str
    device_id: str | None = Field(None, min_length=36, max_length=36, pattern=UUID_PATTERN)


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    display_name: str | None
    email: str | None
    status: int


class LoginResponse(BaseModel):
    user: UserResponse
    device_registration_required: bool


class SessionStatusResponse(BaseModel):
    user: UserResponse
    ttl_seconds: int | None


class ChangePasswordPayload(BaseModel):
    old_password: str
    new_password: str
    confirm_password: str


class ChangePasswordResponse(BaseModel):
    ok: bool


@router.post("/api/auth/login", response_model=LoginResponse)
async def login(
    payload: LoginPayload,
    response: Response,
    session: AsyncSession = Depends(get_session),
) -> LoginResponse:
    """用户登录，创建 session 并返回 httpOnly cookie"""
    result = await session.execute(select(User).where(User.username == payload.username))
    user = result.scalar_one_or_none()

    try:
        if not user or not verify_password(payload.password, user.password_hash):
            raise HTTPException(status_code=401, detail="账号或密码错误")
    except Exception:
        raise HTTPException(status_code=401, detail="账号或密码错误")

    if user.status != 1:
        raise HTTPException(status_code=403, detail="用户已被禁用")

    device_registration_required = False
    if payload.device_id:
        try:
            device_result = await session.execute(
                select(Device).where(Device.device_id == payload.device_id)
            )
            device = device_result.scalar_one_or_none()
            if device:
                device.user_id = user.id
                device.status = 1
                device.last_seen = func.now()
            else:
                session.add(
                    Device(
                        device_id=payload.device_id,
                        user_id=user.id,
                        status=1,
                        last_seen=func.now(),
                    )
                )
            await session.commit()
        except IntegrityError:
            await session.rollback()
            raise HTTPException(status_code=500, detail="设备绑定失败")

    session_id = new_session_id()
    redis = get_redis()
    await redis.setex(session_key(session_id), SESSION_TTL_SECONDS, str(user.id))

    response.set_cookie(
        key=SESSION_COOKIE,
        value=session_id,
        httponly=True,
        samesite="lax",
        secure=settings.admin_session_secure_cookie,
        max_age=SESSION_TTL_SECONDS,
        path="/",
    )

    return LoginResponse(
        user=UserResponse(
            id=user.id,
            username=user.username,
            display_name=user.display_name,
            email=user.email,
            status=user.status,
        ),
        device_registration_required=device_registration_required,
    )


@router.post("/api/auth/logout")
async def logout(request: Request, response: Response) -> dict:
    """用户登出，删除 session 和 cookie"""
    session_id = _extract_session_id(request)
    if session_id:
        redis = get_redis()
        await redis.delete(session_key(session_id))
    response.delete_cookie(SESSION_COOKIE)
    return {"ok": True}


@router.get("/api/auth/me", response_model=UserResponse)
async def me(response: Response, user: User = Depends(get_current_user)) -> User:
    """获取当前登录用户信息"""
    response.headers["Cache-Control"] = "no-store"
    return user


@router.get("/api/auth/session", response_model=SessionStatusResponse)
async def session_status(request: Request, response: Response, user: User = Depends(get_current_user)) -> SessionStatusResponse:
    """返回当前会话信息（含 TTL），便于客户端判断过期"""
    response.headers["Cache-Control"] = "no-store"
    session_id = getattr(request.state, "session_id", None)
    ttl_seconds = None
    if session_id:
        redis = get_redis()
        ttl_value = await redis.ttl(session_key(session_id))
        if isinstance(ttl_value, int) and ttl_value > 0:
            ttl_seconds = ttl_value
    return SessionStatusResponse(
        user=UserResponse(
            id=user.id,
            username=user.username,
            display_name=user.display_name,
            email=user.email,
            status=user.status,
        ),
        ttl_seconds=ttl_seconds,
    )


@router.put("/api/auth/password", response_model=ChangePasswordResponse)
async def change_password(
    payload: ChangePasswordPayload,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> ChangePasswordResponse:
    """修改当前登录用户密码"""
    if not payload.old_password or not payload.new_password or not payload.confirm_password:
        raise HTTPException(status_code=400, detail="密码字段不能为空")

    try:
        if not verify_password(payload.old_password, user.password_hash):
            raise HTTPException(status_code=400, detail="旧密码不正确")
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=400, detail="旧密码不正确")

    if payload.new_password != payload.confirm_password:
        raise HTTPException(status_code=400, detail="两次输入的密码不一致")

    if payload.new_password == payload.old_password:
        raise HTTPException(status_code=400, detail="新密码不能与旧密码相同")

    user.password_hash = hash_password(payload.new_password)
    session.add(user)
    try:
        await session.commit()
    except Exception:
        await session.rollback()
        raise HTTPException(status_code=500, detail="更新密码失败")

    return ChangePasswordResponse(ok=True)
