from __future__ import annotations

from typing import Any, Dict
import json
import logging
from time import perf_counter

from fastapi import WebSocket
from sqlalchemy import func, select, or_
from sqlalchemy.exc import IntegrityError, SQLAlchemyError

from agents.graph import run_task
from db.connection import get_session
from db.models import Device, DeviceSession, ModelConfig, SkillInvocation, UsageLog
from skills.generic import GenericSkill
from skills.user_loader import load_user_skills
from utils.validators import validate_model_config
from utils.session_store import plan_cache
from websocket.connection_manager import ConnectionManager

logger = logging.getLogger(__name__)

BUILTIN_SKILL_IDS = ("default", "translator", "anti_scam", "doudizhu", "photo_composition")
MANAGER_SKILL_ID = "manager"


def _redact(value: Any, key: str | None = None) -> Any:
    if isinstance(value, dict):
        redacted: Dict[str, Any] = {}
        for entry_key, entry_value in value.items():
            if entry_key.lower() in {"api_key", "apikey"}:
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


def _extract_client_meta(websocket: WebSocket) -> tuple[str | None, str | None]:
    ip_address = websocket.client.host if websocket.client else None
    user_agent = websocket.headers.get("user-agent") if websocket.headers else None
    return ip_address, user_agent


def _resolve_skill_id(result: Dict[str, Any] | None, payload: Dict[str, Any]) -> str | None:
    if result:
        skill_id = result.get("skill_id")
        if skill_id:
            return str(skill_id)
        selected_skills = result.get("selected_skills") or result.get("skills") or []
        if isinstance(selected_skills, list) and selected_skills:
            return str(selected_skills[0])
    payload_skill = payload.get("skill_id")
    if payload_skill:
        return str(payload_skill)
    return None


def _model_config_to_dict(config: ModelConfig) -> Dict[str, Any]:
    """将 ModelConfig ORM 对象转换为字典"""
    return {
        "provider": config.provider,
        "base_url": config.base_url,
        "api_key": config.api_key,
        "model": config.model,
        "config": config.config,
    }


async def _load_builtin_models(device_id: str) -> tuple[Dict[str, Any], Dict[str, Any] | None]:
    """加载设备的内置技能模型配置（从数据库）"""
    if not device_id:
        return {}, None

    stmt = (
        select(ModelConfig)
        .where(
            ModelConfig.owner_device_id == device_id,
            or_(ModelConfig.skill_id.in_(BUILTIN_SKILL_IDS), ModelConfig.skill_id.is_(None)),
        )
        .order_by(ModelConfig.updated_at.desc())
    )

    async for session in get_session():
        result = await session.execute(stmt)
        rows = result.scalars().all()
        break

    db_skill_models: Dict[str, Any] = {}
    db_default: Dict[str, Any] | None = None

    for row in rows:
        if row.skill_id is None:
            if db_default is None:
                db_default = _model_config_to_dict(row)
        else:
            skill_id_str = str(row.skill_id)
            if skill_id_str not in db_skill_models:
                db_skill_models[skill_id_str] = _model_config_to_dict(row)

    return db_skill_models, db_default


async def _load_manager_model(device_id: str) -> Dict[str, Any] | None:
    """加载设备的规划模型配置（manager_model）"""
    if not device_id:
        return None

    stmt = (
        select(ModelConfig)
        .where(
            ModelConfig.owner_device_id == device_id,
            ModelConfig.skill_id == MANAGER_SKILL_ID,
        )
        .order_by(ModelConfig.updated_at.desc())
    )

    async for session in get_session():
        result = await session.execute(stmt)
        row = result.scalars().first()
        break

    if not row:
        return None
    return _model_config_to_dict(row)



async def _create_usage_log(
    websocket: WebSocket,
    payload: Dict[str, Any],
    result: Dict[str, Any] | None,
    status: int,
    execution_ms: int,
) -> None:
    device_id = getattr(websocket.state, "device_id", None)
    if not device_id:
        return
    skill_id = _resolve_skill_id(result, payload)
    if not skill_id:
        logger.warning("使用日志已跳过：设备 %s 缺少 skill_id", device_id)
        return
    async for session in get_session():
        usage_log = UsageLog(
            device_id=device_id,
            skill_id=skill_id,
            status=status,
            task_text=payload.get("task"),
            execution_ms=execution_ms,
        )
        session.add(usage_log)
        try:
            await session.commit()
        except IntegrityError:
            await session.rollback()
            logger.exception("创建使用日志时发生完整性错误")
        except SQLAlchemyError:
            await session.rollback()
            logger.exception("创建使用日志失败")
        break


async def _create_skill_invocation_logs(
    websocket: WebSocket,
    payload: Dict[str, Any],
    skill_timings: list[Dict[str, Any]],
) -> None:
    """记录每个技能的调用日志（用于执行耗时和频率统计）"""
    device_id = getattr(websocket.state, "device_id", None)
    if not device_id or not skill_timings:
        return

    task_text = payload.get("task")
    async for session in get_session():
        for timing in skill_timings:
            skill_id = timing.get("skill_id")
            if not skill_id:
                continue

            execution_ms = timing.get("execution_ms")
            status = timing.get("status")
            if status is None:
                status = 1
            elif status == "success":
                status = 1
            elif status == "failure":
                status = 0

            session.add(
                SkillInvocation(
                    device_id=device_id,
                    skill_id=str(skill_id),
                    status=status,
                    task_text=task_text,
                    execution_ms=execution_ms if isinstance(execution_ms, int) else None,
                )
            )

        try:
            await session.commit()
        except IntegrityError:
            await session.rollback()
            logger.exception("创建技能调用日志时发生完整性错误")
        except SQLAlchemyError:
            await session.rollback()
            logger.exception("创建技能调用日志失败")
        break


async def handle_bind(websocket: WebSocket, payload: Dict[str, Any], manager: ConnectionManager) -> None:
    """处理设备绑定请求"""
    _log_json("WS 入站[绑定]：", payload)
    device_id = payload.get("device_id")
    session_id = payload.get("session_id")

    if not device_id or not session_id:
        response = {"type": "error", "message": "缺少 device_id 或 session_id"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    ip_address, user_agent = _extract_client_meta(websocket)
    updates = {
        key: payload.get(key)
        for key in ("model", "os_version", "app_version")
        if payload.get(key) is not None
    }

    async for session in get_session():
        try:
            result = await session.execute(select(Device).where(Device.device_id == device_id))
            device = result.scalar_one_or_none()
            if device:
                for key, value in updates.items():
                    setattr(device, key, value)
                device.status = 1
                device.last_seen = func.now()

            device_session = DeviceSession(
                session_id=session_id,
                device_id=device_id,
                ip_address=ip_address,
                user_agent=user_agent,
            )
            session.add(device_session)
            await session.commit()
        except IntegrityError:
            await session.rollback()
            logger.exception("持久化设备绑定时发生完整性错误，正在重试")
            try:
                result = await session.execute(select(Device).where(Device.device_id == device_id))
                device = result.scalar_one_or_none()
                if device:
                    for key, value in updates.items():
                        setattr(device, key, value)
                    device.status = 1
                    device.last_seen = func.now()

                result = await session.execute(select(DeviceSession).where(DeviceSession.session_id == session_id))
                existing_session = result.scalar_one_or_none()
                if existing_session:
                    existing_session.device_id = device_id
                    existing_session.ip_address = ip_address
                    existing_session.user_agent = user_agent
                    existing_session.connected_at = func.now()
                    existing_session.disconnected_at = None
                else:
                    session.add(
                        DeviceSession(
                            session_id=session_id,
                            device_id=device_id,
                            ip_address=ip_address,
                            user_agent=user_agent,
                        )
                    )
                await session.commit()
            except (IntegrityError, SQLAlchemyError):
                await session.rollback()
                logger.exception("重试后仍无法持久化设备绑定")
        except SQLAlchemyError:
            await session.rollback()
            logger.exception("持久化设备绑定失败")
        break

    manager.bind(websocket, device_id, session_id)
    response = {"type": "bind_ack", "device_id": device_id, "session_id": session_id}
    _log_json("WS 出站：", response)
    await websocket.send_json(response)


async def handle_task(websocket: WebSocket, payload: Dict[str, Any], manager: ConnectionManager) -> None:
    _log_json("WS 入站：", payload)

    # 验证设备绑定
    if not manager.is_bound(websocket):
        response = {"type": "error", "message": "设备未绑定，请先发送绑定消息"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    # 验证是否为当前活动连接
    if not manager.is_current_connection(websocket):
        response = {"type": "error", "message": "连接已过期，设备已重新连接"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    # 验证 session_id 匹配（若客户端提供）
    bound_session = getattr(websocket.state, "session_id", None)
    payload_session_id = payload.get("session_id")
    if payload_session_id and payload_session_id != bound_session:
        response = {"type": "error", "message": f"会话不匹配：期望 {bound_session}，实际 {payload_session_id}"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    device_id = getattr(websocket.state, "device_id", None)
    db_skill_models: Dict[str, Any] = {}
    db_default_model: Dict[str, Any] | None = None
    if device_id:
        try:
            db_skill_models, db_default_model = await _load_builtin_models(device_id)
        except Exception as exc:
            logger.warning(f"加载设备 {device_id} 的内置模型配置失败：{exc}")

    if not db_default_model:
        response = {"type": "error", "message": "设备未配置默认模型,请在设置中配置"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    ok, msg = validate_model_config(db_default_model)
    if not ok:
        response = {"type": "error", "message": f"default_model 无效：{msg}"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    resolved_builtin_models: Dict[str, Any] = {}
    missing_builtin: list[str] = []
    for skill_id in BUILTIN_SKILL_IDS:
        model_config = db_skill_models.get(skill_id) or db_default_model
        if not model_config:
            missing_builtin.append(skill_id)
            continue
        ok, msg = validate_model_config(model_config)
        if not ok:
            response = {"type": "error", "message": f"builtin_models[{skill_id}] 无效：{msg}"}
            _log_json("WS 出站：", response)
            await websocket.send_json(response)
            return
        resolved_builtin_models[skill_id] = model_config

    if missing_builtin:
        response = {"type": "error", "message": f"缺少内置模型配置：{', '.join(missing_builtin)}"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return

    # 加载 manager_model（规划模型），如果没有则使用 default_model
    manager_model: Dict[str, Any] | None = None
    if device_id:
        try:
            manager_model = await _load_manager_model(device_id)
        except Exception as exc:
            logger.warning(f"加载设备 {device_id} 的 manager_model 失败：{exc}")

    if manager_model:
        ok, msg = validate_model_config(manager_model)
        if not ok:
            logger.warning(f"设备 {device_id} 的 manager_model 无效({msg}),回退到 default_model")
            manager_model = db_default_model
        else:
            logger.info(f"设备 {device_id} 使用专用 manager_model: {manager_model.get('model')}")
    else:
        manager_model = db_default_model
        logger.info(f"设备 {device_id} 的 manager_model 未配置，使用 default_model")

    payload["builtin_models"] = resolved_builtin_models
    payload["default_model"] = db_default_model
    payload["manager_model"] = manager_model
    if payload.pop("user_agents", None) is not None:
        logger.warning(f"设备 {device_id} 发送了user_agents字段（已忽略），请升级到新版客户端")

    # 加载用户自定义技能（不注册到全局registry，而是通过payload传递）
    device_id = getattr(websocket.state, "device_id", None)
    user_skills = []
    if device_id:
        try:
            async for session in get_session():
                user_skills = await load_user_skills(device_id, session)
                logger.info(f"已为设备 {device_id} 从数据库加载 {len(user_skills)} 个用户技能")
                break
        except Exception as e:
            logger.warning(f"加载设备 {device_id} 的用户技能失败：{e}")

    # 将用户技能添加到 payload
    payload["user_skills"] = user_skills

    try:
        start_time = perf_counter()
        result = run_task(payload)
    except Exception as exc:  # pragma: no cover - 防御性日志
        execution_ms = int((perf_counter() - start_time) * 1000)
        await _create_usage_log(websocket, payload, None, 0, execution_ms)
        logger.exception("模型执行失败：%s", exc)
        response = {"type": "error", "message": f"模型执行失败：{exc}"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
        return
    execution_ms = int((perf_counter() - start_time) * 1000)
    await _create_usage_log(websocket, payload, result, 1, execution_ms)
    skill_timings = result.get("skill_timings") or []
    await _create_skill_invocation_logs(websocket, payload, skill_timings)

    # 去重 actions（防止重复发送）
    actions = result.get("actions", []) or []
    if actions:
        seen = set()
        deduped = []
        for action in actions:
            key = json.dumps(action, sort_keys=True, ensure_ascii=False)
            if key not in seen:
                seen.add(key)
                deduped.append(action)
        actions = deduped

    for action in actions:
        response = {"type": "action", "action": action}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
    effects = result.get("effects", [])
    if effects:
        response = {"type": "effect", "effects": effects}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)


async def handle_disconnect(websocket: WebSocket, manager: ConnectionManager) -> None:
    session_id = getattr(websocket.state, "session_id", None)
    if session_id:
        # 清理该 session 的规划缓存
        plan_cache.clear(session_id)
        logger.info(f"已清理 session {session_id} 的规划缓存")

        async for session in get_session():
            try:
                result = await session.execute(select(DeviceSession).where(DeviceSession.session_id == session_id))
                device_session = result.scalar_one_or_none()
                if device_session:
                    device_session.disconnected_at = func.now()
                    await session.commit()
            except SQLAlchemyError:
                await session.rollback()
                logger.exception("更新设备会话断开时间失败")
            break
    manager.unbind(websocket)


async def handle_message(websocket: WebSocket, payload: Dict[str, Any], manager: ConnectionManager) -> None:
    message_type = payload.get("type")
    if message_type == "bind":
        await handle_bind(websocket, payload, manager)
    elif message_type == "task":
        await handle_task(websocket, payload, manager)
    else:
        response = {"type": "error", "message": "未知消息类型"}
        _log_json("WS 出站：", response)
        await websocket.send_json(response)
