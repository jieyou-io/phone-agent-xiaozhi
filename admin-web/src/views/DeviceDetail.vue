<template>
  <div class="device-detail-container">
    <div class="header">
      <el-button @click="$router.back()" style="margin-right: 20px;">返回</el-button>
      <h2>设备详情: {{ id }}</h2>
    </div>

    <el-card v-loading="loading" class="info-card">
      <el-descriptions title="设备信息" border>
        <el-descriptions-item label="设备ID">{{ device?.device_id }}</el-descriptions-item>
        <el-descriptions-item label="型号">{{ device?.model || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="device?.status === 'active' ? 'success' : 'info'">
            {{ device?.status === 'active' ? '在线' : '离线' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="系统版本">{{ device?.os_version || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="应用版本">{{ device?.app_version || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="最后在线">{{ device?.last_seen || '从未' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-tabs v-model="activeTab" class="detail-tabs" @tab-click="handleTabClick">
      <el-tab-pane label="使用日志" name="logs">
        <el-table :data="logs" stripe style="width: 100%" v-loading="logsLoading">
          <el-table-column prop="created_at" label="时间" width="180" />
          <el-table-column prop="skill_id" label="技能ID" width="150" />
          <el-table-column prop="task_text" label="任务" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.status === 'success' ? 'success' : 'danger'">
                {{ scope.row.status === 'success' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="execution_ms" label="耗时 (ms)" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="会话记录" name="sessions">
        <el-table :data="sessions" stripe style="width: 100%" v-loading="sessionsLoading">
          <el-table-column prop="session_id" label="会话ID" width="220" />
          <el-table-column prop="connected_at" label="连接时间" width="180" />
          <el-table-column prop="disconnected_at" label="断开时间" width="180" />
          <el-table-column prop="ip_address" label="IP地址" width="150" />
          <el-table-column prop="user_agent" label="User Agent" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getDevice, getUsageLogs, getDeviceSessions } from '@/api';
import type { Device, UsageLog, DeviceSession } from '@/api/types';

const props = defineProps<{
  id: string;
}>();

const device = ref<Device | null>(null);
const logs = ref<UsageLog[]>([]);
const sessions = ref<DeviceSession[]>([]);
const activeTab = ref('logs');
const loading = ref(false);
const logsLoading = ref(false);
const sessionsLoading = ref(false);

// 获取设备信息
const fetchDevice = async () => {
  loading.value = true;
  try {
    const res = await getDevice(props.id);
    device.value = res.data;
  } catch (error) {
    ElMessage.error('获取设备信息失败');
  } finally {
    loading.value = false;
  }
};

// 获取使用日志
const fetchLogs = async () => {
  logsLoading.value = true;
  try {
    const res = await getUsageLogs(props.id);
    logs.value = res.data;
  } catch (error) {
    ElMessage.error('获取使用日志失败');
  } finally {
    logsLoading.value = false;
  }
};

// 获取会话记录
const fetchSessions = async () => {
  sessionsLoading.value = true;
  try {
    const res = await getDeviceSessions(props.id);
    sessions.value = res.data;
  } catch (error) {
    ElMessage.error('获取会话记录失败');
  } finally {
    sessionsLoading.value = false;
  }
};

// 标签页切换时懒加载数据
const handleTabClick = () => {
  if (activeTab.value === 'logs' && logs.value.length === 0) fetchLogs();
  if (activeTab.value === 'sessions' && sessions.value.length === 0) fetchSessions();
};

// 监听设备ID变化，切换设备时重新加载数据
watch(() => props.id, () => {
  device.value = null;
  logs.value = [];
  sessions.value = [];
  fetchDevice();
  if (activeTab.value === 'logs') {
    fetchLogs();
  } else if (activeTab.value === 'sessions') {
    fetchSessions();
  }
});

onMounted(() => {
  fetchDevice();
  fetchLogs();
});
</script>

<style scoped>
.device-detail-container {
  padding: 20px;
}
.header {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.info-card {
  margin-bottom: 20px;
}
</style>
