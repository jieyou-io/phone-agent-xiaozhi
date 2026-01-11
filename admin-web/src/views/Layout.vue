<template>
  <el-container class="layout-container">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <h2>管理后台</h2>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="el-menu-vertical"
        router
      >
        <el-menu-item index="/devices">
          <el-icon><Monitor /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/skills">
          <el-icon><MagicStick /></el-icon>
          <span>技能管理</span>
        </el-menu-item>
        <el-menu-item index="/model-configs">
          <el-icon><Cpu /></el-icon>
          <span>模型配置</span>
        </el-menu-item>
        <el-menu-item index="/baidu-speech">
          <el-icon><Microphone /></el-icon>
          <span>语音配置</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <div class="header-content">小智 Phone Agent 管理系统</div>
        </div>
        <div class="header-right">
          <el-button link @click="handleLogout">
            <el-icon style="margin-right: 4px"><SwitchButton /></el-icon>
            退出登录
          </el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Monitor, MagicStick, Cpu, Microphone, SwitchButton, Setting } from '@element-plus/icons-vue';
import { logout } from '../api';
import { ElMessage } from 'element-plus';

const route = useRoute();
const router = useRouter();

const activeMenu = computed(() => `/${route.path.split('/')[1]}`);

const handleLogout = async () => {
  try {
    await logout();
    ElMessage.success('已退出登录');
    router.replace('/login');
  } catch (error) {
    ElMessage.error('退出失败');
  }
};
</script>

<style scoped>
.layout-container {
  height: 100vh;
}
.aside {
  background-color: #f5f7fa;
  border-right: 1px solid #e6e6e6;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid #e6e6e6;
}
.header {
  background-color: #fff;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.main {
  background-color: #fafafa;
  padding: 0;
}
</style>
