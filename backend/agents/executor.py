from __future__ import annotations

from typing import Any, Dict, List, Optional

from config.prompts import build_system_prompt
from utils import action_parser
from utils import model_client
from utils.session_store import session_store
from utils.validators import validate_action, validate_model_config


class ExecutorAgent:
    def run(
        self,
        task: str,
        selected_skills: List[str],
        model_config: Optional[Dict[str, Any]],
        screenshot: Optional[str],
        session_id: Optional[str],
        system_prompt_override: Optional[str] = None,
    ) -> Dict[str, List[Dict[str, Any]]]:
        actions: List[Dict[str, Any]] = []
        effects: List[Dict[str, Any]] = []

        if model_config:
            valid, reason = validate_model_config(model_config)
            if not valid:
                raise ValueError(f"模型配置无效: {reason}")
            model_actions, model_effects = self._run_with_model(
                task,
                screenshot,
                model_config,
                session_id,
                system_prompt_override,
            )
            actions.extend(model_actions)
            effects.extend(model_effects)
            return {"actions": actions, "effects": effects}
        return {"actions": actions, "effects": effects}

    def _run_with_model(
        self,
        task: str,
        screenshot: Optional[str],
        model_config: Dict[str, Any],
        session_id: Optional[str],
        system_prompt_override: Optional[str],
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        system_prompt = system_prompt_override or build_system_prompt()

        history = session_store.get_history(session_id or "")

        content: List[Dict[str, Any]] = [{"type": "text", "text": task}]
        if screenshot:
            content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{screenshot}"},
            })

        messages = [{"role": "system", "content": system_prompt}]
        messages.extend(history)
        messages.append({"role": "user", "content": content})

        response = model_client.chat_completions(
            base_url=model_config["base_url"],
            api_key=model_config["api_key"],
            model=model_config["model"],
            messages=messages,
        )
        raw = model_client.extract_content(response) or ""
        session_store.append_user(session_id or "", task)
        session_store.append_assistant(session_id or "", raw)
        _, action_text = action_parser.parse_response(raw)
        action = action_parser.parse_action(action_text)
        valid, reason = validate_action(action)
        if not valid:
            raise ValueError(f"动作无效: {reason}")
        return [action], []
