from __future__ import annotations

import ast
from typing import Any, Tuple


def parse_response(content: str) -> Tuple[str, str]:
    if "finish(message=" in content:
        parts = content.split("finish(message=", 1)
        thinking = parts[0].strip()
        action = "finish(message=" + parts[1]
        return thinking, action

    if "do(action=" in content:
        parts = content.split("do(action=", 1)
        thinking = parts[0].strip()
        action = "do(action=" + parts[1]
        return thinking, action

    if "<answer>" in content:
        parts = content.split("<answer>", 1)
        thinking = parts[0].replace("<think>", "").replace("</think>", "").strip()
        action = parts[1].replace("</answer>", "").strip()
        return thinking, action

    return "", content


def parse_action(response: str) -> dict[str, Any]:
    response = response.strip()
    if response.startswith('do(action="Type"') or response.startswith(
        'do(action="Type_Name"'
    ):
        text = response.split("text=", 1)[1][1:-2]
        return {"_metadata": "do", "action": "Type", "text": text}

    if response.startswith("do"):
        response = response.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        tree = ast.parse(response, mode="eval")
        if not isinstance(tree.body, ast.Call):
            raise ValueError("期望函数调用")
        action: dict[str, Any] = {"_metadata": "do"}
        for keyword in tree.body.keywords:
            action[keyword.arg] = ast.literal_eval(keyword.value)
        return action

    if response.startswith("finish"):
        return {
            "_metadata": "finish",
            "message": response.replace("finish(message=", "")[1:-2],
        }

    raise ValueError(f"解析动作失败：{response}")
