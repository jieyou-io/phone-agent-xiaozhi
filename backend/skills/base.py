from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class SkillEffect(BaseModel):
    type: str
    payload: Dict[str, Any] = Field(default_factory=dict)


class SkillResult(BaseModel):
    message: str
    effects: List[SkillEffect] = Field(default_factory=list)


class SkillSchemaMetadata(BaseModel):
    """
    遵循 Agent Skills 规范的技能结构元数据。
    输入/输出结构使用 JSON Schema draft-07 格式。
    所有字段可选，以兼容旧版本。
    """
    input_schema: Dict[str, Any] | None = None
    output_schema: Dict[str, Any] | None = None
    capabilities: List[str] = Field(default_factory=list)
    version: str | None = None


class Skill(ABC):
    id: str
    name: str
    description: str
    icon: Optional[str] = None
    deletable: bool = False
    schema: SkillSchemaMetadata | None = None

    def metadata(self) -> Dict[str, Any]:
        data = {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "icon": self.icon,
            "deletable": self.deletable,
        }
        if self.schema:
            data["schema"] = self.schema.model_dump(exclude_none=True)
        return data

    @abstractmethod
    def analyze(self, task: str, context: Dict[str, Any]) -> SkillResult:
        raise NotImplementedError
