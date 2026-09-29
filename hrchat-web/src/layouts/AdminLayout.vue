<template>
  <a-layout class="admin-layout">
    <!-- 无管理权限：整页提示 -->
    <a-result v-if="noPermission" status="403" title="无权限访问管理后台" sub-title="当前身份没有管理后台权限，请联系系统管理员。">
      <template #extra>
        <a-button type="primary" @click="router.push('/chat')">返回对话工作台</a-button>
      </template>
    </a-result>

    <template v-else>
      <a-layout-sider width="200" theme="dark" class="admin-sider">
        <div class="sider-brand">
          <setting-outlined />
          <span>管理后台</span>
        </div>
        <a-menu
          v-model:selectedKeys="selectedKeys"
          theme="dark"
          mode="inline"
          class="admin-menu"
          @click="onMenuClick"
        >
          <a-menu-item v-for="item in visibleMenus" :key="item.path">
            <template #icon><component :is="iconOf(item.key)" /></template>
            {{ item.title }}
          </a-menu-item>
        </a-menu>
      </a-layout-sider>

      <a-layout class="admin-main">
        <a-layout-header class="admin-header">
          <div class="header-title">{{ route.meta.title ?? '管理后台' }}</div>
          <div class="header-right">
            <a-tag class="tenant-tag">租户：{{ tenantNo }}</a-tag>
            <span class="user-text">{{ auth.currentUser.name }}（{{ auth.currentUser.empNo }}）</span>
            <a-button size="small" @click="router.push('/chat')">返回 C 端</a-button>
          </div>
        </a-layout-header>
        <a-layout-content class="admin-content">
          <router-view />
        </a-layout-content>
      </a-layout>
    </template>
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ApiOutlined,
  ApartmentOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  PartitionOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
} from '@ant-design/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { TENANT_NO_KEY } from '@/api/http'
import { ADMIN_MENUS, menuPermsOf, visible } from './adminMenu'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 当前角色对应的功能权限码集合 */
const perms = computed(() => menuPermsOf(auth.currentUser.role))
/** 无任何管理权限（其他角色）：整页提示 */
const noPermission = computed(() => perms.value.length === 0)

const visibleMenus = computed(() => ADMIN_MENUS.filter((item) => visible(item, perms.value)))
const selectedKeys = computed(() => [route.path])
const tenantNo = localStorage.getItem(TENANT_NO_KEY) || 't01'

const ICON_MAP: Record<string, Component> = {
  dashboard: DashboardOutlined,
  tenants: ApartmentOutlined,
  users: TeamOutlined,
  roles: SafetyCertificateOutlined,
  semantic: PartitionOutlined,
  llm: ApiOutlined,
  audit: FileSearchOutlined,
  settings: SettingOutlined,
  data: DatabaseOutlined,
  eval: ExperimentOutlined,
}

function iconOf(key: string): Component {
  return ICON_MAP[key] ?? SettingOutlined
}

function onMenuClick({ key }: { key: string }) {
  router.push(key)
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-sider {
  background: #001529;
}

.sider-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 64px;
  padding: 0 20px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.admin-menu {
  border-inline-end: none;
}

.admin-main {
  background: #f0f2f5;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-text {
  color: rgba(0, 0, 0, 0.85);
}

.admin-content {
  padding: 24px;
}
</style>
