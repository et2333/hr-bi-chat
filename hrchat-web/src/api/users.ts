import { get, post, patch } from './http'
import type { ApiResponse, PageResult } from './types'

// ---------------- 用户管理（/admin/users） ----------------

/** 用户列表项（对齐 UserViews.UserView，camelCase） */
export interface UserView {
  id: number
  empNo: string
  displayName: string
  email: string
  orgNodeId: number | null
  orgName: string
  /** 1 启用 / 0 停用 */
  status: number
  tenantId: string
  /** 1 需改密 / 0 无需 */
  mustChangePwd: number
  roles: string[]
  lastLoginAt: string | null
  createdAt: string
}

/** 创建用户请求（对齐 UserViews.UserCreateRequest） */
export interface UserCreateRequest {
  empNo: string
  displayName: string
  email: string
  orgNodeId?: number | null
  roleCodes?: string[]
}

/** 创建成功视图（对齐 UserViews.CreateUserResultView，initialPassword 仅一次返回） */
export interface CreateUserResultView {
  userId: number
  initialPassword: string
}

/** 重置密码成功视图（对齐 UserViews.ResetResultView，initialPassword 仅一次返回） */
export interface ResetResultView {
  userId: number
  initialPassword: string
}

export function listUsers(page = 1, size = 20, keyword = '') {
  return get<ApiResponse<PageResult<UserView>>>('/admin/users', {
    params: { page, size, keyword },
  })
}

export function createUser(body: UserCreateRequest) {
  return post<ApiResponse<CreateUserResultView>>('/admin/users', body)
}

export function patchUserStatus(userId: number, status: number) {
  return patch<ApiResponse<void>>(`/admin/users/${userId}`, { status })
}

export function assignUserRoles(userId: number, roleCodes: string[]) {
  return post<ApiResponse<void>>(`/admin/users/${userId}/roles`, { roleCodes })
}

export function resetUserPassword(userId: number) {
  return post<ApiResponse<ResetResultView>>(`/admin/users/${userId}:reset-password`)
}
