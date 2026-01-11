# 内置技能提示词指南 / Builtin Skill Prompts Guide

本文档说明所有内置技能的系统提示词（System Prompts），包括中英文双语版本。

---

## 📋 提示词概览

| 技能 ID | 技能名称 | 默认语言 | 原因 |
|---------|---------|----------|------|
| `anti_scam` | 防诈骗 | 中文 (ZH) | 主要用户场景在中国 |
| `translator` | 翻译 | 英文 (EN) | 逻辑描述更清晰 |
| `photo_composition` | 构图大师 | 中文 (ZH) | 中文用户为主 |
| `doudizhu` | 斗地主大师 | 中文 (ZH) | 中国游戏 |

---

## 🛡️ Anti-Scam (防诈骗)

### 功能描述
检测短信、通知、聊天中的诈骗风险。关键场景包括转账/汇款/验证码、账号异常/冻结、中奖/退税/退款、冒充客服/公安/法院、刷单兼职、可疑链接/二维码、远程控制软件诱导等。

### 中文提示词 (默认)
```python
ANTI_SCAM_PROMPT_ZH = """
你是一个防诈骗助手。基于任务文本和屏幕截图评估诈骗风险。
重点关注欺诈指标，如：冒充身份、紧急转账、验证码、远程控制请求、虚假退款或账户安全威胁。
仅返回 JSON：
{
  "risk_level": "low|medium|high",
  "message": "检测到的风险的简要说明",
  "signals": ["具体指标1", "具体指标2"]
}
"""
```

### 英文提示词
```python
ANTI_SCAM_PROMPT_EN = """
You are an anti-scam assistant. Evaluate scam risk using the task text and screenshot.
Focus on fraud indicators such as impersonation, urgent money transfers, verification codes,
remote control requests, fake refunds, or account security threats.
Return JSON only:
{
  "risk_level": "low|medium|high",
  "message": "Brief explanation of the detected risk",
  "signals": ["specific indicator 1", "specific indicator 2"]
}
"""
```

### 返回字段说明
- `risk_level`: 风险等级 (`low`/`medium`/`high`)
- `message`: 风险说明文本
- `signals`: 具体诈骗指标列表

---

## 🌐 Translator (翻译)

### 功能描述
识别并翻译屏幕文字或用户输入。适用场景：外语应用界面、菜单/路牌/文档截图、跨语言沟通、学习翻译。支持中英互译及常见语种互译。

### 英文提示词 (默认)
```python
TRANSLATOR_PROMPT_EN = """
You are a translation assistant. Extract the most relevant text from the screenshot or task.
Detect the source language, and respect any target language specified in the task. If no target
language is specified, translate to Chinese when the source is non-Chinese; otherwise translate
to English. Preserve names, numbers, and UI labels.
Return JSON only:
{
  "text": "translated text",
  "source_language": "detected source language",
  "target_language": "target language used",
  "notes": "optional translation notes"
}
"""
```

### 中文提示词
```python
TRANSLATOR_PROMPT_ZH = """
你是一个翻译助手。从屏幕截图或任务中提取最相关的文本。
检测源语言，并尊重任务中指定的目标语言。如果未指定目标语言，当源语言为非中文时翻译为中文；
否则翻译为英文。保留姓名、数字和 UI 标签。
仅返回 JSON：
{
  "text": "翻译后的文本",
  "source_language": "检测到的源语言",
  "target_language": "使用的目标语言",
  "notes": "可选的翻译备注"
}
"""
```

### 返回字段说明
- `text`: 翻译后的文本
- `source_language`: 检测到的源语言（如 "English", "Chinese"）
- `target_language`: 使用的目标语言
- `notes`: 可选的翻译备注（如保留的专有名词、翻译难点等）

---

## 📷 Photo Composition (构图大师)

### 功能描述
提供拍摄构图指导。适用于相机预览时的主体摆放、画面平衡、三分法/留白、横平竖直等构图优化建议。

### 基础构图提示（中文，默认）
```python
PHOTO_COMPOSITION_PROMPT_ZH = """
你是一个照片构图助手。分析图像并提供简洁、可操作的提示。
仅返回 JSON：
{
  "region": "center|left|right|top|bottom",
  "direction": "up|down|left|right|none",
  "hint": "简要的构图建议"
}
"""
```

### 基础构图提示（英文）
```python
PHOTO_COMPOSITION_PROMPT_EN = """
You are a photo composition assistant. Analyze the image and provide a concise, actionable hint.
Return JSON only:
{
  "region": "center|left|right|top|bottom",
  "direction": "up|down|left|right|none",
  "hint": "Brief composition suggestion"
}
"""
```

### 坐标定位提示（中文，默认）
```python
PHOTO_COMPOSITION_COORDINATE_PROMPT_ZH = """
你是一个相机预览的照片构图助手。
分析图像并识别遵循三分法则的最佳主体放置点。
提供归一化坐标（0-1范围），指明主体应放置的位置。

返回以下结构的有效 JSON：
{
  "x_norm": 0.33,
  "y_norm": 0.66,
  "confidence": 0.85,
  "rule": "rule_of_thirds",
  "note": "将主体放在左下交叉点以获得平衡构图"
}

字段要求：
- x_norm, y_norm: 0-1 归一化坐标（0=左/上，1=右/下）
- confidence: 0-1 置信度（>0.7 = 高置信度，触发自动点击）
- rule: "rule_of_thirds"、"centered"、"golden_ratio" 等（可选）
- note: 一句话的简要说明（可选）

常见三分法点：
- (0.33, 0.33): 左上交叉点
- (0.67, 0.33): 右上交叉点
- (0.33, 0.67): 左下交叉点
- (0.67, 0.67): 右下交叉点

如果不确定或图像不是相机预览，设置 confidence < 0.5。
不要在 JSON 对象之外包含任何文本。
"""
```

### 返回字段说明（基础构图）
- `region`: 建议调整的区域 (`center`/`left`/`right`/`top`/`bottom`)
- `direction`: 移动方向 (`up`/`down`/`left`/`right`/`none`)
- `hint`: 构图建议文本

### 返回字段说明（坐标定位）
- `x_norm`, `y_norm`: 归一化坐标 (0-1)
- `confidence`: 置信度 (0-1)，>0.7 时触发自动点击
- `rule`: 使用的构图法则
- `note`: 说明文本

---

## 🃏 Doudizhu (斗地主大师)

### 功能描述
分析斗地主牌局并给出出牌建议。适用于对局过程中的牌型判断、出牌时机、控牌与风险评估（地主/农民策略不同）。

### 中文提示词 (默认)
```python
DOUDIZHU_PROMPT_ZH = """
你是一个斗地主策略助手。根据任务和屏幕截图推荐下一步出牌。
保持建议简洁实用。
仅返回 JSON：
{
  "text": "出牌建议",
  "play_type": "single|pair|triple|sequence|bomb|rocket|control|support",
  "risk": "low|medium|high"
}
"""
```

### 英文提示词
```python
DOUDIZHU_PROMPT_EN = """
You are a Dou Dizhu strategy assistant. Use the task and screenshot to recommend the next move.
Keep the suggestion concise and practical.
Return JSON only:
{
  "text": "move suggestion",
  "play_type": "single|pair|triple|sequence|bomb|rocket|control|support",
  "risk": "low|medium|high"
}
"""
```

### 返回字段说明
- `text`: 出牌建议文本
- `play_type`: 出牌类型
  - `single`: 单张
  - `pair`: 对子
  - `triple`: 三张
  - `sequence`: 顺子
  - `bomb`: 炸弹
  - `rocket`: 王炸
  - `control`: 控牌
  - `support`: 助攻
- `risk`: 风险等级 (`low`/`medium`/`high`)

---

## 🔧 动态语言选择

### 使用工具函数
```python
from config.skill_prompts import get_prompt_by_language, ANTI_SCAM_PROMPT_EN, ANTI_SCAM_PROMPT_ZH

# 根据用户语言偏好选择提示词
user_language = "zh-CN"  # 可以从用户配置或请求中获取
prompt = get_prompt_by_language(ANTI_SCAM_PROMPT_EN, ANTI_SCAM_PROMPT_ZH, user_language)
```

### 函数签名
```python
def get_prompt_by_language(
    prompt_en: str,
    prompt_zh: str,
    language: str | None = None
) -> str
```

### 参数说明
- `prompt_en`: 英文版提示词
- `prompt_zh`: 中文版提示词
- `language`: 语言代码（如 `'en'`, `'zh'`, `'zh-CN'`），`None` 时返回默认（通常为中文）

### 返回值
根据 `language` 参数返回对应语言的提示词。

---

## 📝 使用示例

### 在技能类中使用
```python
from config.skill_prompts import ANTI_SCAM_PROMPT, ANTI_SCAM_PROMPT_EN, ANTI_SCAM_PROMPT_ZH, get_prompt_by_language
from skills.model_helpers import call_skill_model

class AntiScamSkill(Skill):
    def analyze(self, task: str, context: dict) -> SkillResult:
        # 方式1: 使用默认提示词（中文）
        parsed = call_skill_model(task, context, ANTI_SCAM_PROMPT)

        # 方式2: 根据上下文动态选择语言
        user_language = context.get("language", "zh")
        prompt = get_prompt_by_language(ANTI_SCAM_PROMPT_EN, ANTI_SCAM_PROMPT_ZH, user_language)
        parsed = call_skill_model(task, context, prompt)

        # 处理返回结果...
        return SkillResult(message=parsed.get("message"), effects=[])
```

---

## 🌍 语言选择策略

### 当前默认语言
- **防诈骗**: 中文 (主要用户在中国，中文关键词检测更准确)
- **翻译**: 英文 (逻辑描述更清晰，避免翻译逻辑混淆)
- **构图大师**: 中文 (中文用户为主，建议更易理解)
- **斗地主**: 中文 (中国游戏，术语中文更准确)

### 未来扩展
如果需要支持更多语言，可以在 `skill_prompts.py` 中添加新的提示词变量，并扩展 `get_prompt_by_language` 函数。

---

## 📌 注意事项

1. **JSON 格式要求**: 所有提示词要求模型返回纯 JSON，不包含 markdown 代码块标记
2. **字段一致性**: 中英文提示词的 JSON 字段名保持一致，便于统一解析
3. **默认值处理**: 技能类代码应包含默认值逻辑，应对模型返回格式不符的情况
4. **测试覆盖**: 修改提示词后，应测试两种语言版本的输出质量
5. **提示词版本**: 当前所有提示词版本为 1.0.0，重大修改时应更新版本号

---

**最后更新**: 2026-01-09
**维护者**: Phone Agent AI Team
