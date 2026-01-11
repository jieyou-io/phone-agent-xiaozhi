import pytest
from httpx import AsyncClient
from main import app
from db.models import User
from utils.auth_dependency import get_current_user


@pytest.fixture(autouse=True)
def override_auth():
    """Override authentication for testing"""
    async def _override():
        return User(id=1, username="test", password_hash="x", status=1)
    app.dependency_overrides[get_current_user] = _override
    yield
    app.dependency_overrides.pop(get_current_user, None)


@pytest.mark.asyncio
async def test_builtin_skills_response_shape():
    """Test that /api/skills returns a direct array of skills"""
    async with AsyncClient(app=app, base_url="http://test") as client:
        response = await client.get("/api/skills")
        assert response.status_code == 200

        skills = response.json()
        assert isinstance(skills, list), "Response should be a direct array"

        # Verify at least one builtin skill exists (e.g., "default" or "translator")
        builtin_skills = [s for s in skills if s.get("is_builtin") is True]
        assert len(builtin_skills) > 0, "Should have at least one builtin skill"

        # Verify "default" skill exists (added in recent changes)
        default_skill = next((s for s in skills if s["id"] == "default"), None)
        assert default_skill is not None, "Should have 'default' builtin skill"
        assert default_skill["is_builtin"] is True

