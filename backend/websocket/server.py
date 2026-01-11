from __future__ import annotations

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from websocket.connection_manager import ConnectionManager
from websocket.handlers import handle_bind, handle_disconnect, handle_message


def register_websocket(app: FastAPI, path: str = "/ws") -> None:
    manager = ConnectionManager()

    @app.websocket(path)
    async def websocket_endpoint(websocket: WebSocket) -> None:
        await websocket.accept()
        device_id = websocket.query_params.get("device_id") if websocket.query_params else None
        session_id = websocket.query_params.get("session_id") if websocket.query_params else None
        if device_id and session_id:
            await handle_bind(
                websocket,
                {"type": "bind", "device_id": device_id, "session_id": session_id},
                manager,
            )
        try:
            while True:
                data = await websocket.receive_json()
                await handle_message(websocket, data, manager)
        except WebSocketDisconnect:
            pass
        finally:
            await handle_disconnect(websocket, manager)
