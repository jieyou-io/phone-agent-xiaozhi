from __future__ import annotations

from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI
import logging

from api.auth import router as auth_router
from api.skills import router as skills_router
from api.health import router as health_router
from api.devices import router as devices_router
from api.model_configs import router as model_configs_router
from api.baidu_speech_configs import router as baidu_speech_configs_router
from api.baidu_speech import router as baidu_speech_router
from api.usage_logs import router as usage_logs_router
from config.settings import settings
from websocket.server import register_websocket
from db.connection import async_engine
from db.redis_client import get_redis
from utils.auth_dependency import get_current_user
from skills.builtin_loader import register_builtin_skills

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动
    logging.info("正在启动...")

    # 加载并注册内置技能
    try:
        await register_builtin_skills()
    except Exception as e:
        logging.exception(f"加载内置技能失败: {e}")
        logging.warning("应用将继续启动，但内置技能可能不可用")

    yield

    # 关闭
    logging.info("正在关闭...")
    await async_engine.dispose()
    redis_client = get_redis()
    await redis_client.close()


app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.include_router(auth_router)
app.include_router(health_router)
app.include_router(devices_router, dependencies=[Depends(get_current_user)])
app.include_router(skills_router, dependencies=[Depends(get_current_user)])
app.include_router(model_configs_router, dependencies=[Depends(get_current_user)])
app.include_router(baidu_speech_configs_router, dependencies=[Depends(get_current_user)])
app.include_router(baidu_speech_router, dependencies=[Depends(get_current_user)])
app.include_router(usage_logs_router, dependencies=[Depends(get_current_user)])
register_websocket(app, settings.websocket_path)


@app.get("/")
def root() -> dict:
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000)
