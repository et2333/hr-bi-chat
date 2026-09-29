<template>
  <a-config-provider :locale="zhCN">
    <a-layout class="app-layout">
    <a-layout-header class="app-header">
      <div class="brand">
        <span class="brand-logo">HR</span>
        <span class="brand-name">HR智能问数</span>
      </div>
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="horizontal"
        class="nav-menu"
        @click="onNavClick"
      >
        <a-menu-item key="/chat"><message-outlined /> 对话工作台</a-menu-item>
        <a-menu-item key="/reports"><bar-chart-outlined /> 报表中心</a-menu-item>
        <a-menu-item key="/profile"><user-outlined /> 个人中心</a-menu-item>
      </a-menu>
      <div class="user-area">
        <a-button v-if="hasAdminEntry" class="admin-entry" size="small" @click="goAdmin">
          <template #icon><setting-outlined /></template>
          管理后台
        </a-button>
        <a-dropdown>
          <a class="user-trigger">
            <a-avatar :size="28" class="user-avatar">{{ auth.currentUser.name.slice(0, 1) }}</a-avatar>
            <span class="user-name">{{ auth.currentUser.name }}（{{ auth.currentUser.empNo }}）</span>
            <down-outlined />
          </a>
          <template #overlay>
            <a-menu @click="onSwitchUser">
              <a-menu-item v-for="u in auth.MOCK_USERS" :key="u.empNo">
                <a-avatar :size="18" style="margin-right: 8px">{{ u.name.slice(0, 1) }}</a-avatar>
                {{ u.name }}（{{ u.empNo }}）· {{ u.role }}
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </a-layout-header>
      <a-layout-content class="app-content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BarChartOutlined,
  DownOutlined,
  MessageOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useAuthStore } from '@/stores/auth'
import { menuPermsOf } from '@/layouts/adminMenu'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const selectedKeys = computed(() => [route.path])

/** 当前身份是否有管理后台权限（menuPermsOf 返回非空） */
const hasAdminEntry = computed(() => menuPermsOf(auth.currentUser.role).length > 0)

function onNavClick({ key }: { key: string }) {
  router.push(key)
}

function goAdmin() {
  router.push('/admin/dashboard')
}

function onSwitchUser({ key }: { key: string }) {
  auth.switchUser(key)
}

watch(
  () => auth.empNo,
  () => {
    // 身份切换后刷新当前页数据
    router.go(0)
  },
)
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  background: #001529;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
  white-space: nowrap;
}

.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 6px;
  background: #1677ff;
  font-weight: 600;
  font-size: 14px;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
}

.nav-menu {
  flex: 1;
  min-width: 0;
  background: transparent;
  border-bottom: none;
}

.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
  color: rgba(255, 255, 255, 0.85);
}

.admin-entry {
  border-color: rgba(255, 255, 255, 0.45);
  color: rgba(255, 255, 255, 0.85);
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.85);
}

.user-avatar {
  background: #1677ff;
  color: #fff;
}

.app-content {
  padding: 24px;
  background: #f0f2f5;
}
</style>
