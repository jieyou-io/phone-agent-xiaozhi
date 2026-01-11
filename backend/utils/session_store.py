from __future__ import annotations

from collections import deque
from typing import Any, Deque, Dict, List


class SessionStore:
    def __init__(self, max_messages: int = 10) -> None:
        self._max_messages = max_messages
        self._store: Dict[str, Deque[dict]] = {}

    def get_history(self, session_id: str) -> List[dict]:
        if not session_id:
            return []
        history = self._store.get(session_id)
        if not history:
            return []
        return list(history)

    def append_user(self, session_id: str, text: str) -> None:
        if not session_id:
            return
        self._append(session_id, {"role": "user", "content": text})

    def append_assistant(self, session_id: str, text: str) -> None:
        if not session_id:
            return
        self._append(session_id, {"role": "assistant", "content": text})

    def _append(self, session_id: str, message: dict) -> None:
        history = self._store.setdefault(session_id, deque(maxlen=self._max_messages))
        history.append(message)


class PlanCache:
    """缓存 session 的规划结果，避免重复调用 Planner"""

    def __init__(self) -> None:
        self._store: Dict[str, dict] = {}

    def get(self, session_id: str, task: str) -> dict | None:
        """获取缓存的规划结果"""
        if not session_id or not task:
            return None
        # 规范化 task 文本（去除首尾空格、统一标点）
        normalized_task = task.strip()
        cached = self._store.get(session_id)
        if not cached or cached.get("task") != normalized_task:
            return None
        return cached

    def set(
        self,
        session_id: str,
        task: str,
        plan: List[str],
        selected_skills: List[str],
        selected_agent: dict | None,
    ) -> None:
        """缓存规划结果"""
        if not session_id:
            return
        # 规范化 task 文本
        normalized_task = task.strip()
        self._store[session_id] = {
            "task": normalized_task,
            "plan": list(plan),
            "selected_skills": list(selected_skills),
            "selected_agent": selected_agent,
        }

    def clear(self, session_id: str) -> None:
        """清除指定 session 的缓存"""
        if session_id in self._store:
            del self._store[session_id]


session_store = SessionStore(max_messages=10)
plan_cache = PlanCache()

