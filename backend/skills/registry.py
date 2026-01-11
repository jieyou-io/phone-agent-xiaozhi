from __future__ import annotations

from typing import Dict, List

from skills.base import Skill


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: Dict[str, Skill] = {}

    def register(self, skill: Skill) -> None:
        self._skills[skill.id] = skill

    def get(self, skill_id: str) -> Skill:
        return self._skills[skill_id]

    def list_skills(self) -> List[dict]:
        return [skill.metadata() for skill in self._skills.values()]

    def all(self) -> List[Skill]:
        return list(self._skills.values())


registry = SkillRegistry()
