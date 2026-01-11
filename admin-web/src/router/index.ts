import { createRouter, createWebHistory } from 'vue-router';
import { checkAuth } from '../api';
import Layout from '../views/Layout.vue';
import Login from '../views/Login.vue';
import DeviceList from '../views/DeviceList.vue';
import DeviceDetail from '../views/DeviceDetail.vue';
import Skills from '../views/Skills.vue';
import ModelConfigs from '../views/ModelConfigs.vue';
import BaiduSpeech from '../views/BaiduSpeech.vue';
import Settings from '../views/Settings.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      component: Login
    },
    {
      path: '/',
      component: Layout,
      children: [
        { path: '', redirect: '/devices' },
        { path: 'devices', component: DeviceList },
        { path: 'devices/:id', component: DeviceDetail, props: true },
        { path: 'skills', component: Skills },
        { path: 'model-configs', component: ModelConfigs },
        { path: 'baidu-speech', component: BaiduSpeech },
        { path: 'settings', component: Settings },
      ]
    }
  ]
});

router.beforeEach(async (to, from, next) => {
  if (to.path === '/login') {
    try {
      await checkAuth();
      next('/');
    } catch (error) {
      next();
    }
  } else {
    try {
      await checkAuth();
      next();
    } catch (error: any) {
      if (error?.response?.status === 401 || error?.response?.status === 403) {
        next('/login');
      } else {
        console.error('Auth check failed:', error);
        next('/login');
      }
    }
  }
});

export default router;
