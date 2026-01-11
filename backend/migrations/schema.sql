-- ============================================
-- Phone Agent AI 小智 - 数据库初始化脚本
-- 版本: 2.0.0 (包含 scope_key 计算列)
-- MySQL 8.0+ / MariaDB 10.5+
-- ============================================

-- 检查数据库是否存在，不存在则创建
CREATE DATABASE IF NOT EXISTS xiaozhi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xiaozhi;

-- ============================================
-- 清理已存在的表（按依赖顺序反向删除）
-- ============================================
DROP TABLE IF EXISTS device_sessions;
DROP TABLE IF EXISTS skill_invocations;
DROP TABLE IF EXISTS usage_logs;
DROP TABLE IF EXISTS baidu_speech_configs;
DROP TABLE IF EXISTS model_configs;
DROP TABLE IF EXISTS skills;
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS users;

-- ============================================
-- 创建表结构
-- ============================================

-- 用户表（预留，用于未来账号绑定功能）
CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
  username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名（唯一）',
  password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
  display_name VARCHAR(128) NULL COMMENT '显示名称',
  email VARCHAR(255) NULL COMMENT '邮箱地址',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表（预留用于账号绑定）';

-- 设备表（主标识为device_id）
CREATE TABLE devices (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '设备记录ID',
  device_id CHAR(36) NOT NULL COMMENT '设备唯一标识（UUID）',
  user_id BIGINT UNSIGNED NULL COMMENT '关联的用户ID（可选，应用层维护）',
  model VARCHAR(128) NULL COMMENT '设备型号（如：Xiaomi 13 Pro）',
  os_version VARCHAR(64) NULL COMMENT '操作系统版本（如：Android 13）',
  app_version VARCHAR(32) NULL COMMENT 'App版本号（如：1.0.0）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '设备状态：1=正常，0=已禁用',
  last_seen TIMESTAMP NULL DEFAULT NULL COMMENT '最后在线时间',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '设备首次注册时间',
  updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '信息更新时间',
  UNIQUE KEY uq_devices_device_id (device_id),
  INDEX idx_devices_user_id (user_id),
  INDEX idx_devices_status_last_seen (status, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备信息表';

-- 技能表（内置技能 + 用户自定义技能）
CREATE TABLE skills (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '技能记录ID',
  skill_id VARCHAR(64) NOT NULL COMMENT '技能唯一标识（如：anti_scam, translator）',
  owner_device_id CHAR(36) NULL COMMENT '所属设备ID（NULL表示系统内置技能，应用层维护）',
  parent_skill_id VARCHAR(64) NULL COMMENT '父技能ID（继承模板）',
  name VARCHAR(128) NOT NULL COMMENT '技能名称（如：防诈骗、翻译）',
  description TEXT NOT NULL COMMENT '技能详细描述（用于AI识别触发场景）',
  definition JSON NOT NULL COMMENT '技能定义（包含system_prompt、effects等配置）',
  is_builtin BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为内置技能（TRUE=系统内置，FALSE=用户自定义）',
  is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用（TRUE=启用，FALSE=禁用）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uq_skills_skill_id (skill_id),
  INDEX idx_skills_owner_active (owner_device_id, is_active),
  INDEX idx_skills_builtin_active (is_builtin, is_active),
  INDEX idx_skills_parent (parent_skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能表（内置+用户自定义）';

-- 模型配置表（三级优先级：skill > device > global）
CREATE TABLE model_configs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
  owner_device_id CHAR(36) NULL COMMENT '所属设备ID（NULL表示全局配置）',
  skill_id VARCHAR(64) NULL COMMENT '关联技能ID（NULL表示设备级通用配置）',
  provider VARCHAR(64) NOT NULL COMMENT '模型提供商（如：openai、anthropic）',
  base_url VARCHAR(255) NOT NULL COMMENT 'API基础URL',
  api_key VARCHAR(255) NOT NULL COMMENT 'API密钥',
  model VARCHAR(128) NOT NULL COMMENT '模型名称（如：gpt-4、claude-3）',
  config VARCHAR(2048) NOT NULL COMMENT '额外配置参数（JSON格式）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  scope_key VARCHAR(200) GENERATED ALWAYS AS (CONCAT(IFNULL(owner_device_id, '_global'), ':', IFNULL(skill_id, '_default'))) STORED COMMENT '作用域标识（计算列）',
  UNIQUE KEY uq_model_configs_scope (scope_key),
  INDEX idx_model_configs_device (owner_device_id),
  INDEX idx_model_configs_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型配置表（三级优先级配置）';

-- 百度语音配置表（每设备独立配置）
CREATE TABLE baidu_speech_configs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
  owner_device_id CHAR(36) NOT NULL COMMENT '所属设备ID',
  app_id VARCHAR(128) NOT NULL COMMENT '百度语音App ID',
  api_key VARCHAR(255) NOT NULL COMMENT '百度语音API Key',
  secret_key VARCHAR(255) NOT NULL COMMENT '百度语音Secret Key',
  config VARCHAR(2048) NOT NULL COMMENT '其他配置参数（JSON格式）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uq_baidu_speech_configs_owner_device_id (owner_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='百度语音配置表（per-device）';

-- 使用日志表（用于统计和审计）
CREATE TABLE usage_logs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
  device_id CHAR(36) NOT NULL COMMENT '设备ID',
  skill_id VARCHAR(64) NOT NULL COMMENT '使用的技能ID',
  task_text TEXT NULL COMMENT '任务描述文本',
  status TINYINT NOT NULL COMMENT '执行状态：1=成功，0=失败',
  execution_ms INT UNSIGNED NULL COMMENT '执行耗时（毫秒）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  INDEX idx_usage_logs_device_time (device_id, created_at),
  INDEX idx_usage_logs_skill_time (skill_id, created_at),
  INDEX idx_usage_logs_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='使用日志表（统计和审计）';

-- 技能调用日志表（用于统计执行耗时 + 频率）
CREATE TABLE skill_invocations (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
  device_id CHAR(36) NOT NULL COMMENT '设备ID',
  skill_id VARCHAR(64) NOT NULL COMMENT '技能ID',
  task_text TEXT NULL COMMENT '任务描述文本',
  status TINYINT NOT NULL COMMENT '执行状态：1=成功，0=失败',
  execution_ms INT UNSIGNED NULL COMMENT '执行耗时（毫秒）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  INDEX idx_skill_invocations_device_time (device_id, created_at),
  INDEX idx_skill_invocations_skill_time (skill_id, created_at),
  INDEX idx_skill_invocations_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能调用日志表（执行耗时 + 频率）';

-- 设备会话表（WebSocket连接跟踪）
CREATE TABLE device_sessions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '会话记录ID',
  session_id CHAR(36) NOT NULL COMMENT '会话ID（UUID）',
  device_id CHAR(36) NOT NULL COMMENT '设备ID',
  connected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '连接时间',
  disconnected_at TIMESTAMP NULL DEFAULT NULL COMMENT '断开时间（NULL表示仍在线）',
  ip_address VARCHAR(45) NULL COMMENT '客户端IP地址（支持IPv4和IPv6）',
  user_agent VARCHAR(255) NULL COMMENT '客户端User-Agent',
  UNIQUE KEY uq_device_sessions_session_id (session_id),
  INDEX idx_device_sessions_device_time (device_id, connected_at),
  INDEX idx_device_sessions_connected (connected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备会话表（WebSocket连接跟踪）';

-- ============================================
-- 初始化数据
-- ============================================

-- 默认管理员用户（密码：admin123）
INSERT INTO users (id, username, password_hash, display_name, email, status)
VALUES (1, 'admin', '$2b$12$Lko0kNY5oMejHZPRlbSkYuXmhaU2BCj0nquzSnYpH0iRgmTMXmuXK', '系统管理员', NULL, 1);

-- 内置技能
INSERT INTO skills (skill_id, owner_device_id, parent_skill_id, name, description, definition, is_builtin, is_active)
VALUES
  (
    'default',
    NULL,
    NULL,
    '默认',
    '基础对话能力，不使用任何特殊技能。适用于日常问答、简单任务和通用场景。',
    JSON_OBJECT('type', 'builtin', 'category', 'general'),
    TRUE,
    TRUE
  ),
  (
    'translator',
    NULL,
    NULL,
    '翻译',
    '识别并翻译屏幕文字或用户输入。适用场景：外语应用界面、菜单/路牌/文档截图、跨语言沟通、学习翻译。支持中英互译及常见语种互译。',
    JSON_OBJECT(
        'type', 'builtin',
        'category', 'language',
        'system_prompt', '你是一个专业的翻译助手。用户会提供需要翻译的文本，以及目标语言（如果未指定则智能判断）。你需要：\n1. 准确理解原文含义\n2. 保持原文的语气和风格\n3. 使用地道的目标语言表达\n4. 对专业术语给出准确翻译\n\n直接返回翻译结果，不要加多余解释。',
        'effects', JSON_ARRAY('overlay'),
        'sub_skills', JSON_ARRAY()
    ),
    TRUE,
    TRUE
  ),
  (
    'anti_scam',
    NULL,
    NULL,
    '防诈骗',
    '检测短信、通知、聊天中诈骗风险。关键场景：转账/汇款/验证码、账号异常/冻结、中奖/退税/退款、冒充客服/公安/法院、刷单兼职、可疑链接/二维码、远程控制软件诱导等。任何涉及资金与账号安全的可疑内容都适用。',
    JSON_OBJECT(
        'type', 'builtin',
        'category', 'security',
        'system_prompt', '你是反诈骗专家助手。用户会提供可疑的消息、电话或链接。你需要：\n1. 分析是否存在诈骗特征（冒充官方、要求转账、索要验证码等）\n2. 评估风险等级（高危/可疑/安全）\n3. 给出具体的防范建议\n4. 如果是诈骗，说明诈骗类型和常见手法\n\n回复格式：\n风险等级：[高危/可疑/安全]\n分析：[具体分析]\n建议：[防范措施]',
        'effects', JSON_ARRAY('shake', 'flash'),
        'sub_skills', JSON_ARRAY()
    ),
    TRUE,
    TRUE
  ),
  (
    'doudizhu',
    NULL,
    NULL,
    '斗地主大师',
    '分析斗地主牌局并给出出牌建议。适用于对局过程中的牌型判断、出牌时机、控牌与风险评估（地主/农民策略不同）。',
    JSON_OBJECT(
        'type', 'builtin',
        'category', 'game',
        'system_prompt', '你是斗地主游戏策略专家。用户会提供当前手牌和场上情况。你需要：\n1. 分析当前牌力和牌型\n2. 评估叫地主/抢地主的建议\n3. 根据场上已出的牌，推荐最优出牌策略\n4. 考虑控牌、拆牌、炸弹使用时机\n\n回复要简洁明确，直接给出建议。',
        'effects', JSON_ARRAY('overlay'),
        'sub_skills', JSON_ARRAY()
    ),
    TRUE,
    TRUE
  ),
  (
    'photo_composition',
    NULL,
    NULL,
    '构图大师',
    '提供拍摄构图指导。适用于相机预览时的主体摆放、画面平衡、三分法/留白、横平竖直等构图优化建议。',
    JSON_OBJECT(
        'type', 'builtin',
        'category', 'photography',
        'system_prompt', '你是摄影构图专家。用户会提供相机预览画面的描述或截图。你需要：\n1. 分析当前构图（三分法、对称、留白、前景背景等）\n2. 识别画面主体位置\n3. 给出具体的构图改进建议（移动位置、调整角度、等待时机等）\n4. 如果构图已经很好，给予肯定并指出亮点\n\n回复要简洁实用，让用户能快速调整。',
        'effects', JSON_ARRAY('overlay', 'region_select'),
        'sub_skills', JSON_ARRAY()
    ),
    TRUE,
    TRUE
  );

-- ============================================
-- 验证安装
-- ============================================
SELECT '数据库初始化完成！' AS message;
SELECT COUNT(*) AS builtin_skills_count FROM skills WHERE is_builtin = TRUE;
SHOW TABLES;

-- ============================================
-- 初始化完成
-- ============================================
