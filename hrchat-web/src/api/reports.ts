import { get, post, del as deleteReq } from './http'
import type { AnalysisPeriod, AnalysisTask } from './attribution'
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
  series: Array<{ name: string; data: Array<number | null> }>
  pieData: Array<{ name: string; value: number }>
}

/** 洞察任务状态：与后端 InsightTaskView.status 对齐。 */
export type InsightStatus =
  | 'NONE'
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETE'
  | 'DEGRADED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED'

/** 只读图文视图：来自持久化数据版本，GET 不触发取数（也不触发模型调用）。 */
export interface ChartAnalysisView {
  dataVersion: string | null
  viewKey: string
  dimValue: string | null
  chartStatus: 'READY' | 'NEED_GENERATE' | 'VIEW_NOT_DERIVABLE' | 'FAILED' | string
  insightStatus: InsightStatus
  /** 当前视图绑定的洞察任务与其尝试次数；无任务时为 null */
  taskId: number | null
  attempt: number | null
  chart: ChartDataView | null
  insight: InsightView | null
}

/** 洞察可执行的后续问数动作（后端 InsightView.actions）。 */
export interface InsightAction {
  actionId: string
  capabilityId: string
  question: string
  executable: boolean
}

/** 洞察任务视图（两个 POST 端点返回；幂等写入 + 异步生成）。 */
export interface InsightTaskView {
  taskId: number
  attempt: number
  status: InsightStatus
  dataVersion: string
  viewKey: string
  scopeRef: string | null
  insightRevision: string
  degradationReason: string | null
  failureReason: string | null
  insightGeneratedAt: string | null
  insight: InsightView | null
  generation: Record<string, unknown> | null
}

export interface DataVersionView {
  dataVersionId: number
  versionNo: number
  status: string
  dataAsOf: string | null
  reportGeneratedAt: string | null
  failureReason: string | null
  published: boolean
}

export function getChartAnalysis(reportId: number, componentId: number, dimValue?: string) {
  return get<ApiResponse<ChartAnalysisView>>(`/reports/${reportId}/components/${componentId}/chart-analysis`, {
    params: dimValue ? { dimValue } : undefined,
  })
}

/** 幂等确保当前视图的洞察任务（仅在图表就绪但尚无洞察时调用），可能入队生成。 */
export function postInsightTask(reportId: number, componentId: number, dimValue?: string) {
  return post<ApiResponse<InsightTaskView>>(`/reports/${reportId}/components/${componentId}/insight-tasks`, null, {
    params: dimValue ? { dimValue } : undefined,
  })
}

/** 复用同一快照重试指定洞察任务（attempt 递增）。 */
export function retryInsightTask(reportId: number, componentId: number, taskId: number) {
  return post<ApiResponse<InsightTaskView>>(
    `/reports/${reportId}/components/${componentId}/insight-tasks/${taskId}/retry`,
    null,
  )
}

/** 报表来源可信分析范围（后端 publicContext + 报表字段），不含推荐动作能力信息。 */
export interface ReportAnalysisContext {
  sourceType: 'REPORT'
  sourceAskId: null
  sourceReportId: number
  sourceDataVersionId: number
  metricCode: string
  metricName: string
  unit: string
  scopeLabel: string
  currentPeriod: AnalysisPeriod
  suggestedBaselinePeriod: AnalysisPeriod | null
}

/** 报表进一步分析预填（I3-2）：可信范围 + 推荐动作能力校验与缺失参数。 */
export interface ReportAnalysisPrefill extends ReportAnalysisContext {
  scopeOrgIds: number[]
  capabilityId: string
  supported: boolean
  executable: boolean
  missingParams: string[]
}

export type ReportAnalysisTask = AnalysisTask<ReportAnalysisContext>

export interface ReportAnalysisStartRequest {
  dataVersionId: number
  baselinePeriod: AnalysisPeriod
  mode?: 'deterministic' | 'single' | 'dual'
}

/** 预填并校验推荐动作；页面确认参数后才创建任务。 */
export function getReportAnalysisContext(reportId: number, dataVersionId: number, capabilityId?: string) {
  return get<ApiResponse<ReportAnalysisPrefill>>(`/reports/${reportId}/analysis/context`, {
    params: capabilityId ? { dataVersionId, capabilityId } : { dataVersionId },
  })
}

/** 用户确认后创建报表来源分析任务（幂等）；进度/取消/事件复用既有任务端点。 */
export function startReportAnalysis(reportId: number, body: ReportAnalysisStartRequest, idempotencyKey: string) {
  return post<ApiResponse<ReportAnalysisTask>>(`/reports/${reportId}/analysis`, body, {
    headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
  })
}

/** 旧报表打开/首次确保当前数据版本（幂等） */
export function ensureReportDataVersion(reportId: number, idempotencyKey?: string) {
  return post<ApiResponse<DataVersionView>>(`/reports/${reportId}/data-versions/ensure`, null, {
    headers: idempotencyKey ? { [IDEMPOTENCY_HEADER]: idempotencyKey } : undefined,
  })
}

/** 显式刷新数据版本 */
export function refreshReportDataVersion(reportId: number, idempotencyKey?: string) {
  return post<ApiResponse<DataVersionView>>(`/reports/${reportId}/data-versions/refresh`, null, {
    headers: idempotencyKey ? { [IDEMPOTENCY_HEADER]: idempotencyKey } : undefined,
  })
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

/** AI 洞察（后端 ChartViews.InsightView；I1 可带状态与版本字段） */
export interface InsightView {
  reportName: string
  metricName: string
  summary: string
  points: Array<{ type: string; label: string }>
  /** 生成局限/免责说明 */
  limitations: string[]
  /** 可执行的后续问数动作 */
  actions: InsightAction[]
  status?: string | null
  degradationReason?: string | null
  dataVersion?: string | null
  viewKey?: string | null
}

/** 指标卡实时数据（后端 ChartViews.MetricCardView） */
export interface MetricCardView {
  metricCode: string
  metricName: string
  title: string
  value: number | null
  unit: string
  definition: string
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

export function getMetricCardData(reportId: number, componentId: number) {
  return get<ApiResponse<MetricCardView>>(`/reports/${reportId}/components/${componentId}/metric-card-data`)
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
