// 设备信息
export interface Device {
  device_id: string;          // 设备唯一标识
  user_id?: number;           // 用户ID（可选）
  model?: string;             // 设备型号
  os_version?: string;        // 操作系统版本
  app_version?: string;       // 应用版本
  status: 'active' | 'disabled';  // 设备状态
  last_seen?: string;         // 最后在线时间
  created_at: string;         // 创建时间
  updated_at?: string;        // 更新时间
}

// 技能定义
export interface Skill {
  id: string;                 // 技能ID
  owner_device_id?: string;   // 所属设备ID（空表示全局技能）
  name: string;               // 技能名称
  description: string;        // 技能描述
  definition: Record<string, any>;  // 技能定义（JSON）
  is_builtin: boolean;        // 是否为内置技能
  is_active: boolean;         // 是否启用
  created_at: string;         // 创建时间
  updated_at?: string;        // 更新时间
}

// 模型配置
export interface ModelConfig {
  id: number;                 // 配置ID
  owner_device_id?: string;   // 所属设备ID（可选）
  skill_id?: string;          // 关联技能ID（可选）
  provider: string;           // 模型提供商
  base_url: string;           // API基础URL
  api_key: string;            // API密钥
  model: string;              // 模型名称
  config?: Record<string, any>;  // 额外配置（JSON）
  scope_key: string;          // 作用域标识
  created_at: string;         // 创建时间
  updated_at?: string;        // 更新时间
}

// 百度语音识别配置
export interface BaiduSpeechConfig {
  owner_device_id: string;    // 所属设备ID
  app_id: string;             // 百度应用ID
  api_key: string;            // 百度API Key
  secret_key: string;         // 百度Secret Key
  config?: Record<string, any>;  // 额外配置（JSON）
  created_at: string;         // 创建时间
  updated_at?: string;        // 更新时间
}

// 使用日志
export interface UsageLog {
  id: number;                 // 日志ID
  device_id: string;          // 设备ID
  skill_id: string;           // 技能ID
  task_text?: string;         // 任务文本
  status: 'success' | 'failure';  // 执行状态
  execution_ms?: number;      // 执行耗时（毫秒）
  created_at: string;         // 创建时间
}

// 设备会话记录
export interface DeviceSession {
  session_id: string;         // 会话ID
  device_id: string;          // 设备ID
  connected_at: string;       // 连接时间
  disconnected_at?: string;   // 断开时间
  ip_address?: string;        // IP地址
  user_agent?: string;        // 用户代理
}
