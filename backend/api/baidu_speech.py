from __future__ import annotations

import base64
import binascii
import logging
from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from api.baidu_speech_configs import _get_baidu_speech_config_or_404, _parse_config_value
from db.connection import get_session
from db.models import BaiduSpeechConfig
from db.redis_client import get_redis

logger = logging.getLogger(__name__)
router = APIRouter()

UUID_PATTERN = r"^[a-fA-F0-9\-]{36}$"
TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
ASR_URL = "http://vop.baidu.com/server_api"
DEFAULT_OPTIONS: dict[str, Any] = {
    "format": "pcm",
    "rate": 16000,
    "channel": 1,
    "dev_pid": 1537,
}
MAX_AUDIO_SIZE_MB = 10


class BaiduSpeechRecognizeRequest(BaseModel):
    device_id: str = Field(..., min_length=36, max_length=36, pattern=UUID_PATTERN)
    audio: str = Field(..., min_length=1, description="Base64 编码的 PCM 负载")
    format: str | None = None
    rate: int | None = None
    channel: int | None = None
    dev_pid: int | None = None
    cuid: str | None = None


class BaiduSpeechRecognizeResponse(BaseModel):
    result: str
    raw: dict[str, Any] | None = None


async def _get_access_token(config: BaiduSpeechConfig) -> str:
    redis = get_redis()
    cache_key = f"baidu:speech:token:{config.owner_device_id}"

    try:
        cached = await redis.get(cache_key)
        if cached:
            logger.debug(f"使用设备 {config.owner_device_id} 的百度 token 缓存")
            return cached
    except Exception as exc:
        logger.warning(f"Redis 读取 token 缓存失败: {exc}，将获取新 token")

    logger.info(f"为设备 {config.owner_device_id} 获取新的百度访问 token")
    params = {
        "grant_type": "client_credentials",
        "client_id": config.api_key,
        "client_secret": config.secret_key,
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(TOKEN_URL, params=params)
            response.raise_for_status()
    except httpx.HTTPError as exc:
        logger.exception("获取百度访问 token 失败")
        raise HTTPException(status_code=502, detail=f"百度 token 请求失败: {exc}") from exc

    try:
        data = response.json()
    except Exception as exc:
        logger.error(f"百度 token 响应包含无效 JSON: {exc}")
        raise HTTPException(status_code=502, detail="百度 token 响应 JSON 无效") from exc

    token = data.get("access_token")
    if not token:
        logger.error(f"百度 token 响应无效: {data}")
        raise HTTPException(status_code=502, detail=f"百度 token 响应无效: {data}")

    expires_in = int(data.get("expires_in", 2592000))
    ttl = max(expires_in - 60, 60)

    try:
        await redis.set(cache_key, token, ex=ttl)
        logger.info(f"已缓存设备 {config.owner_device_id} 的百度 token，TTL={ttl}s")
    except Exception as exc:
        logger.warning(f"Redis 写入 token 缓存失败: {exc}，将继续不使用缓存")

    return token


def _merge_options(payload: BaiduSpeechRecognizeRequest, config: BaiduSpeechConfig) -> dict[str, Any]:
    options = dict(DEFAULT_OPTIONS)
    parsed_config = _parse_config_value(config.config)
    if parsed_config:
        for key in ("format", "rate", "channel", "dev_pid", "cuid"):
            if key in parsed_config:
                options[key] = parsed_config[key]
    if payload.format is not None:
        options["format"] = payload.format
    if payload.rate is not None:
        options["rate"] = payload.rate
    if payload.channel is not None:
        options["channel"] = payload.channel
    if payload.dev_pid is not None:
        options["dev_pid"] = payload.dev_pid
    if payload.cuid is not None:
        options["cuid"] = payload.cuid
    return options


def _decode_audio(audio_b64: str) -> bytes:
    try:
        decoded = base64.b64decode(audio_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail="无效的 base64 音频负载") from exc

    max_bytes = MAX_AUDIO_SIZE_MB * 1024 * 1024
    if len(decoded) > max_bytes:
        raise HTTPException(status_code=400, detail=f"音频过大: {len(decoded)} 字节，最大 {max_bytes}")

    return decoded


@router.post("/api/baidu-speech/recognize", response_model=BaiduSpeechRecognizeResponse)
async def recognize_baidu_speech(
    payload: BaiduSpeechRecognizeRequest,
    session: AsyncSession = Depends(get_session),
) -> BaiduSpeechRecognizeResponse:
    """后端语音识别接口：接收设备音频，调用百度ASR API，返回识别结果"""
    logger.info(f"收到设备 {payload.device_id} 的语音识别请求")

    config = await _get_baidu_speech_config_or_404(session, payload.device_id)
    audio_bytes = _decode_audio(payload.audio)
    logger.debug(f"音频解码完成: {len(audio_bytes)} 字节")

    options = _merge_options(payload, config)
    token = await _get_access_token(config)

    cuid = options.get("cuid") or config.owner_device_id
    request_body = {
        "format": options["format"],
        "rate": options["rate"],
        "dev_pid": options["dev_pid"],
        "channel": options["channel"],
        "token": token,
        "cuid": cuid,
        "len": len(audio_bytes),
        "speech": base64.b64encode(audio_bytes).decode("ascii"),
    }

    logger.info(f"调用百度 ASR API: format={options['format']}, rate={options['rate']}, audio_len={len(audio_bytes)}")
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(ASR_URL, json=request_body)
            response.raise_for_status()
    except httpx.HTTPError as exc:
        logger.exception("百度 ASR 请求失败")
        raise HTTPException(status_code=502, detail=f"百度 ASR 请求失败: {exc}") from exc

    try:
        data = response.json()
    except Exception as exc:
        logger.error(f"百度 ASR 响应包含无效 JSON: {exc}")
        raise HTTPException(status_code=502, detail="百度 ASR 响应 JSON 无效") from exc

    err_no = data.get("err_no", -1)
    if err_no != 0:
        err_msg = data.get("err_msg", "未知错误")
        logger.error(f"百度 ASR 错误: err_no={err_no}, err_msg={err_msg}")
        raise HTTPException(status_code=502, detail=f"百度 ASR 错误 {err_no}: {err_msg}")

    result_list = data.get("result") or []
    if not result_list:
        logger.warning("百度 ASR 返回空结果")
        raise HTTPException(status_code=502, detail="百度 ASR 返回空结果")

    result_text = result_list[0]
    logger.info(f"设备 {payload.device_id} 语音识别成功")
    return BaiduSpeechRecognizeResponse(result=result_text, raw=data)
