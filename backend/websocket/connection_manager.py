from __future__ import annotations

from typing import Dict, Optional

from fastapi import WebSocket


class ConnectionManager:
    """管理 WebSocket 连接与设备/会话的绑定关系"""

    def __init__(self) -> None:
        self._device_to_ws: Dict[str, WebSocket] = {}
        self._session_to_device: Dict[str, str] = {}

    def bind(self, websocket: WebSocket, device_id: str, session_id: str) -> None:
        """绑定设备和会话到 WebSocket 连接"""
        # 清理该设备的旧 session 映射
        old_sessions = [sid for sid, did in self._session_to_device.items() if did == device_id]
        for old_sid in old_sessions:
            self._session_to_device.pop(old_sid, None)

        # 绑定新的设备和会话
        self._device_to_ws[device_id] = websocket
        self._session_to_device[session_id] = device_id
        websocket.state.device_id = device_id
        websocket.state.session_id = session_id

    def unbind(self, websocket: WebSocket) -> None:
        """解绑 WebSocket 连接"""
        device_id = getattr(websocket.state, "device_id", None)
        session_id = getattr(websocket.state, "session_id", None)
        if device_id:
            self._device_to_ws.pop(device_id, None)
        if session_id:
            self._session_to_device.pop(session_id, None)

    def device_for_session(self, session_id: str) -> Optional[str]:
        """根据 session_id 查找对应的设备"""
        return self._session_to_device.get(session_id)

    def is_bound(self, websocket: WebSocket) -> bool:
        """检查 WebSocket 是否已绑定"""
        return hasattr(websocket.state, "device_id") and hasattr(websocket.state, "session_id")

    def is_current_connection(self, websocket: WebSocket) -> bool:
        """检查 WebSocket 是否是设备的当前活动连接"""
        device_id = getattr(websocket.state, "device_id", None)
        if not device_id:
            return False
        return self._device_to_ws.get(device_id) is websocket
