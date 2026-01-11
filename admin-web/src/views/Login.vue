<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <div class="login-header">
          <h2>小智 Phone Agent 管理系统</h2>
        </div>
      </template>
      <el-form ref="loginFormRef" :model="loginForm" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" class="login-button" @click="handleLogin">
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { User, Lock } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { login } from '../api';

const router = useRouter();
const loginFormRef = ref<FormInstance>();
const loading = ref(false);

const loginForm = reactive({
  username: '',
  password: ''
});

const rules = reactive<FormRules>({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
  ]
});

const handleLogin = async () => {
  if (!loginFormRef.value) return;

  try {
    await loginFormRef.value.validate();
    loading.value = true;
    try {
      await login(loginForm);
      ElMessage.success('登录成功');
      router.push('/');
    } catch (error) {
      ElMessage.error('登录失败，请检查用户名和密码');
    } finally {
      loading.value = false;
    }
  } catch (error) {
    // 验证失败，不执行登录
  }
};
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #f0f2f5;
  background-image: url('data:image/svg+xml,%3Csvg width="64" height="64" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg"%3E%3Cpath d="M8 16c4.418 0 8-3.582 8-8s-3.582-8-8-8-8 3.582-8 8 3.582 8 8 8zm0-2c3.314 0 6-2.686 6-6s-2.686-6-6-6-6 2.686-6 6 2.686 6 6 6zm33.414-6l5.95-5.95L45.95.636 38.293 8.293 30.636.636 29.222 2.05 36.879 9.707l-5.95 5.95 1.414 1.414 5.95-5.95 5.95 5.95 1.414-1.414-5.95-5.95 5.95-5.95L41.414 8zM40 16c4.418 0 8-3.582 8-8s-3.582-8-8-8-8 3.582-8 8 3.582 8 8 8zm0-2c3.314 0 6-2.686 6-6s-2.686-6-6-6-6 2.686-6 6 2.686 6 6 6zM9 26c0-4.418-3.582-8-8-8s-8 3.582-8 8 3.582 8 8 8 8-3.582 8-8zm-2 0c0-3.314-2.686-6-6-6s-6 2.686-6 6 2.686 6 6 6 6-2.686 6-6zm77-10c-4.418 0-8 3.582-8 8s3.582 8 8 8 8-3.582 8-8-3.582-8-8-8zm0 2c3.314 0 6 2.686 6 6s-2.686 6-6 6-6-2.686-6-6 2.686-6 6-6zm-18 20c4.418 0 8-3.582 8-8s-3.582-8-8-8-8 3.582-8 8 3.582 8 8 8zm0-2c3.314 0 6-2.686 6-6s-2.686-6-6-6-6 2.686-6 6 2.686 6 6 6zM17 18c-4.418 0-8 3.582-8 8s3.582 8 8 8 8-3.582 8-8-3.582-8-8-8zm0 2c3.314 0 6 2.686 6 6s-2.686 6-6 6-6-2.686-6-6 2.686-6 6-6zM7 58c0-4.418-3.582-8-8-8s-8 3.582-8 8 3.582 8 8 8 8-3.582 8-8zm-2 0c0-3.314-2.686-6-6-6s-6 2.686-6 6 2.686 6 6 6 6-2.686 6-6zm77-10c-4.418 0-8 3.582-8 8s3.582 8 8 8 8-3.582 8-8-3.582-8-8-8zm0 2c3.314 0 6 2.686 6 6s-2.686 6-6 6-6-2.686-6-6 2.686-6 6-6zm-18 20c4.418 0 8-3.582 8-8s-3.582-8-8-8-8 3.582-8 8 3.582 8 8 8zm0-2c3.314 0 6-2.686 6-6s-2.686-6-6-6-6 2.686-6 6 2.686 6 6 6zM17 50c-4.418 0-8 3.582-8 8s3.582 8 8 8 8-3.582 8-8-3.582-8-8-8zm0 2c3.314 0 6 2.686 6 6s-2.686 6-6 6-6-2.686-6-6 2.686-6 6-6z" fill="%239C92AC" fill-opacity="0.05" fill-rule="evenodd"/%3E%3C/svg%3E');
}

.login-card {
  width: 400px;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.login-header {
  text-align: center;
  margin-bottom: 0;
}

.login-header h2 {
  margin: 0;
  color: #303133;
  font-size: 20px;
}

.login-button {
  width: 100%;
  margin-top: 10px;
}
</style>
