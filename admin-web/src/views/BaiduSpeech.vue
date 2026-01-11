<template>
  <div class="baidu-speech-container">
    <div class="toolbar">
      <h2>百度语音配置</h2>
      <el-button type="primary" @click="handleAdd" :icon="Plus">新增配置</el-button>
    </div>

    <el-table :data="configs" style="width: 100%" v-loading="loading">
      <el-table-column prop="owner_device_id" label="设备ID" width="300" />
      <el-table-column prop="app_id" label="App ID" width="200" />
      <el-table-column prop="api_key" label="API Key" width="200">
        <template #default="scope">
          <span>{{ maskSecret(scope.row.api_key) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="secret_key" label="Secret Key" width="200">
        <template #default="scope">
          <span>{{ maskSecret(scope.row.secret_key) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="scope">
          <el-button size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑配置' : '新增配置'" width="600px">
      <el-form ref="formRef" :model="form" label-width="120px" :rules="rules">
        <el-form-item label="设备ID" prop="owner_device_id">
          <el-input v-model="form.owner_device_id" :disabled="isEdit" placeholder="请输入设备ID（UUID格式）" />
        </el-form-item>
        <el-form-item label="App ID" prop="app_id">
          <el-input v-model="form.app_id" />
        </el-form-item>
        <el-form-item label="API Key" prop="api_key">
          <el-input v-model="form.api_key" show-password />
        </el-form-item>
        <el-form-item label="Secret Key" prop="secret_key">
          <el-input v-model="form.secret_key" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="submitForm" :loading="submitting">确定</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import {
  getBaiduSpeechConfig,
  createBaiduSpeechConfig,
  updateBaiduSpeechConfig,
  deleteBaiduSpeechConfig,
} from '@/api';
import type { BaiduSpeechConfig } from '@/api/types';

const configs = ref<BaiduSpeechConfig[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const isEdit = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive<Partial<BaiduSpeechConfig>>({
  owner_device_id: '',
  app_id: '',
  api_key: '',
  secret_key: '',
});

const rules = {
  owner_device_id: [{ required: true, message: '请输入设备ID', trigger: 'blur' }],
  app_id: [{ required: true, message: '请输入App ID', trigger: 'blur' }],
  api_key: [{ required: true, message: '请输入API Key', trigger: 'blur' }],
  secret_key: [{ required: true, message: '请输入Secret Key', trigger: 'blur' }],
};

const maskSecret = (secret: string) => {
  if (!secret) return '***';
  if (secret.length <= 4) return '***';
  const visible = secret.length <= 8 ? 1 : 4;
  return secret.substring(0, visible) + '***' + secret.substring(secret.length - visible);
};

const fetchConfigs = async () => {
  loading.value = true;
  try {
    const res = await fetch('/api/baidu-speech-configs', { credentials: 'include' });
    if (res.ok) {
      configs.value = await res.json();
    } else {
      ElMessage.error('获取配置列表失败');
    }
  } catch (error) {
    ElMessage.error('获取配置列表失败');
  } finally {
    loading.value = false;
  }
};

const handleAdd = () => {
  isEdit.value = false;
  Object.assign(form, {
    owner_device_id: '',
    app_id: '',
    api_key: '',
    secret_key: '',
  });
  dialogVisible.value = true;
};

const handleEdit = async (row: BaiduSpeechConfig) => {
  isEdit.value = true;
  try {
    const res = await getBaiduSpeechConfig(row.owner_device_id);
    Object.assign(form, res.data);
    dialogVisible.value = true;
  } catch (error) {
    ElMessage.error('加载配置失败');
  }
};

const handleDelete = async (row: BaiduSpeechConfig) => {
  try {
    await ElMessageBox.confirm('确定要删除该配置吗？', '警告', { type: 'warning' });
    await deleteBaiduSpeechConfig(row.owner_device_id);
    ElMessage.success('删除成功');
    fetchConfigs();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error('删除失败');
  }
};

const submitForm = async () => {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  submitting.value = true;
  try {
    const payload: any = {
      owner_device_id: form.owner_device_id,
      app_id: form.app_id,
      api_key: form.api_key,
      secret_key: form.secret_key,
    };

    if (isEdit.value) {
      await updateBaiduSpeechConfig(form.owner_device_id!, payload);
      ElMessage.success('更新成功');
    } else {
      await createBaiduSpeechConfig(payload);
      ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    fetchConfigs();
  } catch (error: any) {
    const message = error.response?.data?.detail || '操作失败';
    ElMessage.error(message);
  } finally {
    submitting.value = false;
  }
};

onMounted(fetchConfigs);
</script>

<style scoped>
.baidu-speech-container {
  padding: 20px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
