<template>
  <div class="admin-page">
    <a-card :bordered="false" class="pane-card">
      <div class="filter-row">
        <a-input v-model:value="auditFilter.userNo" placeholder="用户工号" allow-clear class="filter-item" />
        <a-select v-model:value="auditFilter.action" placeholder="操作类型" allow-clear class="filter-item">
          <a-select-option v-for="a in ACTIONS" :key="a" :value="a">{{ a }}</a-select-option>
        </a-select>
        <a-checkbox v-model:checked="auditFilter.sensitiveOnly">仅敏感操作</a-checkbox>
        <a-button type="primary" size="small" @click="onSearch">查询</a-button>
        <a-button size="small" @click="exportCsv">导出 CSV</a-button>
      </div>
      <a-table
        :columns="auditColumns"
        :data-source="auditLogs"
        :loading="loading"
        :pagination="{
          current: auditPage,
          pageSize: 20,
          total: auditTotal,
          onChange: onPageChange,
        }"
        row-key="logId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-tag :color="record.sensitive ? 'volcano' : 'blue'">{{ record.action }}</a-tag>
          </template>
          <template v-else-if="column.key === 'ts'">{{ formatDateTime(record.ts) }}</template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '@/api'
import type { AuditLog } from '@/api/types'

const ACTIONS = ['LOGIN', 'ASK', 'VIEW_SQL', 'EXPORT', 'PLAIN_VIEW', 'PERMISSION_CHANGE', 'SEMANTIC_CHANGE', 'SYNC_RETRY']

const auditLogs = ref<AuditLog[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const loading = ref(false)
const auditFilter = ref({ userNo: '', action: undefined as string | undefined, sensitiveOnly: false })

const auditColumns = [
  { title: '用户', key: 'userNo', dataIndex: 'userNo', width: 90 },
  { title: '姓名', key: 'userName', dataIndex: 'userName', width: 90 },
  { title: '操作', key: 'action', width: 140 },
  { title: '资源', key: 'resource', dataIndex: 'resource' },
  { title: 'IP', key: 'ip', dataIndex: 'ip', width: 110 },
  { title: '时间', key: 'ts', width: 170 },
]

async function loadAudit() {
  loading.value = true
  try {
    const res = await adminApi.listAuditLogs({
      userId: auditFilter.value.userNo || undefined,
      action: auditFilter.value.action,
      sensitiveOnly: auditFilter.value.sensitiveOnly || undefined,
      page: auditPage.value,
      size: 20,
    })
    auditLogs.value = res.data.records
    auditTotal.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载审计日志失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  auditPage.value = 1
  loadAudit()
}

function onPageChange(p: number) {
  auditPage.value = p
  loadAudit()
}

/** 前端将当前页记录转 CSV 下载（纯前端 Blob，不调后端） */
function exportCsv() {
  const header = ['用户工号', '姓名', '操作', '资源', 'IP', '时间']
  const rows = auditLogs.value.map((r) => [r.userNo, r.userName, r.action, r.resource, r.ip, r.ts])
  const csv = [header, ...rows]
    .map((row) => row.map((cell) => `"${String(cell ?? '').replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `审计日志_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(loadAudit)
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

.filter-item {
  width: 180px;
}
</style>
