from __future__ import annotations

from typing import Any, Dict, Optional
import base64
import io

from PIL import Image


def crop_base64_image(
    base64_data: str,
    region: Dict[str, Any],
) -> Optional[str]:
    try:
        raw = base64.b64decode(base64_data)
        image = Image.open(io.BytesIO(raw))
    except Exception:
        return None

    try:
        x = int(region.get("x", 0))
        y = int(region.get("y", 0))
        width = int(region.get("width", image.width))
        height = int(region.get("height", image.height))
        screen_width = int(region.get("screen_width", image.width))
        screen_height = int(region.get("screen_height", image.height))
    except (TypeError, ValueError):
        return None

    if screen_width <= 0 or screen_height <= 0:
        screen_width = image.width
        screen_height = image.height

    scale_x = image.width / screen_width
    scale_y = image.height / screen_height

    crop_left = max(0, min(image.width, int(x * scale_x)))
    crop_top = max(0, min(image.height, int(y * scale_y)))
    crop_right = max(crop_left + 1, min(image.width, int((x + width) * scale_x)))
    crop_bottom = max(crop_top + 1, min(image.height, int((y + height) * scale_y)))

    cropped = image.crop((crop_left, crop_top, crop_right, crop_bottom))
    buffer = io.BytesIO()
    cropped.convert("RGB").save(buffer, format="JPEG", quality=85)
    return base64.b64encode(buffer.getvalue()).decode("utf-8")
