<template>
  <div class="admin-page">
    <!-- 租户列表 -->
    <a-card :bordered="false" title="租户管理" class="pane-card">
      <div class="filter-row">
        <a-input
          v-model:value="keyword"
          placeholder="关键词：租户编码/名称"
          allow-clear
          class="filter-item"
          @pressEnter="onSearch"
        />
        <a-button size="small" @click="onSearch">查询</a-button>
        <a-button type="primary" size="small" @click="openCreate">新建租户</a-button>
      </div>
      <a-table
        :columns="columns"
        :data-source="tenants"
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
          <a-empty description="暂无租户" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ record.createdAt ? formatDateTime(record.createdAt) : '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm
              :title="record.status === 1 ? '确定停用该租户？停用后该租户下用户请求将被拒绝。' : '确定启用该租户？'"
              @confirm="toggleStatus(record)"
            >
              <a-button size="small" type="link">{{ record.status === 1 ? '停用' : '启用' }}</a-button>
            </a-popconfirm>
            <a-button size="small" type="link" :loading="usageLoadingId === record.id" @click="openUsage(record)">
              用量
            </a-button>
            <a-popconfirm title="切换到该租户？切换后建议刷新页面以生效（用于演示租户隔离）。" @confirm="switchTenant(record)">
              <a-button size="small" type="link">切换到此租户</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建 / 编辑 Modal -->
    <a-modal
      v-model:open="modalOpen"
      :title="modalMode === 'create' ? '新建租户' : '编辑租户'"
      :confirm-loading="saving"
      @ok="submitModal"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
        <a-form-item label="租户编码" name="tenantCode">
          <a-input v-model:value="form.tenantCode" :disabled="modalMode === 'edit'" placeholder="如 t03" />
        </a-form-item>
        <a-form-item label="租户名称" name="tenantName">
          <a-input v-model:value="form.tenantName" placeholder="如 演示租户C" />
        </a-form-item>
        <a-form-item label="用户配额" name="userQuota">
          <a-input-number v-model:value="form.userQuota" :min="1" class="num-input" />
        </a-form-item>
        <a-form-item label="报表配额" name="reportQuota">
          <a-input-number v-model:value="form.reportQuota" :min="1" class="num-input" />
        </a-form-item>
        <a-form-item label="订阅配额" name="subscriptionQuota">
          <a-input-number v-model:value="form.subscriptionQuota" :min="1" class="num-input" />
        </a-form-item>
        <a-form-item label="API 日配额" name="apiDailyQuota">
          <a-input-number v-model:value="form.apiDailyQuota" :min="1" class="num-input" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 用量 Modal -->
    <a-modal v-model:open="usageOpen" :title="`用量统计：${usageView?.tenantCode ?? ''}`" :footer="null">
      <div v-if="usageView" class="usage-list">
        <div v-for="u in usageItems" :key="u.label" class="usage-item">
          <div class="usage-head">
            <span>{{ u.label }}</span>
            <span class="usage-value">{{ u.used }} / {{ u.quota }}</span>
          </div>
          <a-progress :percent="u.percent" :status="u.percent >= 100 ? 'exception' : 'active'" :stroke-color="u.color" />
        </div>
      </div>
      <a-empty v-else description="暂无用量数据" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { tenantApi } from '@/api'
import { TENANT_NO_KEY } from '@/api/http'
import type { TenantCreateRequest, TenantPatchRequest, TenantView, UsageView } from '@/api/tenants'

// ---------------- 列表 ----------------
const tenants = ref<TenantView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)

const columns = [
  { title: '租户编码', key: 'tenantCode', dataIndex: 'tenantCode', width: 100 },
  { title: '名称', key: 'tenantName', dataIndex: 'tenantName' },
  { title: '状态', key: 'status', width: 80 },
  { title: '用户配额', key: 'userQuota', dataIndex: 'userQuota', width: 100 },
  { title: '报表配额', key: 'reportQuota', dataIndex: 'reportQuota', width: 100 },
  { title: '订阅配额', key: 'subscriptionQuota', dataIndex: 'subscriptionQuota', width: 100 },
  { title: 'API 日配额', key: 'apiDailyQuota', dataIndex: 'apiDailyQuota', width: 110 },
  { title: '创建时间', key: 'createdAt', width: 160 },
  { title: '操作', key: 'actions', width: 250 },
]

// ---------------- 新建 / 编辑 ----------------
interface TenantForm {
  tenantCode: string
  tenantName: string
  userQuota: number | undefined
  reportQuota: number | undefined
  subscriptionQuota: number | undefined
  apiDailyQuota: number | undefined
}

const modalOpen = ref(false)
const modalMode = ref<'create' | 'edit'>('create')
const editingId = ref(0)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<TenantForm>({
  tenantCode: '',
  tenantName: '',
  userQuota: undefined,
  reportQuota: undefined,
  subscriptionQuota: undefined,
  apiDailyQuota: undefined,
})

const rules = {
  tenantCode: [{ required: true, message: '请填写租户编码', trigger: 'blur' }],
  tenantName: [{ required: true, message: '请填写租户名称', trigger: 'blur' }],
  userQuota: [{ required: true, message: '请填写用户配额', trigger: 'change' }],
  reportQuota: [{ required: true, message: '请填写报表配额', trigger: 'change' }],
  subscriptionQuota: [{ required: true, message: '请填写订阅配额', trigger: 'change' }],
  apiDailyQuota: [{ required: true, message: '请填写 API 日配额', trigger: 'change' }],
}

function openCreate() {
  modalMode.value = 'create'
  editingId.value = 0
  Object.assign(form, {
    tenantCode: '',
    tenantName: '',
    userQuota: undefined,
    reportQuota: undefined,
    subscriptionQuota: undefined,
    apiDailyQuota: undefined,
  })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

function openEdit(record: TenantView) {
  modalMode.value = 'edit'
  editingId.value = record.id
  Object.assign(form, {
    tenantCode: record.tenantCode,
    tenantName: record.tenantName,
    userQuota: record.userQuota,
    reportQuota: record.reportQuota,
    subscriptionQuota: record.subscriptionQuota,
    apiDailyQuota: record.apiDailyQuota,
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
  const base = {
    tenantName: form.tenantName.trim(),
    userQuota: form.userQuota ?? 0,
    reportQuota: form.reportQuota ?? 0,
    subscriptionQuota: form.subscriptionQuota ?? 0,
    apiDailyQuota: form.apiDailyQuota ?? 0,
  }
  saving.value = true
  try {
    if (modalMode.value === 'create') {
      const payload: TenantCreateRequest = { tenantCode: form.tenantCode.trim(), ...base }
      await tenantApi.createTenant(payload)
      message.success('租户已创建')
    } else {
      const payload: TenantPatchRequest = base
      await tenantApi.patchTenant(editingId.value, payload)
      message.success('租户已更新')
    }
    modalOpen.value = false
    await loadTenants()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 操作 ----------------
async function toggleStatus(record: TenantView) {
  try {
    if (record.status === 1) {
      await tenantApi.disableTenant(record.id)
      message.success('租户已停用')
    } else {
      await tenantApi.enableTenant(record.id)
      message.success('租户已启用')
    }
    await loadTenants()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

function switchTenant(record: TenantView) {
  localStorage.setItem(TENANT_NO_KEY, record.tenantCode)
  message.success(`已切换到租户「${record.tenantName}」，建议刷新页面后生效（用于演示租户隔离）`)
}

// ---------------- 用量 ----------------
const usageOpen = ref(false)
const usageView = ref<UsageView | null>(null)
const usageLoadingId = ref<number | null>(null)

const usageItems = computed(() => {
  const u = usageView.value
  if (!u) return []
  return [
    { label: '用户', used: u.userUsed, quota: u.userQuota, color: '#1677ff' },
    { label: '报表', used: u.reportUsed, quota: u.reportQuota, color: '#52c41a' },
    { label: '订阅', used: u.subscriptionUsed, quota: u.subscriptionQuota, color: '#fa8c16' },
    { label: 'API 日调用', used: u.apiDailyUsed, quota: u.apiDailyQuota, color: '#722ed1' },
  ].map((item) => ({
    ...item,
    percent: item.quota > 0 ? Math.min(100, Math.round((item.used / item.quota) * 100)) : 0,
  }))
})

async function openUsage(record: TenantView) {
  usageOpen.value = true
  usageView.value = null
  usageLoadingId.value = record.id
  try {
    const res = await tenantApi.getTenantUsage(record.id)
    usageView.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载用量失败')
  } finally {
    usageLoadingId.value = null
  }
}

// ---------------- 加载 ----------------
async function loadTenants() {
  listLoading.value = true
  try {
    const res = await tenantApi.listTenants(page.value, size.value, keyword.value.trim())
    tenants.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载租户列表失败')
  } finally {
    listLoading.value = false
  }
}

function onSearch() {
  page.value = 1
  loadTenants()
}

function onPageChange(p: number) {
  page.value = p
  loadTenants()
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  loadTenants()
})
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
  width: 220px;
}

.num-input {
  width: 100%;
}

.usage-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.usage-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.usage-value {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
