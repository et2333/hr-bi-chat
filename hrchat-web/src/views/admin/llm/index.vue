<template>
  <div class="admin-page">
    <a-alert
      class="tenant-tip"
      type="info"
      show-icon
      :message="`LLM 配置按当前租户生效（${currentTenant}）；未配置时回退系统默认`"
    />
    <!-- 监控概览 -->
    <a-card :bordered="false" title="监控概览" class="pane-card">
      <a-row :gutter="[16, 16]">
        <a-col v-for="item in monitorItems" :key="item.label" :xs="12" :md="6">
          <div class="quality-box">
            <div class="quality-value" :style="{ color: item.color }">{{ item.value }}</div>
            <div class="quality-label">{{ item.label }}</div>
          </div>
        </a-col>
      </a-row>
    </a-card>

    <!-- 模型列表 -->
    <a-card :bordered="false" title="模型配置" class="pane-card">
      <div class="filter-row">
        <a-input
          v-model:value="keyword"
          placeholder="关键词：模型编码/名称"
          allow-clear
          class="filter-item"
          @pressEnter="onSearch"
        />
        <a-button size="small" @click="onSearch">查询</a-button>
        <a-button type="primary" size="small" @click="openCreate">新建模型</a-button>
      </div>
      <a-table
        :columns="columns"
        :data-source="models"
        :loading="listLoading"
        :pagination="{
          current: page,
          pageSize: size,
          total,
          onChange: onPageChange,
        }"
        row-key="id"
        size="small"
      >
        <template #emptyText>
          <a-empty description="暂无模型配置" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'deployState'">
            <a-tag :color="deployColor(record.deployState)">{{ deployLabel(record.deployState) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'healthStatus'">
            <a-tag :color="healthColor(record.healthStatus)">{{ record.healthStatus }}</a-tag>
          </template>
          <template v-else-if="column.key === 'updatedAt'">
            {{ record.updatedAt ? formatDateTime(record.updatedAt) : '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确定一键部署该模型？" @confirm="deploy(record)">
              <a-button size="small" type="link" :loading="deployingId === record.id">部署</a-button>
            </a-popconfirm>
            <a-button size="small" type="link" @click="openVersions(record)">版本</a-button>
            <a-button size="small" type="link" :loading="checkingId === record.id" @click="checkHealth(record)">
              健康
            </a-button>
            <a-popconfirm title="确定删除该模型？" @confirm="remove(record)">
              <a-button size="small" type="link" danger>删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建 / 编辑 Modal -->
    <a-modal
      v-model:open="modalOpen"
      :title="modalMode === 'create' ? '新建模型' : '编辑模型'"
      :confirm-loading="saving"
      @ok="submitModal"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="模型编码" name="modelCode">
          <a-input v-model:value="form.modelCode" :disabled="modalMode === 'edit'" placeholder="如 llm-demo" />
        </a-form-item>
        <a-form-item label="模型名称" name="modelName">
          <a-input v-model:value="form.modelName" placeholder="如 演示模型" />
        </a-form-item>
        <a-form-item label="厂商" name="vendor">
          <a-select v-model:value="form.vendor" placeholder="选择厂商">
            <a-select-option v-for="v in VENDORS" :key="v" :value="v">{{ v }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="Base URL" name="baseUrl">
          <a-input v-model:value="form.baseUrl" :placeholder="modalMode === 'edit' ? '留空则不修改' : '如 http://localhost:11434'" />
        </a-form-item>
        <a-form-item label="API Key" name="apiKey">
          <a-input-password
            v-model:value="form.apiKey"
            :placeholder="modalMode === 'edit' ? '留空则不修改' : '请输入 API Key'"
          />
        </a-form-item>
        <a-form-item label="模型" name="model">
          <a-input v-model:value="form.model" placeholder="如 qwen-plus / mock-1" />
        </a-form-item>
        <a-form-item label="温度" name="temperature">
          <a-input-number v-model:value="form.temperature" :min="0" :max="2" :step="0.1" class="num-input" />
        </a-form-item>
        <a-form-item label="最大 Tokens" name="maxTokens">
          <a-input-number v-model:value="form.maxTokens" :min="1" class="num-input" />
        </a-form-item>
        <a-form-item label="部署地址" name="deployUrl">
          <a-input v-model:value="form.deployUrl" :placeholder="modalMode === 'edit' ? '留空则不修改' : '如 http://localhost:8080'" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 版本历史 Drawer -->
    <a-drawer
      v-model:open="drawerOpen"
      :title="drawerModel ? `版本历史：${drawerModel.modelName}` : '版本历史'"
      :loading="versionsLoading"
      width="520"
    >
      <a-timeline v-if="versions.length">
        <a-timeline-item v-for="v in versions" :key="v.id" :color="versionColor(v.applyResult)">
          <div class="version-item">
            <div class="version-head">
              <span class="version-no">v{{ v.versionNo }}</span>
              <a-tag :color="applyColor(v.applyResult)">{{ applyLabel(v.applyResult) }}</a-tag>
            </div>
            <div class="version-meta">
              应用时间：{{ v.appliedAt ? formatDateTime(v.appliedAt) : '-' }}
              · 操作人：{{ v.appliedBy || '-' }}
            </div>
            <div v-if="v.changeNote" class="version-note">备注：{{ v.changeNote }}</div>
            <pre class="version-json">{{ v.configJson }}</pre>
            <a-popconfirm title="确定回滚到该版本？" @confirm="rollback(v)">
              <a-button size="small" type="link" :loading="rollingBackId === v.id">回滚</a-button>
            </a-popconfirm>
          </div>
        </a-timeline-item>
      </a-timeline>
      <a-empty v-else description="暂无版本记录" />
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { llmApi } from '@/api'
import { TENANT_NO_KEY } from '@/api/http'
import type {
  LlmModelCreateRequest,
  LlmModelView,
  LlmVersionView,
  MonitorView,
} from '@/api/llm'

const VENDORS = ['openai', 'mock', 'other']

/** 当前租户（取自请求头同源 localStorage，缺省 t01） */
const currentTenant = computed(() => localStorage.getItem(TENANT_NO_KEY) || 't01')

// ---------------- 列表 ----------------
const models = ref<LlmModelView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)

const columns = [
  { title: '模型编码', key: 'modelCode', dataIndex: 'modelCode', width: 140 },
  { title: '名称', key: 'modelName', dataIndex: 'modelName' },
  { title: '厂商', key: 'vendor', dataIndex: 'vendor', width: 100 },
  { title: '模型', key: 'model', dataIndex: 'model', width: 140 },
  { title: '状态', key: 'status', width: 80 },
  { title: '部署状态', key: 'deployState', width: 100 },
  { title: '健康', key: 'healthStatus', width: 100 },
  { title: '版本数', key: 'versionCount', dataIndex: 'versionCount', width: 80 },
  { title: '更新时间', key: 'updatedAt', width: 160 },
  { title: '操作', key: 'actions', width: 240 },
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

// ---------------- 监控概览 ----------------
const monitor = ref<MonitorView | null>(null)

const monitorItems = computed(() => {
  const m = monitor.value
  return [
    { label: '总模型', value: m?.total ?? '-', color: '#1677ff' },
    { label: '已部署', value: m?.active ?? '-', color: '#52c41a' },
    { label: '失败', value: m?.failed ?? '-', color: '#ff4d4f' },
    { label: '降级', value: m?.degraded ?? '-', color: '#fa8c16' },
  ]
})

// ---------------- 新建 / 编辑 ----------------
interface LlmForm {
  modelCode: string
  modelName: string
  vendor: string
  baseUrl: string
  apiKey: string
  model: string
  temperature: number | undefined
  maxTokens: number | undefined
  deployUrl: string
}

const modalOpen = ref(false)
const modalMode = ref<'create' | 'edit'>('create')
const editingId = ref(0)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<LlmForm>({
  modelCode: '',
  modelName: '',
  vendor: 'openai',
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: undefined,
  maxTokens: undefined,
  deployUrl: '',
})

const rules = computed(() => ({
  modelCode: [{ required: true, message: '请填写模型编码', trigger: 'blur' }],
  modelName: [{ required: true, message: '请填写模型名称', trigger: 'blur' }],
  vendor: [{ required: true, message: '请选择厂商', trigger: 'change' }],
  model: [{ required: true, message: '请填写模型标识（如 deepseek-chat）', trigger: 'blur' }],
  ...(modalMode.value === 'create'
    ? {
        baseUrl: [{ required: true, message: '请填写模型 API Base URL', trigger: 'blur' }],
        deployUrl: [{ required: true, message: '请填写 Python 网关 deployUrl', trigger: 'blur' }],
      }
    : {}),
}))

function openCreate() {
  modalMode.value = 'create'
  editingId.value = 0
  Object.assign(form, {
    modelCode: '',
    modelName: '',
    vendor: 'openai',
    baseUrl: '',
    apiKey: '',
    model: '',
    temperature: undefined,
    maxTokens: undefined,
    deployUrl: '',
  })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

function openEdit(record: LlmModelView) {
  modalMode.value = 'edit'
  editingId.value = record.id
  Object.assign(form, {
    modelCode: record.modelCode,
    modelName: record.modelName,
    vendor: record.vendor,
    baseUrl: '',
    apiKey: '',
    model: record.model ?? '',
    temperature: undefined,
    maxTokens: undefined,
    deployUrl: '',
  })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

async function submitModal() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload: LlmModelCreateRequest = {
    modelCode: form.modelCode.trim(),
    modelName: form.modelName.trim(),
    vendor: form.vendor,
    baseUrl: form.baseUrl.trim() || undefined,
    apiKey: form.apiKey.trim() || undefined,
    model: form.model.trim() || undefined,
    temperature: form.temperature ?? undefined,
    maxTokens: form.maxTokens ?? undefined,
    deployUrl: form.deployUrl.trim() || undefined,
  }
  saving.value = true
  try {
    if (modalMode.value === 'create') {
      await llmApi.createLlmModel(payload)
      message.success('模型已创建')
    } else {
      await llmApi.patchLlmModel(editingId.value, payload)
      message.success('模型已更新')
    }
    modalOpen.value = false
    await reloadAll()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 操作 ----------------
const deployingId = ref<number | null>(null)
const checkingId = ref<number | null>(null)

async function deploy(record: LlmModelView) {
  deployingId.value = record.id
  try {
    const res = await llmApi.deployLlmModel(record.id)
    message.success(`部署完成：${res.data.state}（健康 ${res.data.healthStatus}，延迟 ${res.data.latencyMs}ms）`)
    await reloadAll()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '部署失败')
  } finally {
    deployingId.value = null
  }
}

async function checkHealth(record: LlmModelView) {
  checkingId.value = record.id
  try {
    const res = await llmApi.checkLlmHealth(record.id)
    record.healthStatus = res.data.healthStatus
    message.success(`健康检查：${res.data.healthStatus}（延迟 ${res.data.latencyMs}ms）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '健康检查失败')
  } finally {
    checkingId.value = null
  }
}

async function remove(record: LlmModelView) {
  try {
    await llmApi.deleteLlmModel(record.id)
    message.success('模型已删除')
    await reloadAll()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------------- 版本 Drawer ----------------
const drawerOpen = ref(false)
const drawerModel = ref<LlmModelView | null>(null)
const versions = ref<LlmVersionView[]>([])
const versionsLoading = ref(false)
const rollingBackId = ref<number | null>(null)

function versionColor(r: string): string {
  return { SUCCESS: 'green', SIMULATED: 'orange', ROLLBACK: 'orange', FAILED: 'red', PENDING: 'gray' }[r] ?? 'gray'
}

function applyColor(r: string): string {
  return { SUCCESS: 'green', SIMULATED: 'orange', ROLLBACK: 'orange', FAILED: 'red', PENDING: 'default' }[r] ?? 'default'
}

function applyLabel(r: string): string {
  return r === 'SIMULATED' ? '模拟应用' : r
}

async function openVersions(record: LlmModelView) {
  drawerModel.value = record
  drawerOpen.value = true
  versionsLoading.value = true
  try {
    const res = await llmApi.listLlmVersions(record.id)
    versions.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载版本失败')
  } finally {
    versionsLoading.value = false
  }
}

async function rollback(version: LlmVersionView) {
  if (!drawerModel.value) return
  rollingBackId.value = version.id
  try {
    const res = await llmApi.rollbackLlmModel(drawerModel.value.id, { versionId: version.id })
    message.success(`已回滚：${res.data.state}`)
    await openVersions(drawerModel.value)
    await reloadAll()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '回滚失败')
  } finally {
    rollingBackId.value = null
  }
}

// ---------------- 加载 ----------------
async function loadModels() {
  listLoading.value = true
  try {
    const res = await llmApi.listLlmModels(page.value, size.value, keyword.value.trim())
    models.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载模型列表失败')
  } finally {
    listLoading.value = false
  }
}

async function loadMonitor() {
  try {
    const res = await llmApi.getLlmMonitor()
    monitor.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载监控概览失败')
  }
}

async function reloadAll() {
  await Promise.all([loadModels(), loadMonitor()])
}

function onSearch() {
  page.value = 1
  loadModels()
}

function onPageChange(p: number) {
  page.value = p
  loadModels()
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  reloadAll()
})
</script>

<style scoped>
.pane-card {
  margin-bottom: 16px;
}

.tenant-tip {
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
  width: 220px;
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

.num-input {
  width: 100%;
}

.version-item {
  padding-bottom: 4px;
}

.version-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.version-no {
  font-weight: 600;
}

.version-meta {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.version-note {
  margin-top: 4px;
  font-size: 12px;
}

.version-json {
  margin: 8px 0 4px;
  padding: 8px;
  max-height: 160px;
  overflow: auto;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
