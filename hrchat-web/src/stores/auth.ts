import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { get, del } from '@/api/http'
import type { ApiResponse } from '@/api/types'
import { TENANT_NO_KEY, TENANT_SWITCH_REASON_KEY, USER_NO_KEY } from '@/api/http'

/** 本地演示模式可用身份（对齐 Flyway V2 demo seed 的 mock 用户矩阵） */
export interface MockUser {
  empNo: string
  name: string
  role: string
  /** 所属租户；缺省沿用全局默认租户（t01） */
  tenant?: string
}

export const MOCK_USERS: MockUser[] = [
  { empNo: 'hr01', name: '张雨晴', role: 'HRBP' },
  { empNo: 'hr02', name: '李思远', role: 'HRBP' },
  { empNo: 'hr03', name: '王心怡', role: 'HR专员' },
  { empNo: 'hr04', name: '陈哲', role: 'HRD' },
  { empNo: 'pay01', name: '刘敏', role: '薪酬岗' },
  { empNo: 'cho01', name: '赵天明', role: 'CHO' },
  { empNo: 'adm01', name: '系统管理员', role: 'ADMIN' },
  { empNo: 'dat01', name: '数据管理员', role: 'DATA_ADMIN' },
  { empNo: 'test09', name: '测试用户', role: '-' },
  { empNo: 't02adm01', name: '租户管理员B', role: 'TENANT_ADMIN', tenant: 't02' },
]

/**
 * 身份 store：本地演示模式按工号切换用户（X-User-No）。
 * 生产接入 SSO 后保留 getToken/setToken 扩展点。
 */
export const useAuthStore = defineStore('auth', () => {
  const empNo = ref<string>(localStorage.getItem(USER_NO_KEY) || 'hr01')

  // 初始化自愈：普通身份（无租户归属）时强制复位默认租户 t01，
  // 避免上次切换租户管理员（t02）后残留的租户污染后续所有请求（含 SSE 问数）
  const bootUser = MOCK_USERS.find((u) => u.empNo === empNo.value)
  if (bootUser && !bootUser.tenant && (localStorage.getItem(TENANT_NO_KEY) ?? 't01') !== 't01') {
    localStorage.setItem(TENANT_NO_KEY, 't01')
  }

  const currentUser = computed<MockUser>(
    () => MOCK_USERS.find((u) => u.empNo === empNo.value) ?? { empNo: empNo.value, name: empNo.value, role: '-' },
  )

  const functionPerms = ref<string[]>([])
  const roles = ref<string[]>([])
  let requestVersion = 0
  const identityKey = () => [empNo.value, localStorage.getItem(TENANT_NO_KEY),
    sessionStorage.getItem(TENANT_SWITCH_REASON_KEY)].join('|')

  async function refreshPermissions() {
    const version = ++requestVersion
    const identity = identityKey()
    try {
      const response = await get<ApiResponse<{ roles: string[]; functionPerms: string[] }>>('/me/permissions')
      if (version === requestVersion && identity === identityKey()) {
        functionPerms.value = response.data.functionPerms
        roles.value = response.data.roles
      }
    } catch (error) {
      if (version === requestVersion && identity === identityKey()) {
        functionPerms.value = []
        roles.value = []
      }
      throw error
    }
  }

  /** 切换身份（持久化并全局生效；租户管理员身份同步切换其租户上下文，普通身份复位为默认租户 t01） */
  async function switchUser(userNo: string) {
    await del('/chat/query-contexts')
    ++requestVersion
    functionPerms.value = []
    roles.value = []
    empNo.value = userNo
    localStorage.setItem(USER_NO_KEY, userNo)
    const target = MOCK_USERS.find((u) => u.empNo === userNo)
    // 关键：切回普通身份必须复位租户，否则上一次租户管理员（t02）的租户会残留并污染后续请求
    localStorage.setItem(TENANT_NO_KEY, target?.tenant ?? 't01')
    sessionStorage.removeItem(TENANT_SWITCH_REASON_KEY)
  }

  return { empNo, currentUser, MOCK_USERS, switchUser, functionPerms, roles, refreshPermissions }
})
