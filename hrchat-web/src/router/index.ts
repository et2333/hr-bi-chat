import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth'
import { hasPerm } from '@/layouts/adminMenu'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    permission?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat',
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/chat/index.vue'),
    meta: { title: '对话工作台' },
  },
  {
    path: '/reports',
    name: 'reports',
    component: () => import('@/views/reports/index.vue'),
    meta: { title: '报表中心' },
  },
  {
    path: '/reports/editor',
    name: 'report-editor',
    component: () => import('@/views/reports/editor.vue'),
    meta: { title: '报表编辑器' },
  },
  {
    path: '/reports/:id',
    name: 'report-detail',
    component: () => import('@/views/reports/detail.vue'),
    props: true,
    meta: { title: '报表查看' },
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/admin/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'AdminDashboard',
        component: () => import('@/views/admin/dashboard/index.vue'),
        meta: { title: '管理概览', permission: 'admin:view' },
      },
      {
        path: 'roles',
        name: 'AdminRoles',
        component: () => import('@/views/admin/roles/index.vue'),
        meta: { title: '角色权限', permission: 'admin:authz:manage' },
      },
      {
        path: 'audit',
        name: 'AdminAudit',
        component: () => import('@/views/admin/audit/index.vue'),
        meta: { title: '审计日志', permission: 'admin:audit:read' },
      },
      {
        path: 'settings',
        name: 'AdminSettings',
        component: () => import('@/views/admin/settings/index.vue'),
        meta: { title: '系统设置', permission: 'admin:system:manage' },
      },
      {
        path: 'data',
        name: 'AdminData',
        component: () => import('@/views/admin/data/index.vue'),
        meta: { title: '数据与同步', permission: 'admin:data:read' },
      },
      {
        path: 'eval',
        name: 'AdminEval',
        component: () => import('@/views/admin/eval/index.vue'),
        meta: { title: '问句评测', permission: 'admin:eval:manage' },
      },
      {
        path: 'semantic',
        name: 'SemanticAdmin',
        component: () => import('@/views/admin/semantic/index.vue'),
        meta: { title: '语义层管理', permission: 'admin:semantic' },
      },
      {
        path: 'llm',
        name: 'LlmAdmin',
        component: () => import('@/views/admin/llm/index.vue'),
        meta: { title: 'LLM 配置', permission: 'admin:llm:manage' },
      },
      {
        path: 'users',
        name: 'UserAdmin',
        component: () => import('@/views/admin/users/index.vue'),
        meta: { title: '用户管理', permission: 'admin:user:manage' },
      },
      {
        path: 'tenants',
        name: 'TenantAdmin',
        component: () => import('@/views/admin/tenants/index.vue'),
        meta: { title: '租户管理', permission: 'admin:tenant:manage' },
      },
    ],
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/views/profile/index.vue'),
    meta: { title: '个人中心' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局权限守卫：目标路由声明了 meta.permission 且当前角色无权限时，回到 C 端并提示。
router.beforeEach(async (to) => {
  const required = to.meta.permission
  const auth = useAuthStore()
  try {
    await auth.refreshPermissions()
  } catch {
    if (required) return { path: '/chat' }
  }
  if (!required) return true
  const perms = auth.functionPerms
  if (!hasPerm(perms, required)) {
    message.warning('当前身份无权限访问该页面')
    return { path: '/chat' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - HR智能问数` : 'HR智能问数'
})

export default router
