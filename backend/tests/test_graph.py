from agents.graph import run_task


def test_run_task_returns_effects_for_translation():
    result = run_task({"task": "请翻译"})
    assert "effects" in result
    assert any(effect["type"] == "translation" for effect in result["effects"])
