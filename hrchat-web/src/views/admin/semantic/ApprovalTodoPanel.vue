<template>
  <div class="approval-todo">
    <div class="toolbar">
      <a-button size="small" :loading="loading" @click="load">刷新待办</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="todos"
      :loading="loading"
      :pagination="false"
      row-key="approvalId"
      size="small"
    >
      <template #emptyText><a-empty description="暂无审批待办" /></template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'metric'">
          <div class="metric-name">{{ record.metricName }}</div>
          <a-space :size="4">
            <a-tag>{{ record.metricCode }}</a-tag>
            <a-tag color="orange">v{{ record.versionNo }}</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.key === 'content'">
          <div v-if="record.calcScope" class="scope-text">{{ record.calcScope }}</div>
          <pre class="formula-pre">{{ record.formulaExpr }}</pre>
        </template>
        <template v-else-if="column.key === 'submitted'">
          <div>{{ record.submittedBy }}</div>
          <div class="time-text">{{ record.submittedAt ? formatDateTime(record.submittedAt) : '-' }}</div>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-popconfirm title="确认审批通过并发布该版本？" @confirm="approve(record)">
            <a-button size="small" type="primary">通过</a-button>
          </a-popconfirm>
          <a-button size="small" danger class="reject-btn" @click="openReject(record)">驳回</a-button>
        </template>
      </template>
    </a-table>

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
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { semanticApi } from '@/api'
import type { ApprovalTodoItem } from '@/api/semantic'

const emit = defineEmits<{ (e: 'update:count', value: number): void }>()

const todos = ref<ApprovalTodoItem[]>([])
const loading = ref(false)

const columns = [
  { title: '指标', key: 'metric', width: 200 },
  { title: '变更口径/公式', key: 'content' },
  { title: '提交人/时间', key: 'submitted', width: 170 },
  { title: '操作', key: 'actions', width: 150 },
]

async function load() {
  loading.value = true
  try {
    const res = await semanticApi.listApprovalTodos()
    todos.value = res.data
    emit('update:count', res.data.length)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载审批待办失败')
  } finally {
    loading.value = false
  }
}

async function approve(record: ApprovalTodoItem) {
  try {
    await semanticApi.approveMetric(record.approvalId)
    message.success('审批通过，新版本已发布')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  }
}

// ---------------- 驳回 ----------------
const rejectOpen = ref(false)
const rejectComment = ref('')
const rejecting = ref(false)
const rejectingId = ref<number | null>(null)

function openReject(record: ApprovalTodoItem) {
  rejectingId.value = record.approvalId
  rejectComment.value = ''
  rejectOpen.value = true
}

async function doReject() {
  if (rejectingId.value === null) return
  rejecting.value = true
  try {
    await semanticApi.rejectMetric(rejectingId.value, rejectComment.value.trim())
    message.success('已驳回')
    rejectOpen.value = false
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '驳回失败')
  } finally {
    rejecting.value = false
  }
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}

.metric-name {
  font-weight: 600;
  margin-bottom: 4px;
}

.scope-text {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
  margin-bottom: 4px;
  line-height: 1.5;
}

.formula-pre {
  margin: 0;
  padding: 6px 8px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.time-text {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.reject-btn {
  margin-left: 8px;
}
</style>
