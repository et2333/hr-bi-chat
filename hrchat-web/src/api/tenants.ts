import { get, post, patch } from './http'
import type { ApiResponse, PageResult } from './types'

// ---------------- 租户管理（/admin/tenants） ----------------

/** 租户列表项（对齐 TenantViews.TenantView，camelCase） */
export interface TenantView {
  id: number
  tenantCode: string
  tenantName: string
  /** 1 启用 / 0 停用 */
  status: number
  userQuota: number
  reportQuota: number
  subscriptionQuota: number
  apiDailyQuota: number
  createdAt: string
}

/** 创建租户请求（对齐 TenantViews.TenantCreateRequest） */
export interface TenantCreateRequest {
  tenantCode: string
  tenantName: string
  userQuota: number
  reportQuota: number
  subscriptionQuota: number
  apiDailyQuota: number
}

/** 更新租户请求（名称/配额，对齐 TenantViews.TenantPatchRequest，不含 tenantCode） */
export interface TenantPatchRequest {
  tenantName: string
  userQuota: number
  reportQuota: number
  subscriptionQuota: number
  apiDailyQuota: number
}

/** 配额使用视图（对齐 TenantViews.UsageView，实时统计 vs 配额） */
export interface UsageView {
  tenantCode: string
  userUsed: number
  userQuota: number
  reportUsed: number
  reportQuota: number
  subscriptionUsed: number
  subscriptionQuota: number
  apiDailyUsed: number
  apiDailyQuota: number
}

export function listTenants(page = 1, size = 20, keyword = '') {
  return get<ApiResponse<PageResult<TenantView>>>('/admin/tenants', {
    params: { page, size, keyword },
  })
}

export function createTenant(body: TenantCreateRequest) {
  return post<ApiResponse<number>>('/admin/tenants', body)
}

export function patchTenant(id: number, body: TenantPatchRequest) {
  return patch<ApiResponse<void>>(`/admin/tenants/${id}`, body)
}

export function disableTenant(id: number) {
  return post<ApiResponse<void>>(`/admin/tenants/${id}:disable`)
}

export function enableTenant(id: number) {
  return post<ApiResponse<void>>(`/admin/tenants/${id}:enable`)
}

export function getTenantUsage(id: number) {
  return get<ApiResponse<UsageView>>(`/admin/tenants/${id}/usage`)
}
