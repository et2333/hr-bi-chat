<template>
  <div class="synonym-panel">
    <!-- 筛选行 -->
    <div class="filter-row">
      <a-input
        v-model:value="keyword"
        placeholder="关键词：词条"
        allow-clear
        class="filter-item"
        @pressEnter="onSearch"
      />
      <a-button size="small" @click="onSearch">查询</a-button>
      <a-button type="primary" size="small" @click="openCreate">新增同义词组</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="synonyms"
      :loading="listLoading"
      :pagination="{ current: page, pageSize: size, total, showSizeChanger: false, onChange: onPageChange }"
      row-key="id"
      size="small"
    >
      <template #emptyText><a-empty description="暂无同义词" /></template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'termGroup'">
          <span class="term-text">{{ record.termGroup }}</span>
        </template>
        <template v-else-if="column.key === 'targetType'">
          <a-tag :color="record.targetType === 1 ? 'blue' : 'green'">{{ record.targetType === 1 ? '指标' : '维度' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'target'">
          {{ targetName(record.targetType, record.targetId) }}
        </template>
        <template v-else-if="column.key === 'hitCount'">{{ record.hitCount }}</template>
        <template v-else-if="column.key === 'actions'">
          <a-popconfirm title="确定删除该同义词？" @confirm="remove(record)">
            <a-button size="small" type="link" danger>删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <!-- 新增同义词组 Modal -->
    <a-modal
      v-model:open="createOpen"
      title="新增同义词组"
      :confirm-loading="saving"
      @ok="submit"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="同义词组" name="group">
          <a-input v-model:value="form.group" placeholder="同义词组展示名，如 流失率" />
        </a-form-item>
        <a-form-item label="目标类型" name="targetType">
          <a-radio-group v-model:value="form.targetType">
            <a-radio value="metric">指标</a-radio>
            <a-radio value="dimension">维度</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="归一目标" name="targetCode">
          <a-select
            v-model:value="form.targetCode"
            show-search
            placeholder="选择归一到的指标/维度"
            :options="targetOptions"
            :filter-option="filterOption"
          />
        </a-form-item>
        <a-form-item label="同义词" name="terms">
          <div class="terms-box">
            <div v-for="(term, idx) in form.terms" :key="idx" class="term-row">
              <a-input
                :value="term"
                @update:value="(v: string) => (form.terms[idx] = v)"
                placeholder="如 人员流失率"
              />
              <a-button size="small" type="link" danger :disabled="form.terms.length <= 1" @click="removeTerm(idx)">
                删除
              </a-button>
            </div>
            <a-button size="small" type="dashed" block @click="addTerm">+ 新增同义词</a-button>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { semanticApi } from '@/api'
import type { DimensionSummary, MetricSummary, SynonymItem } from '@/api/semantic'

// ---------------- 列表 ----------------
const synonyms = ref<SynonymItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)
const metrics = ref<MetricSummary[]>([])
const dimensions = ref<DimensionSummary[]>([])

const columns = [
  { title: '词条', key: 'termGroup' },
  { title: '目标类型', key: 'targetType', width: 100 },
  { title: '归一目标', key: 'target', width: 160 },
  { title: '命中次数', key: 'hitCount', dataIndex: 'hitCount', width: 90 },
  { title: '操作', key: 'actions', width: 90 },
]

async function loadSynonyms() {
  listLoading.value = true
  try {
    const res = await semanticApi.listSynonyms({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    synonyms.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载同义词列表失败')
  } finally {
    listLoading.value = false
  }
}

async function loadRefs() {
  const [m, d] = await Promise.all([
    semanticApi.listMetrics({ page: 1, size: 100 }),
    semanticApi.listDimensions({ page: 1, size: 100 }),
  ])
  metrics.value = m.data.records
  dimensions.value = d.data.records
}

/** 目标 id → 展示名 */
function targetName(type: number, id: number): string {
  if (type === 1) return metrics.value.find((m) => m.id === id)?.name ?? `#${id}`
  return dimensions.value.find((d) => d.id === id)?.name ?? `#${id}`
}

function onSearch() {
  page.value = 1
  loadSynonyms()
}

function onPageChange(p: number) {
  page.value = p
  loadSynonyms()
}

async function remove(record: SynonymItem) {
  try {
    await semanticApi.deleteSynonym(record.id)
    message.success('已删除')
    await loadSynonyms()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------------- 新增 ----------------
const createOpen = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

interface SynonymForm {
  group: string
  targetType: 'metric' | 'dimension'
  targetCode: string
  terms: string[]
}

const form = reactive<SynonymForm>({ group: '', targetType: 'metric', targetCode: '', terms: [''] })

const rules = {
  group: [{ required: true, message: '请填写同义词组名', trigger: 'blur' }],
  targetCode: [{ required: true, message: '请选择归一目标', trigger: 'change' }],
}

/** 目标下拉项（随目标类型切换） */
const targetOptions = computed(() => {
  if (form.targetType === 'metric') {
    return metrics.value.map((m) => ({ value: m.code, label: `${m.name}（${m.code}）` }))
  }
  return dimensions.value.map((d) => ({ value: d.code, label: `${d.name}（${d.code}）` }))
})

function filterOption(input: string, option: { label?: string }) {
  return String(option.label ?? '').toLowerCase().includes(input.toLowerCase())
}

function openCreate() {
  Object.assign(form, { group: '', targetType: 'metric', targetCode: '', terms: [''] })
  formRef.value?.clearValidate()
  createOpen.value = true
}

function addTerm() {
  form.terms.push('')
}

function removeTerm(idx: number) {
  if (form.terms.length > 1) form.terms.splice(idx, 1)
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const terms = form.terms.map((t) => t.trim()).filter(Boolean)
  if (!terms.length) {
    message.warning('请至少填写一个同义词')
    return
  }
  saving.value = true
  try {
    await semanticApi.createSynonym({
      group: form.group.trim(),
      terms,
      target: `${form.targetType}:${form.targetCode}`,
    })
    message.success('同义词组已创建')
    createOpen.value = false
    await loadSynonyms()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadSynonyms()
  loadRefs()
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

.filter-item {
  width: 220px;
}

.term-text {
  color: rgba(0, 0, 0, 0.85);
}

.terms-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.term-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
