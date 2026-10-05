<template>
  <div class="admin-page">
    <a-card :bordered="false" class="pane-card">
      <a-alert type="info" show-icon message="此页仅管理示例问句；真实评测尚未接入，暂不提供回归运行或准确率。" style="margin-bottom: 16px" />
      <div class="filter-row">
        <a-input v-model:value="evalName" placeholder="评测集名称" class="filter-item" style="max-width: 240px" />
        <a-button type="primary" size="small" @click="createSet">新建评测集</a-button>
        <a-button size="small" @click="loadSets">刷新</a-button>
      </div>
      <a-table :columns="setColumns" :data-source="questionSets" :pagination="false" row-key="setId" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">{{ formatDateTime(record.createdAt) }}</template>
        </template>
      </a-table>

    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '@/api'
import type { QuestionSetView } from '@/api/types'

const questionSets = ref<QuestionSetView[]>([])
const evalName = ref('')

const setColumns = [
  { title: '评测集 ID', key: 'setId', dataIndex: 'setId' },
  { title: '名称', key: 'name', dataIndex: 'name' },
  { title: '问句数', key: 'questionCount', dataIndex: 'questionCount', width: 90 },
  { title: '创建时间', key: 'createdAt', width: 170 },
]

async function loadSets() {
  try {
    const res = await adminApi.listQuestionSets()
    questionSets.value = res.data.records
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载评测集失败')
  }
}

async function createSet() {
  if (!evalName.value.trim()) {
    message.warning('请填写评测集名称')
    return
  }
  try {
    await adminApi.createQuestionSet({
      name: evalName.value.trim(),
      questions: [
        { question: '研发中心在职人数', sceneTag: 'aggregation', expectJson: '{"metric":"headcount"}' },
        { question: '上月离职率是多少', sceneTag: 'fuzzy_time', expectJson: '{"metric":"turnover_rate"}' },
      ],
    })
    message.success('评测集已创建')
    evalName.value = ''
    await loadSets()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建评测集失败')
  }
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(loadSets)
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
