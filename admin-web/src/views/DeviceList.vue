<template>
  <div class="device-list-container">
    <div class="toolbar">
      <el-input
        v-model="search"
        placeholder="搜索设备ID或型号"
        style="width: 300px"
        clearable
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" @click="fetchDevices" :icon="Refresh">刷新</el-button>
    </div>

    <el-table :data="filteredDevices" style="width: 100%" v-loading="loading" stripe>
      <el-table-column prop="device_id" label="设备ID" min-width="150" sortable />
      <el-table-column prop="model" label="型号" width="150" sortable />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'">
            {{ scope.row.status === 'active' ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="last_seen" label="最后在线" width="180" sortable />
      <el-table-column prop="os_version" label="系统版本" width="120" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="scope">
          <el-button size="small" @click="$router.push(`/devices/${scope.row.device_id}`)">
            详情
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { getDevices } from '@/api';
import type { Device } from '@/api/types';

const devices = ref<Device[]>([]);
const loading = ref(false);
const search = ref('');

// 获取设备列表
const fetchDevices = async () => {
  loading.value = true;
  try {
    const res = await getDevices();
    devices.value = res.data;
  } catch (error) {
    ElMessage.error('获取设备列表失败');
  } finally {
    loading.value = false;
  }
};

// 根据搜索关键词过滤设备
const filteredDevices = computed(() => {
  if (!search.value) return devices.value;
  const q = search.value.toLowerCase();
  return devices.value.filter(d =>
    d.device_id.toLowerCase().includes(q) ||
    (d.model && d.model.toLowerCase().includes(q))
  );
});

onMounted(fetchDevices);
</script>

<style scoped>
.device-list-container {
  padding: 20px;
}
.toolbar {
  margin-bottom: 20px;
  display: flex;
  gap: 10px;
}
</style>
