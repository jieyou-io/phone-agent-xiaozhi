from __future__ import annotations

import logging
from operator import add
from time import perf_counter
from typing import Annotated, Any, Dict, List, Optional, TypedDict

from agents.executor import ExecutorAgent
from agents.planner import PlannerAgent
from agents.xiaozhi import XiaozhiAgent
from skills import anti_scam, doudizhu, photo_composition, translator  # noqa: F401
from skills.effect_registry import validate_effects
from skills.registry import registry
from utils.model_router import ModelRouter
from utils.session_store import plan_cache

logger = logging.getLogger(__name__)

try:
    from langgraph.graph import END, StateGraph
except ImportError:  # pragma: no cover
    END = "__END__"
    StateGraph = None


class AgentState(TypedDict):
    task: Annotated[str, lambda x, y: x or y]
    screenshot: Annotated[Optional[str], lambda x, y: x or y]
    translation_region: Annotated[Optional[Dict[str, Any]], lambda x, y: x or y]
    plan: Annotated[List[str], lambda x, y: y if y else x]
    selected_skills: Annotated[List[str], lambda x, y: y if y else x]
    actions: Annotated[List[Dict[str, Any]], lambda x, y: y]
    effects: Annotated[List[Dict[str, Any]], add]
    skill_timings: Annotated[List[Dict[str, Any]], add]
    done: Annotated[bool, lambda x, y: y]
    model_config: Annotated[Optional[Dict[str, Any]], lambda x, y: x or y]
    session_id: Annotated[Optional[str], lambda x, y: x or y]
    builtin_models: Annotated[Dict[str, Any], lambda x, y: x or y]
    user_agents: Annotated[List[Dict[str, Any]], lambda x, y: y if y else x]
    user_skills: Annotated[List[Any], lambda x, y: y if y else x]
    selected_agent: Annotated[Optional[Dict[str, Any]], lambda x, y: y if y is not None else x]
    pending_skills: Annotated[List[str], lambda x, y: y if y else x]
    system_prompt_override: Annotated[Optional[str], lambda x, y: y if y is not None else x]
    manager_model: Annotated[Optional[Dict[str, Any]], lambda x, y: x or y]
    default_model: Annotated[Optional[Dict[str, Any]], lambda x, y: x or y]
    skip_planner: Annotated[bool, lambda x, y: y]


planner_agent = PlannerAgent()
executor_agent = ExecutorAgent()
xiaozhi_agent = XiaozhiAgent()
model_router = ModelRouter()


def xiaozhi_entry(state: AgentState) -> AgentState:
    return state


def planner_node(state: AgentState) -> AgentState:
    # 如果有缓存则跳过规划
    if state.get("skip_planner"):
        logger.info("使用缓存的规划结果，跳过 Planner 调用")
        return state

    planner_model = state.get("manager_model") or state.get("default_model")
    result = planner_agent.run(
        state["task"],
        state.get("user_agents"),
        planner_model,
        state.get("user_skills", [])
    )
    state["plan"] = result["plan"]
    state["selected_skills"] = result["skills"]
    state["selected_agent"] = result.get("agent")
    state["pending_skills"] = list(state["selected_skills"])

    # 缓存规划结果
    session_id = state.get("session_id")
    if session_id:
        plan_cache.set(
            session_id,
            state["task"],
            state["plan"],
            state["selected_skills"],
            state.get("selected_agent"),
        )
        logger.info(f"已缓存 session {session_id} 的规划结果")

    return state


def executor_node(state: AgentState) -> AgentState:
    model_config = state.get("model_config")
    selected_agent = state.get("selected_agent")
    system_prompt_override = state.get("system_prompt_override")
    if selected_agent:
        model_config = selected_agent.get("model") or model_config
        system_prompt_override = selected_agent.get("system_prompt")
        state["system_prompt_override"] = system_prompt_override
    if "translator" in (state.get("selected_skills") or []) and not selected_agent:
        model_config = None
    result = executor_agent.run(
        state["task"],
        state["selected_skills"],
        model_config,
        state.get("screenshot"),
        state.get("session_id"),
        system_prompt_override,
    )
    state["actions"] = result["actions"]
    state["effects"] = result["effects"]
    return state


def skill_node_factory(skill_id: str):
    def run_skill(state: AgentState) -> AgentState:
        # 只处理内置技能（用户技能由 user_skill_node 处理）
        skill = registry.get(skill_id) if skill_id in [s.id for s in registry.all()] else None

        if not skill:
            # 技能未找到，跳过
            if state["pending_skills"] and state["pending_skills"][0] == skill_id:
                state["pending_skills"].pop(0)
            return state

        model_config = model_router.resolve_builtin_model(
            skill_id,
            state.get("builtin_models"),
            state.get("default_model"),
        )
        context = {
            "screenshot": state.get("screenshot"),
            "model_config": model_config.model_dump() if model_config else None,
            "translation_region": state.get("translation_region"),
        }

        start_time = perf_counter()
        try:
            result = skill.analyze(state["task"], context)
        except Exception:
            execution_ms = int((perf_counter() - start_time) * 1000)
            state.setdefault("skill_timings", []).append(
                {"skill_id": skill_id, "execution_ms": execution_ms, "status": 0}
            )
            raise

        execution_ms = int((perf_counter() - start_time) * 1000)
        state.setdefault("skill_timings", []).append(
            {"skill_id": skill_id, "execution_ms": execution_ms, "status": 1}
        )

        effects_data = [effect.model_dump() for effect in result.effects]
        is_valid, errors = validate_effects(effects_data)
        if not is_valid:
            logger.warning(f"技能 {skill_id} 产生了无效效果: {errors}")
        state["effects"].extend(effects_data)
        if state["pending_skills"] and state["pending_skills"][0] == skill_id:
            state["pending_skills"].pop(0)
        return state

    return run_skill


def skill_router_node(state: AgentState) -> AgentState:
    return state


def skill_route(state: AgentState) -> str:
    if state["pending_skills"]:
        skill_id = state["pending_skills"][0]
        # 用户技能统一路由到 "user_skill" node
        if skill_id.startswith("user:"):
            return "user_skill"
        return skill_id
    return "xiaozhi_check"


def user_skill_node(state: AgentState) -> AgentState:
    """通用用户技能执行 node"""
    if not state["pending_skills"]:
        return state

    skill_id = state["pending_skills"][0]
    if not skill_id.startswith("user:"):
        return state

    # 从 user_skills 中查找技能
    skill = None
    for user_skill in state.get("user_skills", []):
        if user_skill.id == skill_id:
            skill = user_skill
            break

    if not skill:
        # 技能未找到，跳过
        state["pending_skills"].pop(0)
        return state

    # 解析模型配置：优先级 skill.model_config > agent.model > default_model
    skill_model = getattr(skill, "model_config", None)
    agent_model = (state.get("selected_agent") or {}).get("model")
    model_config = model_router.resolve_user_skill_model(
        skill_model,
        agent_model,
        state.get("default_model"),
    )

    context = {
        "screenshot": state.get("screenshot"),
        "model_config": model_config.model_dump() if model_config else None,
        "translation_region": state.get("translation_region"),
    }

    start_time = perf_counter()
    try:
        result = skill.analyze(state["task"], context)
    except Exception:
        execution_ms = int((perf_counter() - start_time) * 1000)
        state.setdefault("skill_timings", []).append(
            {"skill_id": skill_id, "execution_ms": execution_ms, "status": 0}
        )
        raise

    execution_ms = int((perf_counter() - start_time) * 1000)
    state.setdefault("skill_timings", []).append(
        {"skill_id": skill_id, "execution_ms": execution_ms, "status": 1}
    )

    effects_data = [effect.model_dump() for effect in result.effects]
    is_valid, errors = validate_effects(effects_data)
    if not is_valid:
        logger.warning(f"用户技能 {skill_id} 产生了无效效果: {errors}")
    state["effects"].extend(effects_data)
    state["pending_skills"].pop(0)
    return state


def xiaozhi_check(state: AgentState) -> AgentState:
    verdict = xiaozhi_agent.evaluate(state["task"], state["plan"])
    state["done"] = verdict["done"]
    return state


def build_graph() -> Any:
    if StateGraph is None:
        return None
    graph = StateGraph(AgentState)
    graph.add_node("xiaozhi_entry", xiaozhi_entry)
    graph.add_node("planner", planner_node)
    graph.add_node("executor", executor_node)
    graph.add_node("xiaozhi_check", xiaozhi_check)
    graph.add_node("skill_router", skill_router_node)
    graph.add_node("user_skill", user_skill_node)  # 通用用户技能 node
    for skill in registry.all():
        graph.add_node(skill.id, skill_node_factory(skill.id))
    graph.set_entry_point("xiaozhi_entry")
    graph.add_edge("xiaozhi_entry", "planner")
    graph.add_edge("planner", "executor")
    graph.add_edge("executor", "skill_router")
    graph.add_edge("user_skill", "skill_router")  # 用户技能执行后回到 router
    for skill in registry.all():
        graph.add_edge(skill.id, "skill_router")
    # skill_router 使用条件边路由到下一个技能或 xiaozhi_check，不需要无条件边

    def route(state: AgentState) -> str:
        return END if state.get("done") else "planner"

    graph.add_conditional_edges("xiaozhi_check", route)
    graph.add_conditional_edges("skill_router", skill_route)
    return graph.compile()


def run_task(payload: Dict[str, Any]) -> Dict[str, Any]:
    default_model = payload.get("default_model")
    manager_model = payload.get("manager_model")
    model_config = default_model or manager_model
    builtin_models = payload.get("builtin_models") or {}

    task = payload.get("task", "")
    session_id = payload.get("session_id")

    # 尝试从缓存获取规划结果
    cached_plan = plan_cache.get(session_id or "", task)

    state: AgentState = {
        "task": task,
        "screenshot": payload.get("screenshot"),
        "translation_region": payload.get("translation_region"),
        "plan": cached_plan["plan"] if cached_plan else [],
        "selected_skills": cached_plan["selected_skills"] if cached_plan else [],
        "actions": [],
        "effects": [],
        "skill_timings": [],
        "done": False,
        "model_config": model_config,
        "builtin_models": builtin_models,
        "session_id": session_id,
        "user_agents": payload.get("user_agents") or [],
        "user_skills": payload.get("user_skills") or [],
        "selected_agent": cached_plan["selected_agent"] if cached_plan else None,
        "pending_skills": list(cached_plan["selected_skills"]) if cached_plan else [],
        "system_prompt_override": None,
        "manager_model": manager_model,
        "default_model": default_model,
        "skip_planner": bool(cached_plan),
    }
    graph = build_graph()
    if graph:
        result = graph.invoke(state)
        return result
    return xiaozhi_check(executor_node(planner_node(xiaozhi_entry(state))))
