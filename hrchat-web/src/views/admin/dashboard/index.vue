<template>
  <div class="admin-page">
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" class="pane-card" />

      <template v-if="data">
        <!-- 指标卡 -->
        <a-card :bordered="false" title="概览" class="pane-card">
          <a-row :gutter="[16, 16]">
            <a-col v-for="item in metricItems" :key="item.label" :xs="12" :md="6">
              <div class="quality-box">
                <div class="quality-value" :style="{ color: item.color }">{{ item.value }}</div>
                <div class="quality-label">{{ item.label }}</div>
              </div>
            </a-col>
          </a-row>
        </a-card>

        <!-- 配额使用率 -->
        <a-card :bordered="false" title="配额使用率" class="pane-card">
          <div v-for="item in quotaItems" :key="item.label" class="quota-item">
            <div class="quota-head">
              <span>{{ item.label }}</span>
              <span class="quota-value">{{ item.used }} / {{ item.total }}</span>
            </div>
            <a-progress :percent="item.percent" :status="item.percent >= 100 ? 'exception' : 'active'" />
          </div>
        </a-card>

        <!-- LLM 模型健康 -->
        <a-card :bordered="false" title="LLM 模型健康" class="pane-card">
          <div class="filter-row">
            <span class="llm-profile-label">系统默认 LLM 档位：</span>
            <a-tag color="geekblue">{{ data.defaultLlmProfile || '-' }}</a-tag>
          </div>
          <a-table
            :columns="llmColumns"
            :data-source="data.llm.items"
            :pagination="false"
            row-key="modelCode"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'deployState'">
                <a-tag :color="deployColor(record.deployState)">{{ deployLabel(record.deployState) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'healthStatus'">
                <a-tag :color="healthColor(record.healthStatus)">{{ record.healthStatus }}</a-tag>
              </template>
            </template>
          </a-table>
        </a-card>

        <!-- 近 7 日审计趋势 -->
        <a-card :bordered="false" title="近 7 日审计趋势" class="pane-card">
          <a-table
            :columns="trendColumns"
            :data-source="data.auditTrend"
            :pagination="false"
            row-key="date"
            size="small"
          />
        </a-card>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '@/api'
import type { DashboardView } from '@/api/types'

const loading = ref(false)
const error = ref('')
const data = ref<DashboardView | null>(null)

const metricItems = computed(() => {
  const d = data.value
  return [
    { label: '租户数', value: d?.tenantCount ?? '-', color: '#1677ff' },
    { label: '用户数', value: d?.userCount ?? '-', color: '#52c41a' },
    { label: '报表数', value: d?.reportCount ?? '-', color: '#fa8c16' },
    { label: '订阅数', value: d?.subscriptionCount ?? '-', color: '#722ed1' },
  ]
})

const quotaItems = computed(() => {
  const q = data.value?.quotaUsage
  if (!q) return []
  return [
    { label: '用户配额', ...q.user },
    { label: '报表配额', ...q.report },
    { label: '订阅配额', ...q.subscription },
    { label: 'API 日配额', ...q.api },
  ]
})

const llmColumns = [
  { title: '模型编码', key: 'modelCode', dataIndex: 'modelCode', width: 140 },
  { title: '名称', key: 'modelName', dataIndex: 'modelName' },
  { title: '部署状态', key: 'deployState', width: 110 },
  { title: '健康状态', key: 'healthStatus', width: 110 },
]

const trendColumns = [
  { title: '日期', key: 'date', dataIndex: 'date', width: 160 },
  { title: '审计次数', key: 'count', dataIndex: 'count' },
]

function deployColor(s: string): string {
  return { ACTIVE: 'green', SIMULATED: 'orange', FAILED: 'red', PENDING: 'default', APPLYING: 'blue' }[s] ?? 'default'
}

function deployLabel(s: string): string {
  return s === 'SIMULATED' ? '模拟生效' : s
}

function healthColor(s: string): string {
  return { UP: 'green', DOWN: 'red', UNKNOWN: 'default' }[s] ?? 'default'
}

async function loadDashboard() {
  loading.value = true
  error.value = ''
  try {
    const res = await adminApi.getDashboard()
    data.value = res.data
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载管理概览失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)
</script>

<style scoped>
.pane-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.llm-profile-label {
  color: rgba(0, 0, 0, 0.65);
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
}

.quality-label {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.quota-item {
  margin-bottom: 16px;
}

.quota-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.quota-value {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
