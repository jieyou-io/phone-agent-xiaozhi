<template>
  <div class="model-configs-container">
    <div class="toolbar">
      <h2>模型配置</h2>
      <el-button type="primary" @click="handleAdd" :icon="Plus">新增配置</el-button>
    </div>

    <el-table :data="configs" style="width: 100%" v-loading="loading">
      <el-table-column prop="provider" label="提供商" width="120" />
      <el-table-column prop="model" label="模型" width="150" />
      <el-table-column prop="scope_key" label="作用域" width="120" />
      <el-table-column prop="base_url" label="Base URL" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="scope">
          <el-button size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(scope.row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑配置' : '新增配置'" width="500px">
      <el-form ref="formRef" :model="form" label-width="120px" :rules="rules">
        <el-form-item label="提供商" prop="provider">
          <el-input v-model="form.provider" placeholder="例如: openai" />
        </el-form-item>
        <el-form-item label="模型名称" prop="model">
          <el-input v-model="form.model" placeholder="例如: gpt-4" />
        </el-form-item>
        <el-form-item label="Base URL" prop="base_url">
          <el-input v-model="form.base_url" placeholder="https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="API Key" prop="api_key">
          <el-input v-model="form.api_key" show-password />
        </el-form-item>
        <el-form-item label="作用域" prop="scope_key">
          <el-input v-model="form.scope_key" placeholder="例如: default" />
        </el-form-item>
        <el-form-item label="设备ID">
          <el-input v-model="form.owner_device_id" placeholder="可选" />
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
import { getModelConfigs, createModelConfig, updateModelConfig, deleteModelConfig } from '@/api';
import type { ModelConfig } from '@/api/types';

const configs = ref<ModelConfig[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const isEdit = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive<Partial<ModelConfig>>({
  provider: '',
  model: '',
  base_url: '',
  api_key: '',
  scope_key: '',
  owner_device_id: '',
});

// 表单验证规则
const rules = {
  provider: [{ required: true, message: '请输入提供商', trigger: 'blur' }],
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  base_url: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
  api_key: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  scope_key: [{ required: true, message: '请输入作用域', trigger: 'blur' }],
};

// 获取配置列表
const fetchConfigs = async () => {
  loading.value = true;
  try {
    const res = await getModelConfigs();
    configs.value = res.data;
  } catch (error) {
    ElMessage.error('获取配置列表失败');
  } finally {
    loading.value = false;
  }
};

// 新增配置
const handleAdd = () => {
  isEdit.value = false;
  Object.assign(form, {
    provider: '',
    model: '',
    base_url: '',
    api_key: '',
    scope_key: 'default',
    owner_device_id: ''
  });
  dialogVisible.value = true;
};

// 编辑配置
const handleEdit = (row: ModelConfig) => {
  isEdit.value = true;
  Object.assign(form, row);
  dialogVisible.value = true;
};

// 删除配置
const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除该配置吗？', '警告', { type: 'warning' });
    await deleteModelConfig(id);
    ElMessage.success('删除成功');
    fetchConfigs();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error('删除失败');
  }
};

// 提交表单
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
      provider: form.provider,
      model: form.model,
      base_url: form.base_url,
      api_key: form.api_key,
      scope_key: form.scope_key,
    };
    // 仅在有值时才包含 owner_device_id
    if (form.owner_device_id) {
      payload.owner_device_id = form.owner_device_id;
    }

    if (isEdit.value && form.id) {
      await updateModelConfig(form.id, payload);
      ElMessage.success('更新成功');
    } else {
      await createModelConfig(payload);
      ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    fetchConfigs();
  } catch (error) {
    ElMessage.error('操作失败');
  } finally {
    submitting.value = false;
  }
};

onMounted(fetchConfigs);
</script>

<style scoped>
.model-configs-container {
  padding: 20px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
