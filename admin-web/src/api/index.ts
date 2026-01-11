import axios from 'axios';
import type { Device, Skill, ModelConfig, BaiduSpeechConfig, UsageLog, DeviceSession } from './types';

const api = axios.create({
  baseURL: '',
  withCredentials: true,
});

// ========== 设备相关 API ==========
// 获取设备列表
export const getDevices = () => api.get<Device[]>('/api/devices');
// 获取单个设备详情
export const getDevice = (id: string) => api.get<Device>(`/api/devices/${id}`);

// ========== 技能相关 API ==========
// 获取技能列表（可选设备ID筛选）
export const getSkills = (deviceId?: string) => api.get<Skill[]>('/api/skills', { params: { device_id: deviceId } });
// 创建技能
export const createSkill = (data: Partial<Skill>) => api.post<Skill>('/api/skills', data);
// 更新技能
export const updateSkill = (id: string, data: Partial<Skill>, deviceId: string) => api.put<Skill>(`/api/skills/${id}?device_id=${deviceId}`, data);
// 删除技能
export const deleteSkill = (id: string, deviceId: string) => api.delete(`/api/skills/${id}?device_id=${deviceId}`);

// ========== 模型配置相关 API ==========
// 获取模型配置列表
export const getModelConfigs = () => api.get<ModelConfig[]>('/api/model-configs');
// 创建模型配置
export const createModelConfig = (data: Partial<ModelConfig>) => api.post<ModelConfig>('/api/model-configs', data);
// 更新模型配置
export const updateModelConfig = (id: number, data: Partial<ModelConfig>) => api.put<ModelConfig>(`/api/model-configs/${id}`, data);
// 删除模型配置
export const deleteModelConfig = (id: number) => api.delete(`/api/model-configs/${id}`);

// ========== 百度语音配置相关 API ==========
// 获取百度语音配置
export const getBaiduSpeechConfig = (deviceId: string) => api.get<BaiduSpeechConfig>(`/api/baidu-speech-configs/${deviceId}`);
// 创建百度语音配置
export const createBaiduSpeechConfig = (data: BaiduSpeechConfig) => api.post<BaiduSpeechConfig>('/api/baidu-speech-configs', data);
// 更新百度语音配置
export const updateBaiduSpeechConfig = (deviceId: string, data: Partial<BaiduSpeechConfig>) => api.put<BaiduSpeechConfig>(`/api/baidu-speech-configs/${deviceId}`, data);
// 删除百度语音配置
export const deleteBaiduSpeechConfig = (deviceId: string) => api.delete(`/api/baidu-speech-configs/${deviceId}`);

// ========== 使用日志相关 API ==========
// 获取设备使用日志
export const getUsageLogs = (deviceId: string) => api.get<UsageLog[]>('/api/usage-logs', { params: { device_id: deviceId } });
// 获取设备会话记录
export const getDeviceSessions = (deviceId: string) => api.get<DeviceSession[]>('/api/device-sessions', { params: { device_id: deviceId } });

// ========== 认证相关 API ==========
export const login = (data: { username: string; password: string }) => api.post('/api/auth/login', data);
export const logout = () => api.post('/api/auth/logout');
export const checkAuth = () => api.get('/api/auth/me');
export const changePassword = (data: { old_password: string; new_password: string; confirm_password: string }) => api.put('/api/auth/password', data);
