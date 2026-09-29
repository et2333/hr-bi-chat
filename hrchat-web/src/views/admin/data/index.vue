<template>
  <div class="admin-page">
    <!-- 数据源健康 -->
    <a-card :bordered="false" title="数据源健康" class="pane-card">
      <a-table
        :columns="dsColumns"
        :data-source="datasources"
        :pagination="false"
        row-key="dsCode"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'syncMode'">
            {{ record.syncMode === 'FULL' ? '全量' : '增量' }}
          </template>
          <template v-else-if="column.key === 'lastSyncAt'">
            {{ record.lastSyncAt ? formatDateTime(record.lastSyncAt) : '-' }}
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 同步任务 -->
    <a-card :bordered="false" title="同步任务" class="pane-card">
      <div class="filter-row">
        <a-button size="small" @click="loadData">刷新</a-button>
      </div>
      <a-table
        :columns="jobColumns"
        :data-source="syncJobs"
        :pagination="false"
        row-key="jobId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'execState'">
            <a-tag :color="stateColor(record.execState)">{{ stateName(record.execState) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'rows'">
            {{ record.rowsRead }} / {{ record.rowsWritten }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-popconfirm
              v-if="record.execState === 'FAILED'"
              title="确定重试该任务？"
              @confirm="retryJob(record.jobId)"
            >
              <a-button size="small" type="link">重试</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 数据质量摘要 -->
    <a-card :bordered="false" title="数据质量摘要" class="pane-card">
      <a-row :gutter="[16, 16]">
        <a-col v-for="item in qualityItems" :key="item.label" :xs="8" :md="4">
          <div class="quality-box">
            <div class="quality-value">{{ item.value }}</div>
            <div class="quality-label">{{ item.label }}</div>
          </div>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '@/api'
import type { DatasourceView, QualitySummary, SyncJobView } from '@/api/types'

// ---------------- 数据源与同步 ----------------
const datasources = ref<DatasourceView[]>([])
const syncJobs = ref<SyncJobView[]>([])
const quality = ref<QualitySummary | null>(null)

const dsColumns = [
  { title: '编码', key: 'dsCode', dataIndex: 'dsCode' },
  { title: '名称', key: 'dsName', dataIndex: 'dsName' },
  { title: '同步模式', key: 'syncMode' },
  { title: '状态', key: 'status' },
  { title: '最近同步', key: 'lastSyncAt' },
  { title: '最新业务日期', key: 'latestBizDate', dataIndex: 'latestBizDate' },
]

const jobColumns = [
  { title: '任务 ID', key: 'jobId', dataIndex: 'jobId', width: 80 },
  { title: '数据源', key: 'dsName', dataIndex: 'dsName' },
  { title: '业务日期', key: 'bizDate', dataIndex: 'bizDate', width: 110 },
  { title: '状态', key: 'execState', width: 90 },
  { title: '读写行数', key: 'rows', width: 120 },
  { title: '操作', key: 'actions', width: 80 },
]

const qualityItems = computed(() => {
  const q = quality.value
  if (!q) return []
  return [
    { label: '完整性', value: `${q.completeness}%` },
    { label: '成功', value: q.success },
    { label: '失败', value: q.failed },
    { label: '运行中', value: q.running },
    { label: '延迟', value: q.delayed },
    { label: '任务总数', value: q.totalJobs },
  ]
})

async function loadData() {
  try {
    const [ds, jobs, q] = await Promise.all([
      adminApi.listDatasources(),
      adminApi.listSyncJobs(),
      adminApi.qualitySummary(),
    ])
    datasources.value = ds.data
    syncJobs.value = jobs.data.records
    quality.value = q.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载数据源失败')
  }
}

async function retryJob(jobId: number) {
  try {
    await adminApi.retrySyncJob(jobId)
    message.success('重试任务已触发')
    await loadData()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重试失败')
  }
}

function stateColor(s: string): string {
  return { SUCCESS: 'green', FAILED: 'red', DELAYED: 'orange', RUNNING: 'blue' }[s] ?? 'default'
}

function stateName(s: string): string {
  return { SUCCESS: '成功', FAILED: '失败', DELAYED: '延迟', RUNNING: '运行中' }[s] ?? s
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(loadData)
</script>

<style scoped>
.pane-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.quality-box {
  padding: 16px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  text-align: center;
}

.quality-value {
  font-size: 26px;
  font-weight: 700;
  color: #1677ff;
}

.quality-label {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
