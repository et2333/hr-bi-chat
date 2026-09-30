/**
 * 管理后台菜单与所需功能码；用户授权由后端返回，不在前端按角色推导。
 * AdminLayout（侧边菜单）与 App.vue（管理入口按钮）共用。
 */

export interface AdminMenuItem {
  key: string
  path: string
  title: string
  /** 访问该菜单所需的功能权限码（对应路由 meta.permission） */
  requiredPerm?: string
}

/** 菜单项定义 */
export const ADMIN_MENUS: AdminMenuItem[] = [
  { key: 'dashboard', path: '/admin/dashboard', title: '概览', requiredPerm: 'admin:view' },
  { key: 'tenants', path: '/admin/tenants', title: '租户管理', requiredPerm: 'admin:tenant:manage' },
  { key: 'users', path: '/admin/users', title: '用户管理', requiredPerm: 'admin:user:manage' },
  { key: 'roles', path: '/admin/roles', title: '角色权限', requiredPerm: 'admin:authz:manage' },
  { key: 'semantic', path: '/admin/semantic', title: '语义层', requiredPerm: 'admin:semantic' },
  { key: 'llm', path: '/admin/llm', title: 'LLM 配置', requiredPerm: 'admin:llm:manage' },
  { key: 'audit', path: '/admin/audit', title: '审计日志', requiredPerm: 'admin:audit:read' },
  { key: 'settings', path: '/admin/settings', title: '系统设置', requiredPerm: 'admin:system:manage' },
  { key: 'data', path: '/admin/data', title: '数据与同步', requiredPerm: 'admin:data:read' },
  { key: 'eval', path: '/admin/eval', title: '问句评测', requiredPerm: 'admin:eval:manage' },
]

/** 权限匹配：支持 '*' 全量通配与 'xxx:*' 前缀通配 */
export function hasPerm(perms: string[], required: string): boolean {
  if (!required) return true
  return perms.some((p) => {
    if (p === required) return true
    if (p.endsWith(':*') && required.startsWith(p.slice(0, -1))) return true
    return false
  })
}

/** 菜单项对当前权限集合是否可见 */
export function visible(item: AdminMenuItem, perms: string[]): boolean {
  return hasPerm(perms, item.requiredPerm ?? '')
}
