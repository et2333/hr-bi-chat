/**
 * 后端接口契约类型（对齐 HR智能问数接口设计文档 + 后端实际返回，camelCase）。
 */

/** 统一响应包装 */
export interface ApiResponse<T> {
  code: string
  message: string
  traceId: string
  data: T
}

/** 分页结果（后端 PageResult） */
export interface PageResult<T> {
  total: number
  records: T[]
  page: number
  size: number
}

// ---------------- 会话 / 问答 ----------------

export interface ChatSession {
  id: number
  title: string
  status: number
  lastActiveAt: string
  pinned: boolean
  createdAt: string
}

export interface TurnView {
  turnId: number
  question: string
  intent: string
  status: string
  conclusionBrief: string
  askId: string
  createdAt: string
}

export interface SqlView {
  askId: string
  sql: string
  caliber: Record<string, string>
}

export interface FeedbackRequest {
  rating: 'UP' | 'DOWN'
  reason?: string
  comment?: string
}

/** SSE 帧（event + data 内 JSON） */
export interface SSEFrame {
  seq: number
  event: string
  ts: string
  payload: Record<string, unknown>
}

/** ANSWER_DONE.payload 完整结构 */
export interface AnswerPayload {
  askId: string
  answerId: string
  status: string
  intent: string
  degraded: boolean
  degradedTip?: string
  conclusion: Conclusion | null
  table: TableData | null
  chart: Chart | null
  caliber: Caliber | null
  followups: string[]
  elapsedMs?: number
}

export interface Conclusion {
  type: 'NUMBER_CARD' | 'TEXT'
  value: unknown
  unit?: string
  compare?: Compare | null
}

export interface Compare {
  period: string
  value: unknown
  direction: string
}

export interface TableData {
  columns: Column[]
  rows: Array<Record<string, unknown>>
  total: number
  page: number
  size: number
}

export interface Column {
  key: string
  name: string
  type: string
  masked: boolean
}

export interface Chart {
  type: string
  recommended: boolean
  config: Record<string, unknown>
}

export interface Caliber {
  /** 展示名（如「在职人数」） */
  metric: string
  /** 语义层 code（如 headcount）；存报表 def.metric 必须用此字段 */
  metricCode?: string
  definition: string
  timeRange?: string
  organization?: string
  queryMode?: string
  dataUpdatedAt: string
}

/** INTERRUPT 澄清载荷 */
export interface ClarifyQuestions {
  interruptType: string
  askId: string
  questions: ClarifyQuestion[]
}

/** 澄清应答请求体（POST …/asks/{askId}/clarifications） */
export interface ClarifyAnswerRequest {
  answers: Array<{ questionId: string; optionIds: string[] }>
}

export interface ClarifyQuestion {
  questionId: string
  question: string
  options: ClarifyOption[]
  multiple: boolean
}

export interface ClarifyOption {
  optionId: string
  label: string
}

// ---------------- 报表 ----------------

export interface ReportSummary {
  id: number
  name: string
  sourceType: string
  ownerId: number
  ownerName: string
  createdAt: string
  subscriptionCount: number
}

export interface ComponentView {
  componentId: number
  compType: string
  chartType: string | null
  def: Record<string, unknown>
}

export interface SubscriptionView {
  id: number
  reportId: number
  frequency: string
  channel: string
  status: number
  nextRunAt: string
  receiverCount: number
}

export interface ReportDetail {
  id: number
  name: string
  sourceType: string
  params: Record<string, unknown>
  components: ComponentView[]
  refresh: string
  createdAt: string
  subscriptions: SubscriptionView[]
}

export interface SnapshotView {
  id: number
  reportId: number
  generatedAt: string
  expireAt: string
}

export interface ExportTaskView {
  exportId: string
  status: string
  downloadUrl: string
  expiresAt: string
  rowCount: number
}

export interface TemplateItem {
  id: string
  name: string
  category: string
  description: string
}

export interface TemplateDetail extends TemplateItem {
  paramsSchema: Record<string, string>
}

// ---------------- 管理后台 ----------------

export interface DatasourceView {
  dsCode: string
  dsName: string
  syncMode: string
  status: number
  lastSyncAt?: string
  lastSyncState?: string
  latestBizDate?: string
}

export interface SyncJobView {
  jobId: number
  dsCode: string
  dsName: string
  taskType: string
  bizDate: string
  execState: string
  rowsRead: number
  rowsWritten: number
  startedAt: string
  finishedAt: string
}

export interface QualitySummary {
  bizDate: string
  totalJobs: number
  success: number
  failed: number
  running: number
  delayed: number
  completeness: number
}

export interface AuditLog {
  logId: string
  userNo: string
  userName: string
  action: string
  resource: string
  questionDigest?: string
  sqlDigest?: string
  rows?: number
  fileName?: string
  ip: string
  sensitive: boolean
  ts: string
}

export interface RoleView {
  roleId: number
  roleCode: string
  roleName: string
  dataLevel: number
  functionPerms: string[]
}

export interface DataScopeView {
  orgNodeId: number
  orgName: string
  scope: number
  orgLevel: number
}

export interface EffectivePermissions {
  userId: string
  roles: string[]
  dataScopes: DataScopeView[]
  fieldPolicies: string[]
  functionPerms: string[]
  effectiveAt: string
}

export interface QuestionSetView {
  setId: string
  name: string
  questionCount: number
  createdAt: string
}

export interface RunResultView {
  runId: string
  status: string
  accuracy: number
  factAccuracy: number
  passed: number
  total: number
  failedQuestions: string[]
}

export interface QuestionSetCreateRequest {
  name: string
  questions: QuestionSpec[]
}

export interface QuestionSpec {
  question: string
  sceneTag?: string
  expectJson?: string
}

// ---------------- 管理后台：概览 / 设置 / 角色 ----------------

/** 配额使用率（单项） */
export interface QuotaItem {
  used: number
  total: number
  percent: number
}

/** 近 7 日审计趋势项 */
export interface AuditTrendItem {
  date: string
  count: number
}

/** LLM 模型健康项 */
export interface LlmHealthItem {
  modelCode: string
  modelName: string
  deployState: string
  healthStatus: string
}

/** 管理概览（GET /admin/dashboard） */
export interface DashboardView {
  tenantCount: number
  userCount: number
  reportCount: number
  subscriptionCount: number
  quotaUsage: {
    user: QuotaItem
    report: QuotaItem
    subscription: QuotaItem
    api: QuotaItem
  }
  llm: {
    items: LlmHealthItem[]
  }
  auditTrend: AuditTrendItem[]
  /** 系统默认 LLM 档位 */
  defaultLlmProfile: string
}

/** 系统设置项（GET /admin/settings） */
export interface SysSetting {
  settingKey: string
  settingValue: string
  description?: string
  updatedAt: string
}

/** 新建角色请求体（POST /admin/authz/roles） */
export interface RoleCreateRequest {
  roleCode: string
  roleName: string
  dataLevel: number
  functionPerms: string[]
}
