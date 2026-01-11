from __future__ import annotations

from typing import Any, Dict, Optional

from pydantic import BaseModel


class ModelConfig(BaseModel):
    base_url: Optional[str] = None
    api_key: Optional[str] = None
    model: Optional[str] = None


class ModelRouter:
    def resolve_builtin_model(
        self,
        skill_id: str,
        builtin_models: Optional[Dict[str, Optional[Dict[str, Any]]]],
        default_model: Optional[Dict[str, Any]],
    ) -> Optional[ModelConfig]:
        if not builtin_models:
            return ModelConfig(**default_model) if default_model else None
        override = builtin_models.get(skill_id)
        if override is None:
            return ModelConfig(**default_model) if default_model else None
        return ModelConfig(**override)

    def resolve_user_skill_model(
        self,
        skill_model: Optional[Dict[str, Any]],
        agent_model: Optional[Dict[str, Any]],
        default_model: Optional[Dict[str, Any]],
    ) -> Optional[ModelConfig]:
        if skill_model:
            return ModelConfig(**skill_model)
        if agent_model:
            return ModelConfig(**agent_model)
        return ModelConfig(**default_model) if default_model else None
