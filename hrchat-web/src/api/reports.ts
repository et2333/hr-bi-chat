import { get, post, del as deleteReq } from './http'
import type {
  ApiResponse,
  ExportTaskView,
  PageResult,
  ReportDetail,
  ReportSummary,
  SnapshotView,
  SubscriptionView,
  TemplateDetail,
  TemplateItem,
} from './types'

export const IDEMPOTENCY_HEADER = 'X-Idempotency-Key'

/** 创建/更新报表请求体（camelCase，对齐后端 ReportDtos） */
export interface ReportCreateRequest {
  sourceType: 'ASK' | 'TEMPLATE' | 'CUSTOM'
  sourceId?: string
  name: string
  folder?: string
  refresh?: { frequency: string; time: string }
  components?: ComponentSpec[]
  params?: Record<string, unknown>
}

export interface ComponentSpec {
  compType: 'CHART' | 'TABLE' | 'METRIC_CARD'
  chartType?: string
  def: Record<string, unknown>
}

export interface SubscribeRequest {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  channel: 'EMAIL' | 'WECOM' | 'DINGTALK' | 'FEISHU'
  receivers: Array<{ type: string; id: string }>
  pushTime?: string
  dayOfMonth?: number
}

export interface ExportCreateRequest {
  format: 'CSV' | 'XLSX' | 'PDF'
  filters?: Record<string, unknown>
}

/** 图表组件数据（后端 ChartViews.ChartDataView） */
export interface ChartDataView {
  chartType: string
  categories: string[]
  series: Array<{ name: string; data: number[] }>
  pieData: Array<{ name: string; value: number }>
}

/** 表格列定义（后端 ChartViews.TableColumn） */
export interface TableColumn {
  key: string
  title: string
  dataType: string
}

/** 表格数据（后端 ChartViews.TableView） */
export interface TableView {
  columns: TableColumn[]
  rows: Array<Record<string, unknown>>
  total: number
  page: number
  size: number
}

/** AI 洞察（后端 ChartViews.InsightView） */
export interface InsightView {
  reportName: string
  metricName: string
  summary: string
  points: Array<{ type: string; label: string }>
}

// ---------------- 报表 CRUD ----------------

export function listReports(scope: 'mine' | 'subscribed' | 'shared' | '' = 'mine', keyword = '', page = 1, size = 20) {
  return get<ApiResponse<PageResult<ReportSummary>>>('/reports', {
    params: { scope, keyword, page, size },
  })
}

export function createReport(body: ReportCreateRequest) {
  return post<ApiResponse<number>>('/reports', body)
}

export function getReport(reportId: number) {
  return get<ApiResponse<ReportDetail>>(`/reports/${reportId}`)
}

export function deleteReport(reportId: number) {
  return deleteReq<ApiResponse<void>>(`/reports/${reportId}`)
}

// ---------------- 订阅 ----------------

export function subscribe(reportId: number, body: SubscribeRequest, idempotencyKey?: string) {
  return post<ApiResponse<SubscriptionView>>(`/reports/${reportId}:subscribe`, body, {
    headers: idempotencyKey ? { [IDEMPOTENCY_HEADER]: idempotencyKey } : undefined,
  })
}

export function listSubscriptions(reportId: number) {
  return get<ApiResponse<SubscriptionView[]>>(`/reports/${reportId}/subscriptions`)
}

export function cancelSubscription(reportId: number, subscriptionId: number) {
  return deleteReq<ApiResponse<void>>(`/reports/${reportId}/subscriptions/${subscriptionId}`)
}

// ---------------- 模板库 ----------------

export function listTemplates(category = '', page = 1, size = 20) {
  return get<ApiResponse<PageResult<TemplateItem>>>('/report-templates', {
    params: { category, page, size },
  })
}

export function getTemplate(templateId: string) {
  return get<ApiResponse<TemplateDetail>>(`/report-templates/${templateId}`)
}

export function instantiateTemplate(templateId: string, params: Record<string, unknown>, name?: string) {
  return post<ApiResponse<number>>(`/report-templates/${templateId}:instantiate`, { params, name })
}

// ---------------- 快照 ----------------

export function listSnapshots(reportId: number, page = 1, size = 20) {
  return get<ApiResponse<PageResult<SnapshotView>>>(`/reports/${reportId}/snapshots`, {
    params: { page, size },
  })
}

export function getSnapshot(reportId: number, snapshotId: number) {
  return get<ApiResponse<SnapshotView>>(`/reports/${reportId}/snapshots/${snapshotId}`)
}

// ---------------- 图表数据 ----------------

export function getChartData(reportId: number, componentId: number, dimValue?: string) {
  return get<ApiResponse<ChartDataView>>(`/reports/${reportId}/components/${componentId}/chart-data`, {
    params: dimValue ? { dimValue } : undefined,
  })
}

/** 明细表数据（服务端筛选/排序/分页） */
export interface TableQueryParams {
  page: number
  size: number
  sortField?: string
  sortOrder?: string
  dimValue?: string[]
}

export function getTableData(reportId: number, componentId: number, params: TableQueryParams) {
  return get<ApiResponse<TableView>>(`/reports/${reportId}/components/${componentId}/data`, {
    params: {
      ...params,
      dimValue: params.dimValue && params.dimValue.length ? params.dimValue : undefined,
    },
  })
}

/** 明细表维度值列表（筛选下拉） */
export function getDimValues(reportId: number, componentId: number) {
  return get<ApiResponse<string[]>>(`/reports/${reportId}/components/${componentId}/dim-values`)
}

/** 图表 AI 洞察解读 */
export function getInsight(reportId: number, componentId: number) {
  return get<ApiResponse<InsightView>>(`/reports/${reportId}/components/${componentId}/insight`)
}

// ---------------- 导出 ----------------

export function createExport(reportId: number, body: ExportCreateRequest) {
  return post<ApiResponse<ExportTaskView>>(`/reports/${reportId}/exports`, body)
}

export function getExport(exportId: string) {
  return get<ApiResponse<ExportTaskView>>(`/exports/${exportId}`)
}

export function downloadExport(exportId: string): string {
  const userNo = localStorage.getItem('hrchat_user_no') || 'hr01'
  return `/api/v1/exports/${exportId}/download?X-User-No=${userNo}`
}
