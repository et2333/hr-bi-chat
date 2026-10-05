<template>
  <div class="reports-page">
    <a-card :bordered="false" class="list-card">
      <div class="list-toolbar">
        <a-radio-group v-model:value="scope" button-style="solid" @change="reload">
          <a-radio-button value="mine">我创建的</a-radio-button>
          <a-radio-button value="subscribed">我订阅的</a-radio-button>
          <a-radio-button value="shared">共享给我的</a-radio-button>
        </a-radio-group>
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索报表名称"
          class="search-input"
          allow-clear
          @search="reload"
        />
        <a-button type="primary" @click="router.push('/reports/editor')">
          <template #icon><PlusOutlined /></template>
          新建报表
        </a-button>
      </div>

      <a-table
        :columns="columns"
        :data-source="records"
        :loading="loading"
        :pagination="{ current: page, pageSize: size, total, onChange: onPageChange }"
        row-key="id"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <a class="report-name" @click="router.push(`/reports/${record.id}`)">{{ record.name }}</a>
          </template>
          <template v-else-if="column.key === 'sourceType'">
            <a-tag :color="sourceColor(record.sourceType)">{{ record.sourceType }}</a-tag>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ formatTime(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-space>
              <a-button size="small" type="link" @click="router.push(`/reports/${record.id}`)">查看</a-button>
              <a-popconfirm title="确定删除该报表？" @confirm="remove(record.id)">
                <a-button size="small" type="link" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="暂无报表">
            <a-space>
              <a-button type="primary" @click="router.push('/reports/editor')">从模板创建</a-button>
              <a-button @click="router.push('/chat')">去问数生成</a-button>
            </a-space>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <a-card :bordered="false" class="template-card" title="模板库（一键实例化）">
      <a-row :gutter="[12, 12]">
        <a-col v-for="tpl in templates" :key="tpl.id" :xs="24" :sm="12" :md="8" :lg="6">
          <a-card size="small" hoverable class="template-item" @click="router.push({ path: '/reports/editor', query: { template: tpl.id } })">
            <div class="template-name">{{ tpl.name }}</div>
            <a-tag class="template-cat">{{ tpl.category }}</a-tag>
            <div class="template-desc">{{ tpl.description }}</div>
          </a-card>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { reportApi } from '@/api'
import type { ReportSummary, TemplateItem } from '@/api/types'

const router = useRouter()
const scope = ref<'mine' | 'subscribed' | 'shared'>('mine')
const keyword = ref('')
const records = ref<ReportSummary[]>([])
const templates = ref<TemplateItem[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

const columns = [
  { title: '报表名称', key: 'name', dataIndex: 'name' },
  { title: '来源', key: 'sourceType', dataIndex: 'sourceType', width: 110 },
  { title: '所有者', key: 'ownerName', dataIndex: 'ownerName', width: 120 },
  { title: '订阅数', key: 'subscriptionCount', dataIndex: 'subscriptionCount', width: 80 },
  { title: '创建时间', key: 'createdAt', dataIndex: 'createdAt', width: 160 },
  { title: '操作', key: 'actions', width: 140 },
]

onMounted(async () => {
  await Promise.all([reload(), loadTemplates()])
})

async function reload() {
  loading.value = true
  try {
    const res = await reportApi.listReports(scope.value, keyword.value, page.value, size.value)
    records.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载报表失败')
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  const res = await reportApi.listTemplates('', 1, 20)
  templates.value = res.data.records
}

function onPageChange(p: number, ps: number) {
  page.value = p
  size.value = ps
  reload()
}

async function remove(id: number) {
  try {
    await reportApi.deleteReport(id)
    message.success('已删除')
    reload()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

function sourceColor(s: string): string {
  return s === 'CUSTOM' ? 'blue' : s === 'TEMPLATE' ? 'green' : 'purple'
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.reports-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.list-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  max-width: 320px;
}

.report-name {
  font-weight: 600;
}

.template-name {
  font-weight: 600;
}

.template-cat {
  margin: 6px 0;
}

.template-desc {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  min-height: 34px;
}
</style>
