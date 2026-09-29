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
          <a-button type="primary" @click="openExport">
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
            <a-space>
              <a-tag color="geekblue">{{ compTypeName(c.compType) }}</a-tag>
              <a-tag v-if="c.chartType">{{ chartTypeName(c.chartType) }}</a-tag>
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
                <a-breadcrumb v-if="drillPathOf(c.componentId).length" class="drill-breadcrumb">
                  <a-breadcrumb-item v-for="(seg, i) in drillPathOf(c.componentId)" :key="i">
                    <a @click="drillTo(c.componentId, i)">{{ seg }}</a>
                  </a-breadcrumb-item>
                </a-breadcrumb>
                <a-tag v-if="drillPathOf(c.componentId).length" color="orange" class="drill-tag">组织下钻</a-tag>
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
                    <a-button v-if="insightStateOf(c.componentId)!.error" size="small" type="link" @click="loadInsight(c.componentId)">
                      重试
                    </a-button>
                  </div>
                  <template v-if="insightStateOf(c.componentId)!.data">
                    <p class="insight-summary">{{ insightStateOf(c.componentId)!.data!.summary }}</p>
                    <ul class="insight-points">
                      <li v-for="(pt, i) in insightStateOf(c.componentId)!.data!.points" :key="i">· {{ pt.label }}</li>
                    </ul>
                  </template>
                  <p v-else-if="insightStateOf(c.componentId)!.loading" class="insight-loading">洞察生成中…</p>
                  <p v-else class="insight-error">洞察加载失败</p>
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
                @change="onTableChange(c.componentId, $event)"
              />
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

    <!-- 快照 -->
    <a-card :bordered="false" title="快照历史" class="section-card">
      <a-table
        :columns="snapColumns"
        :data-source="snapshots"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'generatedAt'">{{ formatDateTime(record.generatedAt) }}</template>
          <template v-else-if="column.key === 'expireAt'">{{ formatDateTime(record.expireAt) }}</template>
        </template>
      </a-table>
      <a-empty v-if="snapshots.length === 0" description="暂无快照" />
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, BulbOutlined, DownloadOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { reportApi } from '@/api'
import type { ComponentView, ReportDetail, SnapshotView, SubscriptionView } from '@/api/types'
import type { ChartDataView, InsightView, TableView } from '@/api/reports'
import ChartRenderer, { type ChartType } from '@/components/ChartRenderer.vue'

const route = useRoute()
const router = useRouter()
const reportId = Number(route.params.id)

const report = ref<ReportDetail | null>(null)
const subscriptions = ref<SubscriptionView[]>([])
const snapshots = ref<SnapshotView[]>([])
const loading = ref(false)

// 图表组件数据（异步拉取，key 为 componentId）
interface ChartState {
  loading: boolean
  data: ChartDataView | null
}
const chartStates = ref<Record<number, ChartState>>({})

function chartStateOf(componentId: number): ChartState | undefined {
  return chartStates.value[componentId]
}

async function loadChartData(componentId: number, dimValue?: string) {
  chartStates.value[componentId] = { loading: true, data: null }
  try {
    const res = await reportApi.getChartData(reportId, componentId, dimValue)
    chartStates.value[componentId] = { loading: false, data: res.data }
  } catch (e) {
    chartStates.value[componentId] = { loading: false, data: null }
    message.error(e instanceof Error ? e.message : '图表数据加载失败')
  }
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

/** AI 洞察（仅 BAR/LINE 图表组件加载） */
interface InsightState {
  loading: boolean
  data: InsightView | null
  error: boolean
}
const insightStates = ref<Record<number, InsightState>>({})

function insightStateOf(componentId: number): InsightState | undefined {
  return insightStates.value[componentId]
}

async function loadInsight(componentId: number) {
  insightStates.value[componentId] = { loading: true, data: null, error: false }
  try {
    const res = await reportApi.getInsight(reportId, componentId)
    insightStates.value[componentId] = { loading: false, data: res.data, error: false }
  } catch {
    insightStates.value[componentId] = { loading: false, data: null, error: true }
  }
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
  return (tableStateOf(componentId)?.data?.columns ?? []).map((col) => ({
    title: col.title,
    key: col.key,
    dataIndex: col.key,
    sorter: true,
    sortDirections: ['ascend', 'descend'] as const,
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

function onTableChange(componentId: number, e: { pagination?: { current?: number; pageSize?: number }; sorter?: { order?: string; columnKey?: string } }) {
  const st = tableStateOf(componentId)
  if (!st) return
  st.page = e.pagination?.current ?? 1
  st.size = e.pagination?.pageSize ?? st.size
  if (e.sorter?.order) {
    st.sortField = e.sorter.columnKey
    st.sortOrder = e.sorter.order === 'descend' ? 'desc' : 'asc'
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

/** 后端 chart-data 端点仅支持 BAR/LINE/PIE；TABLE 走明细表端点；BAR/LINE 附带加载洞察（chartType 兼容小写，如模板里的 "bar"） */
async function loadCharts(components: ComponentView[]) {
  const charts = components.filter(
    (c) => c.compType === 'CHART' && ['BAR', 'LINE', 'PIE'].includes((c.chartType ?? '').toUpperCase()),
  )
  await Promise.all([
    ...charts.map((c) => loadChartData(c.componentId)),
    ...charts.filter((c) => (c.chartType ?? '').toUpperCase() !== 'PIE').map((c) => loadInsight(c.componentId)),
    ...components.filter((c) => c.compType === 'TABLE').map((c) => initTable(c.componentId)),
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

const snapColumns = [
  { title: '快照 ID', key: 'id', dataIndex: 'id' },
  { title: '生成时间', key: 'generatedAt' },
  { title: '过期时间', key: 'expireAt' },
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

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  try {
    const res = await reportApi.getReport(reportId)
    report.value = res.data
    subscriptions.value = res.data.subscriptions
    const snaps = await reportApi.listSnapshots(reportId)
    snapshots.value = snaps.data.records
    await loadCharts(res.data.components)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载报表详情失败')
  } finally {
    loading.value = false
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

.insight-loading,
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
