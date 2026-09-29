<template>
  <div class="admin-page">
    <!-- 系统默认 LLM 配置 -->
    <a-card :bordered="false" title="系统默认 LLM 配置" class="pane-card">
      <div class="filter-row">
        <span class="llm-key">llm.default.profile</span>
        <a-tag color="geekblue">{{ defaultLlmProfile?.settingValue ?? '-' }}</a-tag>
        <a-button type="primary" size="small" @click="openEditDefault">编辑</a-button>
      </div>
    </a-card>

    <!-- 设置列表 -->
    <a-card :bordered="false" title="系统设置" class="pane-card">
      <div class="filter-row">
        <a-button type="primary" size="small" @click="openCreate">新增设置</a-button>
        <a-button size="small" @click="loadSettings">刷新</a-button>
      </div>
      <a-table
        :columns="columns"
        :data-source="settings"
        :loading="loading"
        :pagination="false"
        row-key="settingKey"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'updatedAt'">
            {{ record.updatedAt ? formatDateTime(record.updatedAt) : '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确定删除该设置？" @confirm="remove(record)">
              <a-button size="small" type="link" danger>删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建 / 编辑 Modal -->
    <a-modal
      v-model:open="modalOpen"
      :title="modalMode === 'create' ? '新增设置' : '编辑设置'"
      :confirm-loading="saving"
      @ok="submitModal"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="配置键" name="key">
          <a-input v-model:value="form.key" :disabled="modalMode === 'edit'" placeholder="如 llm.default.profile" />
        </a-form-item>
        <a-form-item label="配置值" name="value">
          <a-input v-model:value="form.value" placeholder="请输入配置值" />
        </a-form-item>
        <a-form-item label="描述" name="description">
          <a-input v-model:value="form.description" placeholder="选填" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { adminApi } from '@/api'
import type { SysSetting } from '@/api/types'

const LLM_PROFILE_KEY = 'llm.default.profile'

const settings = ref<SysSetting[]>([])
const loading = ref(false)

const columns = [
  { title: '配置键', key: 'settingKey', dataIndex: 'settingKey', width: 220 },
  { title: '配置值', key: 'settingValue', dataIndex: 'settingValue' },
  { title: '描述', key: 'description', dataIndex: 'description' },
  { title: '更新时间', key: 'updatedAt', width: 160 },
  { title: '操作', key: 'actions', width: 120 },
]

const defaultLlmProfile = computed(() => settings.value.find((s) => s.settingKey === LLM_PROFILE_KEY) ?? null)

// ---------------- 新建 / 编辑 ----------------
interface SettingForm {
  key: string
  value: string
  description: string
}

const modalOpen = ref(false)
const modalMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<SettingForm>({ key: '', value: '', description: '' })

const rules = {
  key: [{ required: true, message: '请填写配置键', trigger: 'blur' }],
  value: [{ required: true, message: '请填写配置值', trigger: 'blur' }],
}

function openCreate() {
  modalMode.value = 'create'
  Object.assign(form, { key: '', value: '', description: '' })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

function openEdit(record: SysSetting) {
  modalMode.value = 'edit'
  Object.assign(form, { key: record.settingKey, value: record.settingValue, description: record.description ?? '' })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

function openEditDefault() {
  modalMode.value = 'edit'
  Object.assign(form, {
    key: LLM_PROFILE_KEY,
    value: defaultLlmProfile.value?.settingValue ?? '',
    description: defaultLlmProfile.value?.description ?? '系统默认 LLM 档位',
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
  saving.value = true
  try {
    await adminApi.upsertSetting({
      key: form.key.trim(),
      value: form.value.trim(),
      description: form.description.trim() || undefined,
    })
    message.success('设置已保存')
    modalOpen.value = false
    await loadSettings()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 删除 ----------------
async function remove(record: SysSetting) {
  try {
    await adminApi.deleteSetting(record.settingKey)
    message.success('设置已删除')
    await loadSettings()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------------- 加载 ----------------
async function loadSettings() {
  loading.value = true
  try {
    const res = await adminApi.listSettings()
    settings.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载系统设置失败')
  } finally {
    loading.value = false
  }
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(loadSettings)
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

.llm-key {
  font-family: monospace;
  color: rgba(0, 0, 0, 0.85);
}
</style>
