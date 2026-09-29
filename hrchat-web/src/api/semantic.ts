import { get, post, patch, del as deleteReq } from './http'
import type { ApiResponse, PageResult } from './types'

// ---------------- 语义层管理（/admin/semantic：指标/维度/同义词 + 版本审批流） ----------------

/** 指标列表项 */
export interface MetricSummary {
  id: number
  code: string
  name: string
  domain: string
  /** 1 启用 / 其他 停用 */
  status: number
  /** 生效中最高版本号 */
  effectiveVersion: number
  /** 待审批最高版本号（0 表示无） */
  pendingVersion: number
  updatedAt: string | null
}

/** 指标详情（含生效/待审批版本与可用维度） */
export interface MetricDetail {
  id: number
  code: string
  name: string
  domain: string
  formulaExpr: string
  calcScope: string
  defaultPeriod: string
  /** 1 越大越好 / -1 越小越好 */
  goodDirection: number
  status: number
  effectiveVersion: number
  pendingVersion: number
  /** 密级：1 公开 / 3 敏感 */
  permLevel: number
  availableDimensions: string[]
  updatedBy: string
  updatedAt: string | null
}

/** 新增指标请求 */
export interface MetricCreateRequest {
  name: string
  code: string
  domain: string
  definition: string
  formula: string
  defaultGrain: string
  availableDimensions: string[]
  sensitive: boolean
}

/** 指标部分更新请求（definition/formula 变更自动触发审批流） */
export interface MetricPatchRequest {
  name?: string
  definition?: string
  formula?: string
  defaultGrain?: string
  goodDirection?: number
  sensitive?: boolean
  availableDimensions?: string[]
}

/**
 * 版本状态：0 待审批 / 1 生效中 / 2 已驳回 / 3 历史
 */
export type MetricVersionStatus = 0 | 1 | 2 | 3

/** 指标版本历史项 */
export interface MetricVersionInfo {
  versionNo: number
  formulaExpr: string
  calcScope: string
  status: MetricVersionStatus
  submittedBy: string
  approvedBy: string | null
  effectiveAt: string | null
  changeNote: string | null
}

/** 提交审批结果 */
export interface SubmitApprovalResult {
  approvalId: number
  status: string
}

/** 审批通过结果 */
export interface ApproveResult {
  approvalId: number
  publishedVersion: number
  eventName: string
}

/** 维度列表项 */
export interface DimensionSummary {
  id: number
  code: string
  name: string
  /** 1 结构维度 / 2 枚举维度 / 3 区间维度 */
  dimType: number
}

/** 维度枚举值项 */
export interface DimensionValueItem {
  valueCode: string
  valueLabel: string
  parentCode: string | null
  sortNo: number
}

/** 维度详情（含枚举值与物理映射元数据） */
export interface DimensionDetail {
  id: number
  code: string
  name: string
  dimType: number
  refTable: string | null
  /** 来源表主键列；null 表示事实表属性维度 */
  keyColumn: string | null
  /** 展示值列 */
  valueColumn: string | null
  /** 层级父键列；null 表示不可下钻 */
  parentColumn: string | null
  /** 事实表外键列；null 与主键列同名 */
  factColumn: string | null
  /** 来源表时效标记列 */
  currentColumn: string | null
  values: DimensionValueItem[]
}

/** 维度创建/更新请求 */
export interface DimensionUpsertRequest {
  name: string
  code: string
  dimType: number
  refTable?: string | null
  keyColumn?: string | null
  valueColumn?: string | null
  parentColumn?: string | null
  factColumn?: string | null
  currentColumn?: string | null
  enumValues?: DimensionValueItem[]
}

/** 同义词列表项 */
export interface SynonymItem {
  id: number
  termGroup: string
  /** 1 指标 / 2 维度 */
  targetType: number
  targetId: number
  hitCount: number
}

/** 新增同义词组请求 */
export interface SynonymCreateRequest {
  group: string
  terms: string[]
  /** 目标定位串，形如 metric:turnover_rate / dimension:org */
  target: string
}

/** 指标启停请求：1 启用 / 0 停用 */
export interface MetricStatusRequest {
  status: number
}

/** 审批待办项（待审批版本 + 指标信息） */
export interface ApprovalTodoItem {
  approvalId: number
  metricId: number
  metricCode: string
  metricName: string
  versionNo: number
  submittedBy: string
  submittedAt: string | null
  calcScope: string | null
  formulaExpr: string
}

/** 引用血缘项（引用该指标/维度的报表组件） */
export interface LineageItem {
  reportId: number
  reportName: string
  componentId: number
  /** 1 图表 / 2 表格 */
  compType: number
  chartType: string | null
}

// ---------------- 接口封装 ----------------

/** 指标列表 */
export function listMetrics(params: {
  domain?: string
  status?: number
  keyword?: string
  page?: number
  size?: number
}) {
  return get<ApiResponse<PageResult<MetricSummary>>>('/admin/semantic/metrics', { params })
}

/** 新增指标（保存即生效，无需审批） */
export function createMetric(body: MetricCreateRequest) {
  return post<ApiResponse<number>>('/admin/semantic/metrics', body)
}

/** 指标详情 */
export function getMetric(metricId: number) {
  return get<ApiResponse<MetricDetail>>(`/admin/semantic/metrics/${metricId}`)
}

/** 部分更新（definition/formula 变更自动触发审批流） */
export function patchMetric(metricId: number, body: MetricPatchRequest) {
  return patch<ApiResponse<MetricDetail>>(`/admin/semantic/metrics/${metricId}`, body)
}

/** 删除指标（软删；被报表引用时后端拒绝） */
export function deleteMetric(metricId: number) {
  return deleteReq<ApiResponse<void>>(`/admin/semantic/metrics/${metricId}`)
}

/** 启用/停用指标 */
export function setMetricStatus(metricId: number, status: number) {
  return patch<ApiResponse<void>>(`/admin/semantic/metrics/${metricId}/status`, { status })
}

/** 指标引用血缘（引用该指标的报表组件） */
export function getMetricLineage(metricId: number) {
  return get<ApiResponse<LineageItem[]>>(`/admin/semantic/metrics/${metricId}/lineage`)
}

/** 审批待办列表（需 approve 权限） */
export function listApprovalTodos() {
  return get<ApiResponse<ApprovalTodoItem[]>>('/admin/semantic/approvals/todo')
}

/** 版本历史 */
export function listMetricVersions(metricId: number) {
  return get<ApiResponse<MetricVersionInfo[]>>(`/admin/semantic/metrics/${metricId}/versions`)
}

/** 提交审批：返回 approvalId（即待审批版本 id） */
export function submitMetricApproval(metricId: number) {
  return post<ApiResponse<SubmitApprovalResult>>(`/admin/semantic/metrics/${metricId}:submit-approval`, {})
}

/** 审批通过（发布新版本） */
export function approveMetric(approvalId: number) {
  return post<ApiResponse<ApproveResult>>(`/admin/semantic/approvals/${approvalId}:approve`, {})
}

/** 审批驳回 */
export function rejectMetric(approvalId: number, comment: string) {
  return post<ApiResponse<void>>(`/admin/semantic/approvals/${approvalId}:reject`, { comment })
}

/** 维度列表 */
export function listDimensions(params: { keyword?: string; page?: number; size?: number }) {
  return get<ApiResponse<PageResult<DimensionSummary>>>('/admin/semantic/dimensions', { params })
}

/** 新增维度 */
export function createDimension(body: DimensionUpsertRequest) {
  return post<ApiResponse<number>>('/admin/semantic/dimensions', body)
}

/** 更新维度 */
export function patchDimension(dimensionId: number, body: DimensionUpsertRequest) {
  return patch<ApiResponse<DimensionDetail>>(`/admin/semantic/dimensions/${dimensionId}`, body)
}

/** 维度详情 */
export function getDimension(dimensionId: number) {
  return get<ApiResponse<DimensionDetail>>(`/admin/semantic/dimensions/${dimensionId}`)
}

/** 删除维度（软删；被指标可用维度/同义词/报表引用时后端拒绝） */
export function deleteDimension(dimensionId: number) {
  return deleteReq<ApiResponse<void>>(`/admin/semantic/dimensions/${dimensionId}`)
}

/** 维度引用血缘（引用该维度的报表组件） */
export function getDimensionLineage(dimensionId: number) {
  return get<ApiResponse<LineageItem[]>>(`/admin/semantic/dimensions/${dimensionId}/lineage`)
}

/** 同义词组列表 */
export function listSynonyms(params: { keyword?: string; page?: number; size?: number }) {
  return get<ApiResponse<PageResult<SynonymItem>>>('/admin/semantic/synonyms', { params })
}

/** 新增同义词组 */
export function createSynonym(body: SynonymCreateRequest) {
  return post<ApiResponse<number>>('/admin/semantic/synonyms', body)
}

/** 删除同义词组（单条） */
export function deleteSynonym(synonymId: number) {
  return deleteReq<ApiResponse<void>>(`/admin/semantic/synonyms/${synonymId}`)
}
