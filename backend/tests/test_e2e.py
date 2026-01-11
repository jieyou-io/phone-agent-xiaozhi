from agents.graph import run_task


def test_end_to_end_like_flow():
    payload = {"type": "task", "task": "打开翻译"}
    result = run_task(payload)
    assert result["done"] is True
