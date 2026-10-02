import { get, post, patch, del as deleteReq } from './http'
import type { ApiResponse, PageResult } from './types'

// ---------------- LLM 大模型配置管理（/admin/llm） ----------------

/** 部署状态 */
export type LlmDeployState = 'PENDING' | 'APPLYING' | 'ACTIVE' | 'SIMULATED' | 'FAILED'
/** 健康状态 */
export type LlmHealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

export interface LlmModelView {
  id: number
  modelCode: string
  modelName: string
  vendor: string
  model: string
  /** 1 启用 / 0 停用 */
  status: number
  deployState: LlmDeployState
  healthStatus: LlmHealthStatus
  versionCount: number
  updatedAt: string
}

export interface LlmModelCreateRequest {
  modelCode: string
  modelName: string
  vendor: string
  baseUrl?: string
  apiKey?: string
  model?: string
  temperature?: number
  maxTokens?: number
  deployUrl?: string
}

export interface LlmVersionView {
  id: number
  versionNo: string
  configJson: string
  applyResult: 'PENDING' | 'SUCCESS' | 'SIMULATED' | 'FAILED' | 'ROLLBACK'
  appliedAt: string
  appliedBy: string
  changeNote: string
}

export interface DeployStateView {
  configId: number
  state: string
  healthStatus: LlmHealthStatus
  latencyMs: number
  llmProfile: string
  lastCheckedAt: string
}

export interface HealthView {
  modelCode: string
  state: string
  healthStatus: LlmHealthStatus
  latencyMs: number
  llmProfile: string
  lastCheckedAt: string
}

export interface MonitorItem {
  configId: number
  modelCode: string
  modelName: string
  deployState: LlmDeployState
  healthStatus: LlmHealthStatus
  versionCount: number
  lastCheckedAt: string
}

export interface MonitorView {
  total: number
  active: number
  failed: number
  degraded: number
  items: MonitorItem[]
}

export function listLlmModels(page = 1, size = 20, keyword = '') {
  return get<ApiResponse<PageResult<LlmModelView>>>('/admin/llm/models', {
    params: { page, size, keyword },
  })
}

export function createLlmModel(body: LlmModelCreateRequest) {
  return post<ApiResponse<number>>('/admin/llm/models', body)
}

export function patchLlmModel(id: number, body: LlmModelCreateRequest) {
  return patch<ApiResponse<void>>(`/admin/llm/models/${id}`, body)
}

export function deleteLlmModel(id: number) {
  return deleteReq<ApiResponse<void>>(`/admin/llm/models/${id}`)
}

export function listLlmVersions(id: number) {
  return get<ApiResponse<LlmVersionView[]>>(`/admin/llm/models/${id}/versions`)
}

export function deployLlmModel(id: number) {
  return post<ApiResponse<DeployStateView>>(`/admin/llm/models/${id}:deploy`)
}

export function rollbackLlmModel(id: number, body: { versionId: number }) {
  return post<ApiResponse<DeployStateView>>(`/admin/llm/models/${id}:rollback`, body)
}

export function checkLlmHealth(id: number) {
  return get<ApiResponse<HealthView>>(`/admin/llm/models/${id}/health`)
}

export function getLlmMonitor() {
  return get<ApiResponse<MonitorView>>('/admin/llm/monitor')
}
