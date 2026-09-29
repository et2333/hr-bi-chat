<template>
  <div class="dimension-panel">
    <!-- 筛选行 -->
    <div class="filter-row">
      <a-input
        v-model:value="keyword"
        placeholder="关键词：维度编码/名称"
        allow-clear
        class="filter-item"
        @pressEnter="onSearch"
      />
      <a-button size="small" @click="onSearch">查询</a-button>
      <a-button type="primary" size="small" @click="openCreate">新建维度</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="dimensions"
      :loading="listLoading"
      :pagination="{ current: page, pageSize: size, total, showSizeChanger: false, onChange: onPageChange }"
      row-key="id"
      size="small"
    >
      <template #emptyText><a-empty description="暂无维度" /></template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'dimType'">
          <a-tag :color="dimTypeColor(record.dimType)">{{ dimTypeLabel(record.dimType) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
          <a-popconfirm title="确认删除该维度？删除后不可直接恢复。" @confirm="remove(record)">
            <a-button size="small" type="link" danger>删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <!-- 新建 / 编辑 Drawer -->
    <a-drawer
      v-model:open="editorOpen"
      :title="mode === 'create' ? '新建维度' : `编辑维度：${form.name}`"
      width="620px"
      :footer="null"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item ref="codeItem" label="维度编码" name="code">
          <a-input v-model:value="form.code" :disabled="mode === 'edit'" placeholder="如 contract_type" />
        </a-form-item>
        <a-form-item label="维度名称" name="name">
          <a-input v-model:value="form.name" placeholder="如 合同类型" />
        </a-form-item>
        <a-form-item label="维度类型" name="dimType">
          <a-select v-model:value="form.dimType">
            <a-select-option :value="1">结构维度（组织树）</a-select-option>
            <a-select-option :value="2">枚举维度</a-select-option>
            <a-select-option :value="3">区间维度</a-select-option>
          </a-select>
        </a-form-item>
        <!-- 物理映射：图表引擎按此元数据动态生成维度 SQL（对标 Quick BI 维度建模） -->
        <a-divider class="mapping-divider">物理映射（可选，配置后图表可按该维度分组/下钻）</a-divider>
        <a-form-item label="来源表">
          <a-input v-model:value="form.refTable" placeholder="如 dim_org / dim_region" />
        </a-form-item>
        <a-form-item label="主键列">
          <a-input v-model:value="form.keyColumn" placeholder="如 org_key / region_key" />
        </a-form-item>
        <a-form-item label="取值列">
          <a-input v-model:value="form.valueColumn" placeholder="如 org_name / region_name" />
        </a-form-item>
        <a-form-item label="层级父键列">
          <a-input v-model:value="form.parentColumn" placeholder="如 parent_org_key（配置后支持下钻）" />
        </a-form-item>
        <a-form-item label="事实表外键列">
          <a-input v-model:value="form.factColumn" placeholder="留空则与主键列同名" />
        </a-form-item>
        <a-form-item label="时效标记列">
          <a-input v-model:value="form.currentColumn" placeholder="如 is_current" />
        </a-form-item>
        <div class="mapping-hint">
          仅填取值列（不填来源表）= 事实表自带属性维度；填来源表 + 主键列 + 取值列 = 查表维度。
        </div>

        <!-- 枚举值编辑 -->
        <template v-if="form.dimType !== 1">
          <a-form-item label="枚举值" class="values-form-item">
            <div class="values-box">
              <div v-for="(item, idx) in enumValues" :key="idx" class="value-row">
                <a-input v-model:value="item.valueCode" placeholder="编码" class="value-code" />
                <a-input v-model:value="item.valueLabel" placeholder="名称" class="value-label" />
                <a-input-number v-model:value="item.sortNo" :min="1" class="value-sort" placeholder="序号" />
                <a-button size="small" type="link" danger @click="removeValue(idx)">删除</a-button>
              </div>
              <a-button size="small" type="dashed" block @click="addValue">+ 新增枚举值</a-button>
            </div>
          </a-form-item>
        </template>
      </a-form>

      <div class="editor-footer">
        <a-button @click="editorOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="submit">保存</a-button>
      </div>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { semanticApi } from '@/api'
import type { DimensionSummary, DimensionValueItem } from '@/api/semantic'

// ---------------- 列表 ----------------
const dimensions = ref<DimensionSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)

const columns = [
  { title: '维度编码', key: 'code', dataIndex: 'code', width: 160 },
  { title: '名称', key: 'name', dataIndex: 'name' },
  { title: '类型', key: 'dimType', width: 160 },
  { title: '操作', key: 'actions', width: 170 },
]

function dimTypeLabel(t: number): string {
  return { 1: '结构维度', 2: '枚举维度', 3: '区间维度' }[t] ?? '未知'
}

function dimTypeColor(t: number): string {
  return { 1: 'blue', 2: 'green', 3: 'orange' }[t] ?? 'default'
}

async function loadDimensions() {
  listLoading.value = true
  try {
    const res = await semanticApi.listDimensions({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    dimensions.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载维度列表失败')
  } finally {
    listLoading.value = false
  }
}

function onSearch() {
  page.value = 1
  loadDimensions()
}

function onPageChange(p: number) {
  page.value = p
  loadDimensions()
}

/** 删除维度；被指标可用维度/同义词/报表引用时后端拒绝，弹窗展示原因。 */
async function remove(record: DimensionSummary) {
  try {
    await semanticApi.deleteDimension(record.id)
    message.success('已删除')
    await loadDimensions()
  } catch (e) {
    Modal.error({
      title: '删除被拒绝',
      content: e instanceof Error ? e.message : '删除失败，请稍后重试',
    })
  }
}

// ---------------- 新建 / 编辑 ----------------
const editorOpen = ref(false)
const mode = ref<'create' | 'edit'>('create')
const editingId = ref(0)
const saving = ref(false)
const formRef = ref<FormInstance>()

interface DimForm {
  code: string
  name: string
  dimType: number
  refTable: string
  keyColumn: string
  valueColumn: string
  parentColumn: string
  factColumn: string
  currentColumn: string
}

const form = reactive<DimForm>({
  code: '', name: '', dimType: 2, refTable: '',
  keyColumn: '', valueColumn: '', parentColumn: '', factColumn: '', currentColumn: '',
})
const enumValues = ref<DimensionValueItem[]>([])

const rules = {
  code: [{ required: true, message: '请填写维度编码', trigger: 'blur' }],
  name: [{ required: true, message: '请填写维度名称', trigger: 'blur' }],
  dimType: [{ required: true, message: '请选择维度类型', trigger: 'change' }],
}

function resetForm() {
  Object.assign(form, {
    code: '', name: '', dimType: 2, refTable: '',
    keyColumn: '', valueColumn: '', parentColumn: '', factColumn: '', currentColumn: '',
  })
  enumValues.value = []
}

function openCreate() {
  mode.value = 'create'
  editingId.value = 0
  resetForm()
  editorOpen.value = true
}

async function openEdit(record: DimensionSummary) {
  mode.value = 'edit'
  editingId.value = record.id
  // 取详情（含枚举值）
  try {
    const res = await semanticApi.getDimension(record.id)
    const d = res.data
    Object.assign(form, {
      code: d.code,
      name: d.name,
      dimType: d.dimType,
      refTable: d.refTable ?? '',
      keyColumn: d.keyColumn ?? '',
      valueColumn: d.valueColumn ?? '',
      parentColumn: d.parentColumn ?? '',
      factColumn: d.factColumn ?? '',
      currentColumn: d.currentColumn ?? '',
    })
    enumValues.value = d.values.map((v) => ({ ...v }))
    editorOpen.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载维度详情失败')
  }
}

function addValue() {
  enumValues.value.push({
    valueCode: '',
    valueLabel: '',
    parentCode: null,
    sortNo: enumValues.value.length + 1,
  })
}

function removeValue(idx: number) {
  enumValues.value.splice(idx, 1)
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const ref = form.refTable.trim()
  const key = form.keyColumn.trim()
  const value = form.valueColumn.trim()
  const parent = form.parentColumn.trim()
  const fact = form.factColumn.trim()
  const current = form.currentColumn.trim()
  // 物理映射一致性校验（与后端 applyMapping 规则对齐）
  if (ref && (!key || !value)) {
    message.warning('查表维度需同时配置来源表、主键列与取值列')
    return
  }
  if (!ref && key) {
    message.warning('配置主键列时必须同时配置来源表')
    return
  }
  if (parent && !ref) {
    message.warning('层级下钻仅支持查表维度，请补全来源表与主键列')
    return
  }
  if (fact && !key) {
    message.warning('配置事实表外键列时必须配置主键列')
    return
  }
  // 行内编码/名称不能为空
  if (form.dimType !== 1) {
    const invalid = enumValues.value.some((v) => !v.valueCode.trim() || !v.valueLabel.trim())
    if (invalid) {
      message.warning('请补全枚举值的编码与名称，或删除空行')
      return
    }
  }
  saving.value = true
  const body = {
    name: form.name.trim(),
    code: form.code.trim(),
    dimType: form.dimType,
    refTable: ref || null,
    keyColumn: key || null,
    valueColumn: value || null,
    parentColumn: parent || null,
    factColumn: fact || null,
    currentColumn: current || null,
    enumValues: form.dimType === 1 ? [] : enumValues.value.map((v, i) => ({
      valueCode: v.valueCode.trim(),
      valueLabel: v.valueLabel.trim(),
      parentCode: v.parentCode,
      sortNo: v.sortNo || i + 1,
    })),
  }
  try {
    if (mode.value === 'create') {
      await semanticApi.createDimension(body)
      message.success('维度已创建')
    } else {
      await semanticApi.patchDimension(editingId.value, body)
      message.success('维度已更新')
    }
    editorOpen.value = false
    await loadDimensions()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadDimensions)
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

.values-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.value-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.value-code {
  width: 110px;
  flex-shrink: 0;
}

.value-label {
  flex: 1;
  min-width: 0;
}

.value-sort {
  width: 80px;
  flex-shrink: 0;
}

.mapping-divider {
  margin: 8px 0 16px;
}

.mapping-hint {
  margin: -8px 0 16px;
  color: #999;
  font-size: 12px;
}

.editor-footer {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 24px;
  border-top: 1px solid #f0f0f0;
  background: #fff;
}
</style>
