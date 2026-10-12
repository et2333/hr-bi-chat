<template>
  <div class="detail-page">
    <a-card :bordered="false" class="head-card">
      <div class="head-row">
        <div>
          <h2 class="report-title">{{ report?.name ?? '加载中…' }}</h2>
          <a-space>
            <a-tag :color="report?.sourceType === 'CUSTOM' ? 'blue' : 'green'">{{ report?.sourceType }}</a-tag>
            <span v-if="report?.refresh" class="refresh-text">刷新：{{ report.refresh }}</span>
            <span class="created-text">创建于 {{ report ? formatTime(report.createdAt) : '' }}</span>
          </a-space>
        </div>
        <a-space>
          <a-button @click="router.push('/reports')">
            <template #icon><ArrowLeftOutlined /></template>
            返回
          </a-button>
          <a-button v-if="report?.canRefresh" :loading="refreshing" :disabled="loading" @click="refreshData">刷新报表</a-button>
          <a-button type="primary" :disabled="!report?.currentDataVersionId || refreshing" @click="openExport">
            <template #icon><DownloadOutlined /></template>
            导出
          </a-button>
        </a-space>
      </div>
    </a-card>

    <!-- 组件 -->
    <a-card :bordered="false" title="报表组件" class="section-card">
      <a-empty v-if="!report?.components?.length" description="暂无组件" />
      <a-row :gutter="[12, 12]">
        <a-col v-for="c in report?.components" :key="c.componentId" :xs="24" :md="12">
          <div class="comp-box">
            <a-space wrap>
              <!-- CHART 的具体图型由下方切换条表达，不再重复打「柱状图」标签 -->
              <a-tag color="geekblue">{{ compTypeName(c.compType) }}</a-tag>
              <a-tag v-if="c.compType !== 'CHART' && c.chartType">{{ chartTypeName(c.chartType) }}</a-tag>
              <a-tag v-if="periodTag(c)" color="purple">期间：{{ periodTag(c) }}</a-tag>
            </a-space>

            <!-- CHART 组件：图表类型一键切换 + 组织下钻 + AI 洞察 -->
            <template v-if="c.compType === 'CHART'">
              <div class="chart-toolbar">
                <a-radio-group
                  size="small"
                  :value="displayChartType(c.componentId)"
                  @change="onChartTypeChange(c.componentId, $event)"
                >
                  <a-radio-button value="BAR">柱状</a-radio-button>
                  <a-radio-button value="LINE">折线</a-radio-button>
                  <a-radio-button value="PIE">饼图</a-radio-button>
                </a-radio-group>
                <template v-if="drillPathOf(c.componentId).length">
                  <a-button size="small" class="drill-back" @click="drillUp(c.componentId)">返回</a-button>
                  <a-breadcrumb class="drill-breadcrumb">
                    <a-breadcrumb-item>
                      <a @click="drillTo(c.componentId, 0)">全部</a>
                    </a-breadcrumb-item>
                    <a-breadcrumb-item v-for="(seg, i) in drillPathOf(c.componentId)" :key="i">
                      <a v-if="i < drillPathOf(c.componentId).length - 1" @click="drillTo(c.componentId, i + 1)">{{ seg }}</a>
                      <span v-else>{{ seg }}</span>
                    </a-breadcrumb-item>
                  </a-breadcrumb>
                  <a-tag color="orange" class="drill-tag">组织下钻</a-tag>
                </template>
              </div>
              <div class="comp-chart">
                <a-skeleton v-if="chartStateOf(c.componentId)?.loading" active :paragraph="{ rows: 4 }" />
                <template v-else-if="chartStateOf(c.componentId)?.data">
                  <ChartRenderer
                    :chart-type="displayChartType(c.componentId)"
                    :categories="chartStateOf(c.componentId)?.data?.categories ?? []"
                    :series="chartStateOf(c.componentId)?.data?.series ?? []"
                    :pie-data="chartStateOf(c.componentId)?.data?.pieData ?? []"
                    :drillable="isOrgDim(c) && displayChartType(c.componentId) === 'BAR'"
                    @bar-click="(cat: string) => drillTo(c.componentId, -1, cat)"
                  />
                  <!-- 下钻到叶子组织时提示 -->
                  <div
                    v-if="drillPathOf(c.componentId).length && !(chartStateOf(c.componentId)?.data?.categories.length)"
                    class="chart-empty"
                  >
                    该节点为叶子，无下级数据
                  </div>
                </template>
                <!-- 降级：加载失败或暂不支持的图表类型，展示定义 JSON -->
                <pre v-else class="comp-def">{{ prettyJson(c.def) }}</pre>
              </div>
              <!-- AI 洞察卡片 -->
              <div v-if="insightStateOf(c.componentId)" class="insight-card">
                <a-spin :spinning="insightStateOf(c.componentId)!.loading" size="small">
                  <div class="insight-head">
                    <span class="insight-title"><BulbOutlined /> AI 洞察</span>
                    <a-space size="small">
                      <a-tag :color="insightStatusColor(insightStateOf(c.componentId)!.status)">
                        {{ insightStatusName(insightStateOf(c.componentId)!.status) }}
                      </a-tag>
                      <a-tag v-if="insightStateOf(c.componentId)!.timedOut" color="orange">轮询超时</a-tag>
                      <a-button
                        v-if="canRetryInsight(c.componentId)"
                        size="small"
                        type="link"
                        @click="retryInsight(c.componentId)"
                      >
                        重试
                      </a-button>
                      <a-button size="small" type="link" :disabled="refreshing" @click="refreshData">刷新数据</a-button>
                    </a-space>
                  </div>
                  <template v-if="insightStateOf(c.componentId)!.data">
                    <p class="insight-summary">{{ insightStateOf(c.componentId)!.data!.summary }}</p>
                    <ul v-if="insightStateOf(c.componentId)!.data!.points.length" class="insight-points">
                      <li v-for="(pt, i) in insightStateOf(c.componentId)!.data!.points" :key="i">· {{ pt.label }}</li>
                    </ul>
                    <ul v-if="insightStateOf(c.componentId)!.data!.limitations?.length" class="insight-limitations">
                      <li v-for="(item, i) in insightStateOf(c.componentId)!.data!.limitations" :key="i">{{ item }}</li>
                    </ul>
                    <p v-if="insightDegradedNote(c.componentId)" class="insight-degraded">
                      降级说明：{{ insightDegradedNote(c.componentId) }}
                    </p>
                    <details v-if="insightEvidenceOf(c.componentId).length" class="insight-evidence">
                      <summary>查看证据（{{ insightEvidenceOf(c.componentId).length }} 条）</summary>
                      <ul class="insight-evidence-list">
                        <li v-for="(ev, i) in insightEvidenceOf(c.componentId)" :key="i">
                          <span class="insight-evidence-text">{{ ev.text }}</span>
                          <span v-if="ev.refs.length" class="insight-evidence-ref">〔{{ ev.refs.join('；') }}〕</span>
                        </li>
                      </ul>
                    </details>
                    <div v-if="insightStateOf(c.componentId)!.data!.actions?.length" class="insight-actions">
                      <a-tag
                        v-for="act in insightStateOf(c.componentId)!.data!.actions"
                        :key="act.actionId"
                        :color="isAnalyzable(act, c.componentId) ? 'blue' : 'default'"
                        :class="{ 'action-clickable': isAnalyzable(act, c.componentId) }"
                        :title="isAnalyzable(act, c.componentId) ? '点击在侧边面板中确认并分析' : undefined"
                        @click="openReportAnalysis(act, c.componentId)"
                      >
                        {{ act.question }}
                      </a-tag>
                    </div>
                  </template>
                  <p
                    v-else-if="insightStateOf(c.componentId)!.status === 'PENDING' || insightStateOf(c.componentId)!.status === 'RUNNING'"
                    class="insight-loading"
                  >洞察生成中…</p>
                  <p v-else-if="insightStateOf(c.componentId)!.status === 'NONE'" class="insight-pending">洞察待生成…</p>
                  <p v-else-if="insightStateOf(c.componentId)!.status === 'FAILED'" class="insight-error">洞察生成失败，可重试</p>
                  <p v-else-if="insightStateOf(c.componentId)!.status === 'INTERRUPTED'" class="insight-error">洞察生成已中断，可重试</p>
                  <p v-else-if="insightStateOf(c.componentId)!.status === 'CANCELLED'" class="insight-error">洞察已取消，可重试</p>
                  <p v-else-if="insightStateOf(c.componentId)!.loading" class="insight-loading">洞察加载中…</p>
                  <p v-else class="insight-pending">洞察暂不可用</p>
                </a-spin>
              </div>
            </template>

            <!-- TABLE 组件：维度筛选 + 服务端排序/分页 -->
            <template v-else-if="c.compType === 'TABLE'">
              <div class="table-toolbar">
                <a-select
                  v-if="tableStateOf(c.componentId)"
                  :value="tableDimValues(c.componentId)"
                  mode="multiple"
                  placeholder="维度筛选"
                  class="table-filter"
                  size="small"
                  allow-clear
                  :options="dimOptionsOf(c.componentId)"
                  @change="(vals: string[]) => applyTableFilter(c.componentId, vals)"
                />
              </div>
              <a-table
                v-if="tableStateOf(c.componentId)"
                :columns="tableColumnsOf(c.componentId)"
                :data-source="tableRowsOf(c.componentId)"
                :loading="tableStateOf(c.componentId)!.loading"
                :pagination="tablePaginationOf(c.componentId)"
                :scroll="{ x: 600 }"
                size="small"
                row-key="dimValue"
                @change="(pag: TablePag, _f: unknown, sorter: TableSorter) => onTableChange(c.componentId, pag, sorter)"
              />
            </template>

            <!-- METRIC_CARD：实时标量（同源授权查库），禁止裸 JSON / 静态假数 -->
            <template v-else-if="c.compType === 'METRIC_CARD'">
              <div class="metric-card-comp">
                <a-skeleton v-if="metricCardStateOf(c.componentId)?.loading" active :paragraph="{ rows: 2 }" />
                <template v-else>
                  <div class="metric-card-title">
                    {{ metricCardStateOf(c.componentId)?.data?.title ?? metricCardTitle(c.def) }}
                  </div>
                  <MetricCard
                    :conclusion="{
                      type: 'NUMBER_CARD',
                      value: metricCardDisplayValue(c.componentId, c.def),
                      unit: metricCardStateOf(c.componentId)?.data?.unit ?? String(c.def?.unit ?? ''),
                    }"
                    :caliber="{
                      metric: metricCardStateOf(c.componentId)?.data?.metricName
                        ?? String(c.def?.metric ?? ''),
                      definition: metricCardStateOf(c.componentId)?.data?.definition
                        ?? String(c.def?.definition ?? c.def?.title ?? ''),
                      dataUpdatedAt: '',
                    }"
                  />
                </template>
              </div>
            </template>

            <!-- 其他组件：展示定义 JSON -->
            <pre v-else class="comp-def">{{ prettyJson(c.def) }}</pre>
          </div>
        </a-col>
      </a-row>
    </a-card>

    <!-- 订阅 -->
    <a-card :bordered="false" title="订阅管理" class="section-card">
      <div class="section-actions">
        <a-button type="primary" size="small" @click="subModalVisible = true">
          <template #icon><PlusOutlined /></template>
          新建订阅
        </a-button>
      </div>
      <a-table
        :columns="subColumns"
        :data-source="subscriptions"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'frequency'">{{ freqName(record.frequency) }}</template>
          <template v-else-if="column.key === 'channel'">{{ record.channel }}</template>
          <template v-else-if="column.key === 'nextRunAt'">{{ formatDateTime(record.nextRunAt) }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-popconfirm title="确定取消该订阅？" @confirm="cancelSub(record.id)">
              <a-button size="small" type="link" danger>取消</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建订阅弹窗 -->
    <a-modal v-model:open="subModalVisible" title="新建订阅" :confirm-loading="subSubmitting" @ok="createSub">
      <a-form layout="vertical">
        <a-form-item label="频率" required>
          <a-select v-model:value="subForm.frequency">
            <a-select-option value="DAILY">每日</a-select-option>
            <a-select-option value="WEEKLY">每周</a-select-option>
            <a-select-option value="MONTHLY">每月</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="渠道" required>
          <a-select v-model:value="subForm.channel">
            <a-select-option value="EMAIL">邮件</a-select-option>
            <a-select-option value="WECOM">企业微信</a-select-option>
            <a-select-option value="DINGTALK">钉钉</a-select-option>
            <a-select-option value="FEISHU">飞书</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="推送时间" required>
          <a-time-picker v-model:value="subForm.pushTime" value-format="HH:mm" format="HH:mm" placeholder="09:00" />
        </a-form-item>
        <a-form-item label="收件人（工号，逗号分隔）" required>
          <a-input v-model:value="subForm.receivers" placeholder="如 hr02,hr03" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 导出弹窗 -->
    <a-modal v-model:open="exportVisible" title="导出报表" :footer="null">
      <div class="export-body">
        <a-select v-model:value="exportFormat" style="width: 120px">
          <a-select-option value="CSV">CSV</a-select-option>
          <a-select-option value="XLSX">XLSX</a-select-option>
          <a-select-option value="PDF">PDF</a-select-option>
        </a-select>
        <a-button type="primary" :loading="exporting" @click="createExportTask">发起导出</a-button>
      </div>
      <div v-if="exportTask" class="export-status">
        <p>状态：{{ exportTask.status }}</p>
        <p v-if="exportTask.rowCount">行数：{{ exportTask.rowCount }}</p>
        <p v-if="exportTask.expiresAt">下载链接有效期至 {{ formatDateTime(exportTask.expiresAt) }}</p>
        <a-button v-if="exportTask.downloadUrl" type="link" :href="downloadHref" :download="exportTask.exportId">
          点击下载
        </a-button>
      </div>
    </a-modal>

    <!-- 进一步分析侧边面板：由支持推荐动作打开，收起不取消，切换主图不影响运行中任务 -->
    <ReportAnalysisPanel
      v-model:open="analysisOpen"
      :report-id="reportId"
      :data-version-id="analysisDataVersionId"
      :capability-id="analysisCapabilityId"
      :component-id="analysisComponentId"
      :view-key="analysisViewKey"
      :action-id="analysisActionId"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, BulbOutlined, DownloadOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { reportApi } from '@/api'
import type { ComponentView, ReportDetail, SubscriptionView } from '@/api/types'
import type { MetricCardView, TableView, InsightAction } from '@/api/reports'
import { useReportChartAnalysis } from '@/composables/useReportChartAnalysis'
import ChartRenderer, { type ChartType } from '@/components/ChartRenderer.vue'
import MetricCard from '@/components/MetricCard.vue'
import ReportAnalysisPanel from '@/components/ReportAnalysisPanel.vue'
import { REPORT_ANALYSIS_CAPABILITY } from '@/utils/reportAnalysis'

const route = useRoute()
const router = useRouter()
const reportId = Number(route.params.id)

const report = ref<ReportDetail | null>(null)
const subscriptions = ref<SubscriptionView[]>([])
const loading = ref(false)

/** 进一步分析侧边面板：由支持推荐动作打开，运行中任务与主图/基础洞察互不影响。 */
const analysisOpen = ref(false)
const analysisCapabilityId = ref('')
const analysisDataVersionId = ref(0)
const analysisComponentId = ref(0)
const analysisViewKey = ref('')
const analysisActionId = ref('')

/** 当前视图绑定的洞察来源身份（含 viewKey/版本）；无来源时不可分析。 */
function insightSourceOf(componentId: number) {
  return insightStates.value[componentId]?.source ?? null
}

/** 仅首轮开放能力、报表已有成功版本且该视图洞察有可绑定来源的动作可点击。 */
function isAnalyzable(act: InsightAction, componentId: number): boolean {
  return act.capabilityId === REPORT_ANALYSIS_CAPABILITY
    && !!report.value?.currentDataVersionId
    && !!insightSourceOf(componentId)
}

/** A2：打开面板只回传服务端动作引用（组件/视图/动作）与来源版本，期间/组织由后端恢复。 */
function openReportAnalysis(act: InsightAction, componentId: number) {
  if (!isAnalyzable(act, componentId)) return
  const source = insightSourceOf(componentId)!
  const version = Number(source.dataVersion)
  analysisCapabilityId.value = act.capabilityId
  analysisDataVersionId.value = Number.isFinite(version) && version > 0
    ? version
    : report.value?.currentDataVersionId ?? 0
  analysisComponentId.value = componentId
  analysisViewKey.value = source.viewKey
  analysisActionId.value = act.actionId
  analysisOpen.value = true
}

// 图表与洞察从服务端共享快照同步更新。
const { chartStates, insightStates, loadChartData, retryInsight } = useReportChartAnalysis(reportId, (e) => {
  message.error(e instanceof Error ? e.message : '图表数据加载失败')
})

function chartStateOf(componentId: number) {
  return chartStates.value[componentId]
}

interface MetricCardState {
  loading: boolean
  data: MetricCardView | null
}
const metricCardStates = ref<Record<number, MetricCardState>>({})

function metricCardStateOf(componentId: number): MetricCardState | undefined {
  return metricCardStates.value[componentId]
}

async function loadMetricCardData(componentId: number) {
  metricCardStates.value[componentId] = { loading: true, data: null }
  try {
    const res = await reportApi.getMetricCardData(reportId, componentId)
    metricCardStates.value[componentId] = { loading: false, data: res.data }
  } catch (e) {
    metricCardStates.value[componentId] = { loading: false, data: null }
    message.error(e instanceof Error ? e.message : '指标卡数据加载失败')
  }
}

function metricCardDisplayValue(componentId: number, def?: Record<string, unknown> | null): unknown {
  const live = metricCardStateOf(componentId)?.data?.value
  if (live !== undefined && live !== null) return live
  if (def?.value !== undefined && def?.value !== null) return def.value
  return '—'
}

/** 图表类型一键切换（用户选择优先后端返回，其次组件声明） */
const chartTypeOverrides = ref<Record<number, ChartType>>({})

function setChartType(componentId: number, t: ChartType) {
  chartTypeOverrides.value[componentId] = t
}

function onChartTypeChange(componentId: number, e: { target: { value: string } }) {
  setChartType(componentId, (e.target.value ?? 'BAR') as ChartType)
}

function displayChartType(componentId: number): ChartType {
  const declared = report.value?.components?.find((x) => x.componentId === componentId)?.chartType
  return (chartTypeOverrides.value[componentId]
    ?? chartStateOf(componentId)?.data?.chartType
    ?? (declared ?? 'BAR').toUpperCase()) as ChartType
}

/** 组织维度下钻（drillPath 为逐级下钻的组织名序列，面包屑可回溯） */
const drillStates = ref<Record<number, string[]>>({})

function drillPathOf(componentId: number): string[] {
  return drillStates.value[componentId] ?? []
}

function isOrgDim(c: ComponentView): boolean {
  const dim = (c.def?.dim as string | undefined)
    ?? (Array.isArray(c.def?.dimensions) ? String((c.def.dimensions as unknown[])[0]) : undefined)
    ?? (Array.isArray(c.def?.dims) ? String((c.def.dims as unknown[])[0]) : undefined)
  return (dim || 'org').toLowerCase() === 'org'
}

/** level>=0：面包屑回溯到该级；level=-1 且 next 存在：向下钻取到 next 组织 */
function drillTo(componentId: number, level: number, next?: string) {
  let path = [...drillStates.value[componentId] ?? []]
  if (level >= 0) {
    path = path.slice(0, level)
  } else if (next) {
    path.push(next)
  }
  if (level === -1 && !next) return
  drillStates.value[componentId] = path
  const dimValue = path.length ? path[path.length - 1] : undefined
  loadChartData(componentId, dimValue)
}

/** 返回上一级；已在第一级时回到根视图（等价于点「全部」）。 */
function drillUp(componentId: number) {
  const path = drillStates.value[componentId] ?? []
  drillTo(componentId, Math.max(0, path.length - 1))
}

function insightStateOf(componentId: number) {
  return insightStates.value[componentId]
}

/** 洞察状态中文名与配色（待生成/不支持/进行中/完成/降级/失败）。 */
function insightStatusName(status?: string): string {
  return {
    MISSING: '待生成',
    NOT_APPLICABLE: '不支持洞察',
    NONE: '待生成',
    PENDING: '进行中',
    RUNNING: '进行中',
    COMPLETE: '完成',
    DEGRADED: '降级',
    FAILED: '失败',
    INTERRUPTED: '已中断',
    CANCELLED: '已取消',
  }[status ?? ''] ?? (status ?? '')
}

function insightStatusColor(status?: string): string {
  return {
    COMPLETE: 'green',
    DEGRADED: 'orange',
    FAILED: 'red',
    INTERRUPTED: 'red',
    CANCELLED: 'default',
    RUNNING: 'blue',
    PENDING: 'blue',
    MISSING: 'default',
    NOT_APPLICABLE: 'default',
    NONE: 'default',
  }[status ?? ''] ?? 'default'
}

/** 重试由后端 canRetry 判定（失败/降级/中断且快照可用）；进行中或成功不展示。 */
function canRetryInsight(componentId: number): boolean {
  return insightStateOf(componentId)?.canRetry === true
}

/** 失败/降级/中断时给出明确的降级说明，避免把确定性摘要伪装成正常 AI 解读。 */
function insightDegradedNote(componentId: number): string {
  const st = insightStateOf(componentId)
  if (!st) return ''
  const degraded = st.status === 'DEGRADED' || st.status === 'FAILED' || st.status === 'INTERRUPTED'
  if (!degraded) return ''
  return st.data?.degradationReason ?? '未能获得通过校验的模型解读，当前展示确定性摘要。'
}

/** 证据条目：claim 正文 + 其引用事实的期间/范围/值/依据/快照（不触发模型调用）。 */
interface InsightEvidenceItem {
  text: string
  refs: string[]
}

function insightEvidenceOf(componentId: number): InsightEvidenceItem[] {
  const data = insightStateOf(componentId)?.data
  if (!data) return []
  const facts = new Map((data.facts ?? []).map((f) => [f.fact_id, f]))
  return (data.claims ?? [])
    .map((claim) => {
      const text = (claim.text ?? '').trim()
      const refs = (claim.fact_ids ?? []).map((id) => {
        const fact = facts.get(id)
        if (!fact) return id
        const parts: string[] = []
        if (fact.period_ref) parts.push(`期间 ${fact.period_ref}`)
        if (fact.scope_ref) parts.push(`范围 ${fact.scope_ref}`)
        if (fact.value !== null && fact.value !== undefined) parts.push(`值 ${fact.value}${fact.unit ?? ''}`)
        if (fact.basis) parts.push(`依据 ${fact.basis}`)
        if (fact.snapshot_id) parts.push(`快照 ${fact.snapshot_id}`)
        return parts.length ? parts.join('，') : id
      })
      return { text, refs }
    })
    .filter((item) => item.text)
}

/** 明细表状态：服务端筛选/排序/分页 */
interface TableState {
  loading: boolean
  data: TableView | null
  page: number
  size: number
  sortField?: string
  sortOrder?: string
  dimValues: string[]
  dimOptions: string[]
}
const tableStates = ref<Record<number, TableState>>({})

function tableStateOf(componentId: number): TableState | undefined {
  return tableStates.value[componentId]
}

function tableColumnsOf(componentId: number) {
  const st = tableStateOf(componentId)
  return (st?.data?.columns ?? []).map((col) => ({
    title: col.title,
    key: col.key,
    dataIndex: col.key,
    sorter: true,
    sortDirections: ['ascend', 'descend'] as const,
    // 受控排序态，避免点了图标 UI 不回显
    sortOrder: st?.sortField === col.key
      ? (st.sortOrder === 'desc' ? 'descend' as const : 'ascend' as const)
      : null,
    width: col.dataType === 'number' ? 150 : 200,
  }))
}

function tableRowsOf(componentId: number) {
  return tableStateOf(componentId)?.data?.rows ?? []
}

function tablePaginationOf(componentId: number) {
  const st = tableStateOf(componentId)
  return {
    current: st?.page ?? 1,
    pageSize: st?.size ?? 10,
    total: st?.data?.total ?? 0,
    showSizeChanger: true,
    pageSizeOptions: ['5', '10', '20', '50'],
  }
}

function tableDimValues(componentId: number): string[] {
  return tableStateOf(componentId)?.dimValues ?? []
}

function dimOptionsOf(componentId: number) {
  return (tableStateOf(componentId)?.dimOptions ?? []).map((v) => ({ label: v, value: v }))
}

async function loadTableData(componentId: number) {
  const st = tableStateOf(componentId)
  if (!st) return
  st.loading = true
  try {
    const res = await reportApi.getTableData(reportId, componentId, {
      page: st.page,
      size: st.size,
      sortField: st.sortField,
      sortOrder: st.sortOrder,
      dimValue: st.dimValues,
    })
    st.data = res.data
  } catch (e) {
    st.data = null
    message.error(e instanceof Error ? e.message : '表格数据加载失败')
  } finally {
    st.loading = false
  }
}

function initTable(componentId: number) {
  if (tableStateOf(componentId)) return
  tableStates.value[componentId] = { loading: false, data: null, page: 1, size: 10, dimValues: [], dimOptions: [] }
  loadTableData(componentId)
  reportApi.getDimValues(reportId, componentId).then((res) => {
    const st = tableStateOf(componentId)
    if (st) st.dimOptions = res.data
  }).catch(() => {
    const st = tableStateOf(componentId)
    if (st) st.dimOptions = []
  })
}

/** ant-design-vue Table change：(pagination, filters, sorter)，不是单对象 */
type TablePag = { current?: number; pageSize?: number }
type TableSorterItem = {
  order?: 'ascend' | 'descend' | null
  columnKey?: string | number
  field?: string | number
}
type TableSorter = TableSorterItem | TableSorterItem[]

function onTableChange(componentId: number, pagination: TablePag, sorter: TableSorter) {
  const st = tableStateOf(componentId)
  if (!st) return
  st.page = pagination?.current ?? st.page
  st.size = pagination?.pageSize ?? st.size
  const s = Array.isArray(sorter) ? sorter[0] : sorter
  if (s?.order) {
    const field = s.columnKey ?? s.field
    st.sortField = field != null && String(field) ? String(field) : undefined
    st.sortOrder = s.order === 'descend' ? 'desc' : 'asc'
  } else {
    st.sortField = undefined
    st.sortOrder = undefined
  }
  loadTableData(componentId)
}

function applyTableFilter(componentId: number, vals: string[]) {
  const st = tableStateOf(componentId)
  if (!st) return
  st.dimValues = vals ?? []
  st.page = 1
  loadTableData(componentId)
}

/** 图表与洞察统一加载；TABLE 走独立明细表端点。 */
async function loadCharts(components: ComponentView[]) {
  // 全量加载即为根视图：同步清空下钻路径，避免刷新后面包屑与图表不一致
  drillStates.value = {}
  const charts = components.filter(
    (c) => c.compType === 'CHART' && ['BAR', 'LINE', 'PIE'].includes((c.chartType ?? '').toUpperCase()),
  )
  await Promise.all([
    ...charts.map((c) => loadChartData(c.componentId)),
    ...components.filter((c) => c.compType === 'TABLE').map((c) => initTable(c.componentId)),
    ...components.filter((c) => c.compType === 'METRIC_CARD').map((c) => loadMetricCardData(c.componentId)),
  ])
}

const subColumns = [
  { title: '频率', key: 'frequency', width: 90 },
  { title: '渠道', key: 'channel', width: 110 },
  { title: '状态', key: 'status', dataIndex: 'status', width: 80 },
  { title: '下次推送', key: 'nextRunAt', width: 180 },
  { title: '收件人数', key: 'receiverCount', dataIndex: 'receiverCount', width: 90 },
  { title: '操作', key: 'actions', width: 90 },
]

// 订阅表单
const subModalVisible = ref(false)
const subSubmitting = ref(false)
const subForm = ref({
  frequency: 'WEEKLY',
  channel: 'EMAIL',
  pushTime: null as string | null,
  receivers: '',
})

// 导出
const exportVisible = ref(false)
const exporting = ref(false)
const exportFormat = ref('CSV')
const exportTask = ref<{ exportId: string; status: string; rowCount: number; downloadUrl: string; expiresAt: string } | null>(null)

const downloadHref = computed(() => (exportTask.value ? reportApi.downloadExport(exportTask.value.exportId) : ''))

const refreshing = ref(false)

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  try {
    const res = await reportApi.getReport(reportId)
    report.value = res.data
    subscriptions.value = res.data.subscriptions
    // 旧报表/无版本：打开详情时幂等初始化数据版本（不暗中刷新已有成功版本）
    if (!res.data.currentDataVersionId && res.data.canRefresh !== false) {
      const generated = await reportApi.ensureReportDataVersion(reportId, `ensure-${reportId}-${res.data.definitionRevision ?? 1}`)
      if (generated.data.status !== 'READY' || !generated.data.published) {
        message.error('报表生成失败，请点击“刷新报表”重试')
        return
      }
      const refreshed = await reportApi.getReport(reportId)
      report.value = refreshed.data
    }
    await loadCharts((report.value ?? res.data).components)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载报表详情失败')
  } finally {
    loading.value = false
  }
}

async function refreshData() {
  refreshing.value = true
  try {
    const result = await reportApi.refreshReportDataVersion(reportId, `refresh-${reportId}-${crypto.randomUUID()}`)
    if (result.data.status !== 'READY' || !result.data.published) {
      message.error('刷新失败，保留上一成功版本')
      return
    }
    await loadDetail()
    message.success('报表已刷新')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '刷新报表失败')
  } finally {
    refreshing.value = false
  }
}

async function createSub() {
  const receivers = subForm.value.receivers
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((id) => ({ type: 'USER', id }))
  if (receivers.length === 0) {
    message.warning('请填写收件人')
    return
  }
  subSubmitting.value = true
  try {
    await reportApi.subscribe(reportId, {
      frequency: subForm.value.frequency as 'DAILY' | 'WEEKLY' | 'MONTHLY',
      channel: subForm.value.channel as 'EMAIL' | 'WECOM' | 'DINGTALK' | 'FEISHU',
      receivers,
      pushTime: subForm.value.pushTime ?? '09:00',
    })
    message.success('订阅成功')
    subModalVisible.value = false
    await loadDetail()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建订阅失败')
  } finally {
    subSubmitting.value = false
  }
}

async function cancelSub(subId: number) {
  try {
    await reportApi.cancelSubscription(reportId, subId)
    message.success('已取消订阅')
    await loadDetail()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '取消失败')
  }
}

function openExport() {
  exportTask.value = null
  exportVisible.value = true
}

async function createExportTask() {
  exporting.value = true
  try {
    const res = await reportApi.createExport(reportId, { format: exportFormat.value as 'CSV' | 'XLSX' | 'PDF' })
    exportTask.value = res.data
    message.success('导出任务已创建')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建导出任务失败')
  } finally {
    exporting.value = false
  }
}

function compTypeName(t: string): string {
  return { CHART: '图表', TABLE: '表格', METRIC_CARD: '指标卡' }[t] ?? t
}

function chartTypeName(t: string): string {
  return { BAR: '柱状图', LINE: '折线图', PIE: '饼图', NUMBER_CARD: '数字卡' }[t] ?? t
}

function freqName(f: string): string {
  return { DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月' }[f] ?? f
}

function prettyJson(def: Record<string, unknown>): string {
  try {
    return JSON.stringify(def, null, 2)
  } catch {
    return String(def)
  }
}

function metricCardTitle(def?: Record<string, unknown> | null): string {
  return String(def?.title ?? def?.metric ?? '指标')
}

/** 组件期间展示：优先 periodLabel，否则 periodStart/periodEnd 或滚动 months */
function periodTag(c: ComponentView): string {
  const def = c.def ?? {}
  const label = def.periodLabel
  if (typeof label === 'string' && label.trim()) return label.trim()
  const start = def.periodStart
  const end = def.periodEnd
  if (typeof start === 'string' && typeof end === 'string' && start && end) {
    return `${start} ~ ${end}`
  }
  const months = def.months
  if (typeof months === 'number' && months > 0) return `近${months}月`
  if (typeof months === 'string' && months.trim()) return `近${months}月`
  return ''
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${formatTime(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.report-title {
  margin: 0 0 8px;
}

.refresh-text,
.created-text {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
}

.comp-box {
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}

.comp-chart {
  margin-top: 8px;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.drill-breadcrumb {
  margin-left: 4px;
}

.drill-breadcrumb a {
  color: #1677ff;
  cursor: pointer;
}

.drill-breadcrumb a:hover {
  text-decoration: underline;
}

.drill-tag {
  margin-left: auto;
}

.table-toolbar {
  display: flex;
  justify-content: flex-end;
  margin: 8px 0;
}

.table-filter {
  width: 280px;
}

.insight-card {
  margin-top: 12px;
  padding: 12px;
  border: 1px dashed #91caff;
  border-radius: 8px;
  background: #f5faff;
}

.insight-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.insight-title {
  font-size: 13px;
  font-weight: 600;
  color: #1677ff;
}

.insight-summary {
  margin: 8px 0 4px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.85);
}

.insight-points {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}

.insight-limitations {
  margin: 6px 0 0;
  padding-left: 16px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.insight-degraded {
  margin: 6px 0 0;
  font-size: 12px;
  color: #d46b08;
}

.insight-evidence {
  margin-top: 8px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}

.insight-evidence > summary {
  cursor: pointer;
  color: #1677ff;
}

.insight-evidence-list {
  margin: 4px 0 0;
  padding-left: 16px;
}

.insight-evidence-list li {
  margin-bottom: 2px;
}

.insight-evidence-ref {
  margin-left: 4px;
  color: rgba(0, 0, 0, 0.45);
}

.insight-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.insight-actions .action-clickable {
  cursor: pointer;
}

.insight-loading,
.insight-pending {
  margin: 0;
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}

.insight-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.chart-empty {
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.35);
}

.comp-def {
  margin: 8px 0 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
  white-space: pre-wrap;
}

.metric-card-comp {
  margin-top: 8px;
}

.metric-card-title {
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 4px;
}

.section-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.export-body {
  display: flex;
  gap: 12px;
  align-items: center;
}

.export-status {
  margin-top: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 6px;
}
</style>
