from __future__ import annotations

from typing import Dict, List


class XiaozhiAgent:
    def evaluate(self, task: str, plan: List[str]) -> Dict[str, bool]:
        return {"done": True}
