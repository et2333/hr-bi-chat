<template>
  <div class="metric-panel">
    <!-- 筛选行 -->
    <div class="filter-row">
      <a-select
        v-model:value="domainFilter"
        placeholder="主题域"
        allow-clear
        class="filter-select"
        @change="onSearch"
      >
        <a-select-option v-for="d in DOMAIN_OPTIONS" :key="d.value" :value="d.value">{{ d.label }}</a-select-option>
      </a-select>
      <a-select
        v-model:value="statusFilter"
        placeholder="状态"
        allow-clear
        class="filter-select-sm"
        @change="onSearch"
      >
        <a-select-option :value="1">启用</a-select-option>
      </a-select>
      <a-input
        v-model:value="keyword"
        placeholder="关键词：指标编码/名称"
        allow-clear
        class="filter-item"
        @pressEnter="onSearch"
      />
      <a-button size="small" @click="onSearch">查询</a-button>
      <a-button type="primary" size="small" @click="openCreate">新建指标</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="metrics"
      :loading="listLoading"
      :pagination="{ current: page, pageSize: size, total, showSizeChanger: false, onChange: onPageChange }"
      row-key="id"
      size="small"
    >
      <template #emptyText><a-empty description="暂无指标" /></template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'domain'">
          <a-tag>{{ domainLabel(record.domain) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'pendingVersion'">
          <a-tag v-if="record.pendingVersion > 0" color="orange">v{{ record.pendingVersion }} 待审批</a-tag>
          <span v-else class="text-muted">-</span>
        </template>
        <template v-else-if="column.key === 'updatedAt'">
          {{ record.updatedAt ? formatDateTime(record.updatedAt) : '-' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button size="small" type="link" @click="openDetail(record)">详情</a-button>
          <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
          <a-popconfirm
            :title="record.status === 1 ? '确认停用该指标？停用后新报表不可再引用。' : '确认启用该指标？'"
            @confirm="toggleStatus(record)"
          >
            <a-button size="small" type="link">{{ record.status === 1 ? '停用' : '启用' }}</a-button>
          </a-popconfirm>
          <a-popconfirm title="确认删除该指标？删除后不可直接恢复。" @confirm="removeMetric(record)">
            <a-button size="small" type="link" danger>删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <!-- 详情 Drawer -->
    <a-drawer
      v-model:open="detailOpen"
      :title="detail ? `指标详情：${detail.name}` : '指标详情'"
      width="600"
    >
      <template v-if="detail">
        <div class="detail-actions">
          <a-button v-if="versions.length >= 2" size="small" @click="diffOpen = true">版本对比</a-button>
          <a-button type="primary" size="small" @click="openEditFromDetail">编辑指标</a-button>
        </div>
        <a-descriptions :column="2" size="small" bordered class="detail-desc">
          <a-descriptions-item label="指标编码">{{ detail.code }}</a-descriptions-item>
          <a-descriptions-item label="主题域">{{ domainLabel(detail.domain) }}</a-descriptions-item>
          <a-descriptions-item label="默认周期">{{ detail.defaultPeriod }}</a-descriptions-item>
          <a-descriptions-item label="指标方向">{{ detail.goodDirection === 1 ? '正向' : '逆向' }}</a-descriptions-item>
          <a-descriptions-item label="密级">
            <a-tag :color="detail.permLevel === 3 ? 'red' : 'green'">{{ detail.permLevel === 3 ? '敏感' : '公开' }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="更新人">{{ detail.updatedBy || '-' }}</a-descriptions-item>
        </a-descriptions>

        <div class="detail-block">
          <div class="block-label">当前口径</div>
          <div class="block-text">{{ detail.calcScope }}</div>
        </div>
        <div class="detail-block">
          <div class="block-label">当前公式</div>
          <pre class="block-pre">{{ detail.formulaExpr }}</pre>
        </div>
        <div class="detail-block">
          <div class="block-label">可用维度</div>
          <a-space wrap>
            <a-tag v-for="d in detail.availableDimensions" :key="d">{{ d }}</a-tag>
            <span v-if="!detail.availableDimensions.length" class="text-muted">无</span>
          </a-space>
        </div>

        <!-- 引用血缘 -->
        <div class="detail-block">
          <div class="block-label">引用关系</div>
          <a-spin :spinning="lineageLoading">
            <a-list v-if="lineage.length" size="small" bordered :data-source="lineage">
              <template #renderItem="{ item }">
                <a-list-item>
                  <a-list-item-meta :title="lineageTitle(item)" :description="`组件 #${item.componentId}`">
                    <template #avatar>
                      <a-tag :color="item.compType === 1 ? 'blue' : 'purple'">
                        {{ item.compType === 1 ? item.chartType || '图表' : '表格' }}
                      </a-tag>
                    </template>
                  </a-list-item-meta>
                </a-list-item>
              </template>
            </a-list>
            <a-empty v-else description="暂无报表引用" />
          </a-spin>
        </div>

        <!-- 版本历史 -->
        <div class="detail-block">
          <div class="block-label">版本历史</div>
          <a-timeline v-if="versions.length" class="version-timeline">
            <a-timeline-item v-for="v in versions" :key="v.versionNo" :color="versionColor(v.status)">
              <div class="version-head">
                <span class="version-no">v{{ v.versionNo }}</span>
                <a-tag :color="versionTagColor(v.status)">{{ versionLabel(v.status) }}</a-tag>
              </div>
              <div class="version-meta">
                {{ v.submittedBy || '-' }} 提交
                <template v-if="v.approvedBy"> · {{ v.approvedBy }} 审批</template>
                <template v-if="v.effectiveAt"> · 生效 {{ formatDateTime(v.effectiveAt) }}</template>
              </div>
              <div v-if="v.changeNote" class="version-note">{{ v.changeNote }}</div>
              <pre class="block-pre version-pre">{{ v.formulaExpr }}</pre>

              <!-- 待审批版本：提交审批后可审批（需 approve 权限） -->
              <div v-if="v.status === 0" class="approval-row">
                <a-button
                  v-if="approvalId === null"
                  type="primary"
                  size="small"
                  :loading="submittingApproval"
                  @click="doSubmitApproval"
                >
                  提交审批
                </a-button>
                <template v-else>
                  <a-popconfirm
                    v-if="canApprove"
                    title="确认审批通过并发布该版本？"
                    @confirm="doApprove"
                  >
                    <a-button type="primary" size="small" :loading="approving">审批通过</a-button>
                  </a-popconfirm>
                  <a-button v-if="canApprove" size="small" danger :loading="rejecting" @click="openReject">
                    驳回
                  </a-button>
                  <span v-if="!canApprove" class="text-muted">等待 HR 数据负责人审批…</span>
                </template>
              </div>
            </a-timeline-item>
          </a-timeline>
          <a-empty v-else description="暂无版本记录" />
        </div>
      </template>
      <a-skeleton v-else active :paragraph="{ rows: 6 }" />
    </a-drawer>

    <!-- 新建 / 编辑 Modal -->
    <MetricEditorModal
      v-model:open="editorOpen"
      :mode="editorMode"
      :detail="detail"
      :dimensions="dimensions"
      @saved="onSaved"
    />

    <!-- 版本对比 Modal -->
    <VersionDiffModal v-model:open="diffOpen" :versions="versions" />

    <!-- 驳回原因 Modal -->
    <a-modal
      v-model:open="rejectOpen"
      title="驳回审批"
      :confirm-loading="rejecting"
      width="480px"
      @ok="doReject"
    >
      <a-textarea v-model:value="rejectComment" :rows="3" placeholder="请填写驳回原因（可选）" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { semanticApi } from '@/api'
import type {
  DimensionSummary,
  LineageItem,
  MetricDetail,
  MetricSummary,
  MetricVersionInfo,
  MetricVersionStatus,
} from '@/api/semantic'
import { useAuthStore } from '@/stores/auth'
import { hasPerm } from '@/layouts/adminMenu'
import MetricEditorModal from './MetricEditorModal.vue'
import VersionDiffModal from './VersionDiffModal.vue'

const DOMAIN_OPTIONS = [
  { value: 'staff', label: '人员' },
  { value: 'pay', label: '薪酬' },
  { value: 'attendance', label: '考勤' },
  { value: 'perf', label: '绩效' },
  { value: 'recruit', label: '招聘' },
  { value: 'org', label: '组织' },
]

function domainLabel(value: string): string {
  return DOMAIN_OPTIONS.find((d) => d.value === value)?.label ?? value
}

// ---------------- 权限 ----------------
const auth = useAuthStore()
const perms = computed(() => auth.functionPerms)
const canApprove = computed(() => hasPerm(perms.value, 'admin:semantic:approve'))

// ---------------- 列表 ----------------
const metrics = ref<MetricSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const domainFilter = ref<string>()
const statusFilter = ref<number>()
const listLoading = ref(false)
const dimensions = ref<DimensionSummary[]>([])

const columns = [
  { title: '指标编码', key: 'code', dataIndex: 'code', width: 140 },
  { title: '名称', key: 'name', dataIndex: 'name', width: 120 },
  { title: '主题域', key: 'domain', width: 90 },
  { title: '状态', key: 'status', width: 80 },
  { title: '生效版本', key: 'effectiveVersion', dataIndex: 'effectiveVersion', width: 90 },
  { title: '待审批', key: 'pendingVersion', width: 110 },
  { title: '更新时间', key: 'updatedAt', width: 160 },
  { title: '操作', key: 'actions', width: 230 },
]

async function loadMetrics() {
  listLoading.value = true
  try {
    const res = await semanticApi.listMetrics({
      domain: domainFilter.value || undefined,
      status: statusFilter.value,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    metrics.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载指标列表失败')
  } finally {
    listLoading.value = false
  }
}

async function loadDimensions() {
  try {
    const res = await semanticApi.listDimensions({ page: 1, size: 100 })
    dimensions.value = res.data.records
  } catch {
    // 维度加载失败不阻断指标页（仅影响可用维度多选）
  }
}

function onSearch() {
  page.value = 1
  loadMetrics()
}

function onPageChange(p: number) {
  page.value = p
  loadMetrics()
}

// ---------------- 详情 Drawer ----------------
const detailOpen = ref(false)
const detail = ref<MetricDetail | null>(null)
const versions = ref<MetricVersionInfo[]>([])
/** 当前待审批版本的 approvalId（submit-approval 获取；null 未提交） */
const approvalId = ref<number | null>(null)
const submittingApproval = ref(false)
const approving = ref(false)
const rejecting = ref(false)

/** 引用血缘 */
const lineage = ref<LineageItem[]>([])
const lineageLoading = ref(false)

/** 版本对比 */
const diffOpen = ref(false)

async function openDetail(record: MetricSummary) {
  detailOpen.value = true
  detail.value = null
  versions.value = []
  approvalId.value = null
  lineage.value = []
  diffOpen.value = false
  await Promise.all([
    loadDetail(record.id),
    loadVersions(record.id),
    loadLineage(record.id),
  ])
}

async function loadLineage(metricId: number) {
  lineageLoading.value = true
  try {
    const res = await semanticApi.getMetricLineage(metricId)
    lineage.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载引用关系失败')
  } finally {
    lineageLoading.value = false
  }
}

function lineageTitle(item: LineageItem): string {
  return item.reportName
}

// ---------------- 启停 / 删除 ----------------

async function toggleStatus(record: MetricSummary) {
  const next = record.status === 1 ? 0 : 1
  try {
    await semanticApi.setMetricStatus(record.id, next)
    message.success(next === 0 ? '已停用' : '已启用')
    await loadMetrics()
    if (detail.value?.id === record.id) {
      await loadDetail(record.id)
    }
  } catch (e) {
    showBlock(e)
  }
}

async function removeMetric(record: MetricSummary) {
  try {
    await semanticApi.deleteMetric(record.id)
    message.success('已删除')
    if (detail.value?.id === record.id) {
      detailOpen.value = false
    }
    await loadMetrics()
  } catch (e) {
    showBlock(e)
  }
}

/** 引用拦截等后端拒绝：弹窗展示完整原因（message 会被快速关闭）。 */
function showBlock(e: unknown) {
  Modal.error({
    title: '操作被拒绝',
    content: e instanceof Error ? e.message : '操作失败，请稍后重试',
  })
}

async function loadDetail(metricId: number) {
  try {
    const res = await semanticApi.getMetric(metricId)
    detail.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载指标详情失败')
  }
}

async function loadVersions(metricId: number) {
  try {
    const res = await semanticApi.listMetricVersions(metricId)
    versions.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载版本历史失败')
  }
}

async function doSubmitApproval() {
  if (!detail.value) return
  submittingApproval.value = true
  try {
    const res = await semanticApi.submitMetricApproval(detail.value.id)
    approvalId.value = res.data.approvalId
    message.success('已提交审批')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '提交审批失败')
  } finally {
    submittingApproval.value = false
  }
}

async function doApprove() {
  if (approvalId.value === null) return
  approving.value = true
  try {
    await semanticApi.approveMetric(approvalId.value)
    message.success('审批通过，新版本已发布')
    await refreshAfterApproval()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  } finally {
    approving.value = false
  }
}

// ---------------- 驳回 ----------------
const rejectOpen = ref(false)
const rejectComment = ref('')

function openReject() {
  rejectComment.value = ''
  rejectOpen.value = true
}

async function doReject() {
  if (approvalId.value === null) return
  rejecting.value = true
  try {
    await semanticApi.rejectMetric(approvalId.value, rejectComment.value.trim())
    message.success('已驳回')
    rejectOpen.value = false
    await refreshAfterApproval()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '驳回失败')
  } finally {
    rejecting.value = false
  }
}

/** 审批操作后刷新版本/详情/列表 */
async function refreshAfterApproval() {
  approvalId.value = null
  if (detail.value) {
    await Promise.all([loadDetail(detail.value.id), loadVersions(detail.value.id)])
  }
  await loadMetrics()
}

// ---------------- 新建 / 编辑 ----------------
const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')

function openCreate() {
  editorMode.value = 'create'
  detail.value = null
  editorOpen.value = true
}

function openEdit(record: MetricSummary) {
  editorMode.value = 'edit'
  // 先取详情（编辑表单以详情为准）
  semanticApi
    .getMetric(record.id)
    .then((res) => {
      detail.value = res.data
      editorOpen.value = true
    })
    .catch((e: unknown) => message.error(e instanceof Error ? e.message : '加载指标详情失败'))
}

function openEditFromDetail() {
  editorMode.value = 'edit'
  editorOpen.value = true
}

async function onSaved() {
  await loadMetrics()
  if (detail.value && detailOpen.value) {
    await Promise.all([loadDetail(detail.value.id), loadVersions(detail.value.id)])
  }
}

// ---------------- 版本状态展示 ----------------
function versionColor(s: MetricVersionStatus): string {
  return { 0: 'orange', 1: 'green', 2: 'red', 3: 'gray' }[s]
}

function versionTagColor(s: MetricVersionStatus): string {
  return { 0: 'orange', 1: 'green', 2: 'red', 3: 'default' }[s]
}

function versionLabel(s: MetricVersionStatus): string {
  return { 0: '待审批', 1: '生效中', 2: '已驳回', 3: '历史' }[s]
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  loadMetrics()
  loadDimensions()
})
</script>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.filter-select {
  width: 120px;
}

.filter-select-sm {
  width: 100px;
}

.filter-item {
  width: 220px;
}

.text-muted {
  color: rgba(0, 0, 0, 0.35);
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.detail-block {
  margin-top: 16px;
}

.block-label {
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.65);
  margin-bottom: 6px;
}

.block-text {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.85);
  line-height: 1.6;
}

.block-pre {
  margin: 0;
  padding: 8px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.version-timeline {
  margin-top: 8px;
}

.version-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-no {
  font-weight: 600;
}

.version-meta {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.version-note {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}

.version-pre {
  margin-top: 6px;
  max-height: 120px;
  overflow: auto;
}

.approval-row {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
