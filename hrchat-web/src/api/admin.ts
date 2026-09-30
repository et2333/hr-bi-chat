import { get, post, patch, del as deleteReq } from './http'
import type {
  ApiResponse,
  AuditLog,
  DashboardView,
  DatasourceView,
  EffectivePermissions,
  PageResult,
  QualitySummary,
  QuestionSetCreateRequest,
  QuestionSetView,
  RoleCreateRequest,
  RoleView,
  RunResultView,
  SyncJobView,
  SysSetting,
} from './types'

// ---------------- 管理概览 ----------------

export function getDashboard() {
  return get<ApiResponse<DashboardView>>('/admin/dashboard')
}

// ---------------- 系统设置 ----------------

export function listSettings() {
  return get<ApiResponse<SysSetting[]>>('/admin/settings')
}

export function upsertSetting(body: { key: string; value: string; description?: string }) {
  return post<ApiResponse<void>>('/admin/settings', body)
}

export function deleteSetting(key: string) {
  return deleteReq<ApiResponse<void>>(`/admin/settings/${key}`)
}

// ---------------- 数据源与同步监控 ----------------

export function listDatasources() {
  return get<ApiResponse<DatasourceView[]>>('/admin/datasources')
}

export function listSyncJobs(date = '', status = '', page = 1, size = 20) {
  return get<ApiResponse<PageResult<SyncJobView>>>('/admin/sync-jobs', {
    params: { date, status, page, size },
  })
}

export function retrySyncJob(jobId: number) {
  return post<ApiResponse<void>>(`/admin/sync-jobs/${jobId}:retry`)
}

export function qualitySummary(date = '') {
  return get<ApiResponse<QualitySummary>>('/admin/data-quality/summary', { params: { date } })
}

// ---------------- 审计 ----------------

export function listAuditLogs(params: {
  userId?: string
  startTime?: string
  endTime?: string
  action?: string
  sensitiveOnly?: boolean
  page?: number
  size?: number
}) {
  return get<ApiResponse<PageResult<AuditLog>>>('/admin/audit/logs', { params })
}

// ---------------- 评测 ----------------

export function listQuestionSets() {
  return get<ApiResponse<PageResult<QuestionSetView>>>('/admin/evaluation/question-sets')
}

export function createQuestionSet(body: QuestionSetCreateRequest) {
  return post<ApiResponse<string>>('/admin/evaluation/question-sets', body)
}

export function runQuestionSet(setId: string) {
  return post<ApiResponse<RunResultView>>(`/admin/evaluation/question-sets/${setId}:run`)
}

export function getRun(runId: string) {
  return get<ApiResponse<RunResultView>>(`/admin/evaluation/runs/${runId}`)
}

// ---------------- 权限管理 ----------------

export function listRoles(page = 1, size = 20) {
  return get<ApiResponse<PageResult<RoleView>>>('/admin/authz/roles', { params: { page, size } })
}

export function createRole(body: RoleCreateRequest) {
  return post<ApiResponse<void>>('/admin/authz/roles', body)
}

export function effectivePermissions(userId: number) {
  return get<ApiResponse<EffectivePermissions>>(`/admin/authz/users/${userId}/effective-permissions`)
}

export { deleteReq as delete }

export function permissionCatalog() {
  return get<ApiResponse<string[]>>('/admin/authz/permission-catalog')
}

export function patchRole(roleId: number, body: RoleCreateRequest) {
  return patch<ApiResponse<void>>(`/admin/authz/roles/${roleId}`, body)
}
