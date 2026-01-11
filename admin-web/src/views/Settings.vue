<template>
  <div class="settings-container">
    <el-card class="settings-card">
      <template #header>
        <div class="card-header">
          <span>修改密码</span>
        </div>
      </template>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <el-form-item label="旧密码" prop="old_password">
          <el-input
            v-model="form.old_password"
            type="password"
            placeholder="请输入当前密码"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码" prop="new_password">
          <el-input
            v-model="form.new_password"
            type="password"
            placeholder="请输入新密码"
            show-password
            autocomplete="new-password"
            @change="handleNewPasswordChange"
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirm_password">
          <el-input
            v-model="form.confirm_password"
            type="password"
            placeholder="请再次输入新密码"
            show-password
            autocomplete="new-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            确认修改
          </el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { changePassword } from '../api';

const formRef = ref<FormInstance>();
const loading = ref(false);

const form = reactive({
  old_password: '',
  new_password: '',
  confirm_password: ''
});

const validateConfirmPassword = (rule: any, value: string, callback: any) => {
  if (value === '') {
    callback(new Error('请再次输入新密码'));
  } else if (value !== form.new_password) {
    callback(new Error('两次输入密码不一致!'));
  } else {
    callback();
  }
};

const rules = reactive<FormRules>({
  old_password: [
    { required: true, message: '请输入旧密码', trigger: 'blur' },
    { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
  ],
  new_password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
  ],
  confirm_password: [
    { required: true, validator: validateConfirmPassword, trigger: 'blur' }
  ]
});

const handleNewPasswordChange = () => {
  if (form.confirm_password) {
    formRef.value?.validateField('confirm_password');
  }
};

const handleSubmit = async () => {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();
    loading.value = true;
    try {
      await changePassword({ ...form });
      ElMessage.success('密码修改成功');
      formRef.value.resetFields();
    } catch (error: any) {
      const message = error.response?.data?.detail || '密码修改失败';
      ElMessage.error(message);
    } finally {
      loading.value = false;
    }
  } catch (error) {
    // 验证失败，不执行提交
  }
};

const resetForm = () => {
  if (!formRef.value) return;
  formRef.value.resetFields();
};
</script>

<style scoped>
.settings-container {
  padding: 20px;
  display: flex;
  justify-content: center;
}
.settings-card {
  width: 100%;
  max-width: 500px;
  margin-top: 20px;
}
.card-header {
  font-weight: bold;
}
</style>
