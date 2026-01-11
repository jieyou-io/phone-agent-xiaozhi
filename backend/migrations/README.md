# 数据库 Schema 说明

## 快速开始

### 初始化数据库

```bash
cd backend/migrations

# 1. 执行初始化脚本
mysql -u root -p xiaozhi < schema.sql

# 2. 验证结果（应该看到 5 个内置技能）
mysql -u root -p xiaozhi -e "SELECT skill_id, name FROM skills WHERE is_builtin = true;"

# 3. 启动后端服务
cd ..
./run_bg.sh

# 4. 检查日志
tail -f nohup.out | grep "内置技能"
```

## Schema 说明

**`schema.sql`** - 完整数据库架构，包含：

### Part 1: 表结构定义
- `users` - 用户表（预留）
- `devices` - 设备信息表
- `skills` - 技能表（支持继承）
- `model_configs` - 模型配置表（支持全局/设备/技能三级配置）
- `baidu_speech_configs` - 百度语音配置表
- `usage_logs` - 使用日志表
- `skill_invocations` - 技能调用日志表
- `device_sessions` - 设备会话表

### Part 2: 初始化数据
- 1 个默认管理员用户（密码：admin123）
- 5 个内置技能：
  - `default` - 默认技能（基础对话）
  - `translator` - 翻译助手
  - `anti_scam` - 反诈助手
  - `doudizhu` - 斗地主助手
  - `photo_composition` - 拍照构图助手

### Part 3: 验证查询
- 自动验证插入结果
- 显示内置技能数量

## 设计要点

### 1. 技能继承支持
```sql
-- skills 表支持父技能继承
parent_skill_id VARCHAR(64) NULL
```

**用途**：用户可基于内置技能创建自定义版本

### 2. 模型配置关联
```sql
-- model_configs 通过 (owner_device_id, skill_id) 关联技能
CREATE TABLE model_configs (
  owner_device_id CHAR(36) NULL,  -- NULL = 全局配置
  skill_id VARCHAR(64) NULL,       -- NULL = 设备默认配置
  ...
)
```

**效果**：每个技能可使用专用模型，未配置时继承设备默认或全局默认

### 3. 三级配置优先级
```sql
-- 查询顺序
1. 技能专用: (device_id=xxx, skill_id=xxx)
2. 设备默认: (device_id=xxx, skill_id=NULL)
3. 全局默认: (device_id=NULL, skill_id=NULL)
```

**优先级**：技能专用 > 设备默认 > 全局默认

### 4. 无外键约束
```sql
-- 只有索引，没有 FOREIGN KEY CONSTRAINT
INDEX idx_model_configs_skill (skill_id)
```

**原因**：灵活性优先，避免级联删除问题，应用层维护数据完整性

### 5. 计算列（唯一性保证）
```sql
-- scope_key 用于唯一性约束
scope_key AS (CONCAT(IFNULL(owner_device_id, '_global'), ':', IFNULL(skill_id, '_default'))) STORED
```

**效果**：同一设备/技能组合只能有一个配置

## 预期日志

执行初始化后，启动服务应该看到：

```log
INFO - 从数据库加载了 5 个内置技能
WARNING - 未找到模型配置: device=None, skill=translator
WARNING - 未找到模型配置: device=None, skill=anti_scam
...（其他技能也会有警告，这是正常的，因为没有配置全局默认模型）
INFO - 注册内置技能: default - 默认
INFO - 注册内置技能: translator - 翻译
INFO - 注册内置技能: anti_scam - 反诈助手
INFO - 注册内置技能: doudizhu - 斗地主助手
INFO - 注册内置技能: photo_composition - 拍照构图助手
INFO - 成功注册 5 个内置技能
```

**注意**：模型配置警告是正常的，需要在 Android 设置中配置设备默认模型。

## 常见问题

### Q1: 提示"未能加载任何内置技能"
**A**: 检查数据是否成功插入
```sql
SELECT * FROM skills WHERE is_builtin = true;
```

### Q2: 想修改某个技能的提示词
**A**: 直接更新数据库，无需重启（下次任务自动生效）
```sql
UPDATE skills
SET definition = JSON_SET(definition, '$.system_prompt', '新的提示词...')
WHERE skill_id = 'translator' AND is_builtin = true;
```

### Q3: 如何配置全局默认模型
**A**: 插入一条全局配置
```sql
INSERT INTO model_configs (owner_device_id, skill_id, provider, base_url, api_key, model, config)
VALUES (NULL, NULL, 'openai', 'https://api.openai.com/v1', 'sk-xxx', 'gpt-4o-mini', '{}');
```

### Q4: 如何为特定设备配置默认模型
**A**: 在 Android 设置中配置，或手动插入
```sql
INSERT INTO model_configs (owner_device_id, skill_id, provider, base_url, api_key, model, config)
VALUES ('your-device-id', NULL, 'openai', 'https://api.openai.com/v1', 'sk-xxx', 'gpt-4o-mini', '{}');
```

### Q5: 如何为特定技能配置专用模型
**A**: 插入技能专用配置
```sql
INSERT INTO model_configs (owner_device_id, skill_id, provider, base_url, api_key, model, config)
VALUES ('your-device-id', 'translator', 'openai', 'https://api.openai.com/v1', 'sk-xxx', 'gpt-4', '{}');
```

## 数据库表结构

### skills 表（新增字段）
| 字段 | 类型 | 说明 |
|------|------|------|
| parent_skill_id | VARCHAR(64) | 父技能ID（继承） |

### model_configs 表（关键字段）
| 字段 | 类型 | 说明 |
|------|------|------|
| owner_device_id | CHAR(36) NULL | NULL=全局配置 |
| skill_id | VARCHAR(64) NULL | NULL=设备默认配置 |
| scope_key | VARCHAR(200) | 唯一约束计算列 |

## 后续功能

- 用户基于内置技能创建自定义版本
- 为特定技能配置专用模型
- 在线修改提示词（无需重启）
- Android 设置界面配置所有模型
