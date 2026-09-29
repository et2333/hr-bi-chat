<template>
  <div class="admin-page">
    <!-- 角色列表 -->
    <a-card :bordered="false" title="角色列表" class="pane-card">
      <div class="filter-row">
        <a-button type="primary" size="small" @click="openCreate">新建角色</a-button>
        <a-button size="small" @click="loadRoles">刷新</a-button>
      </div>
      <a-table
        :columns="roleColumns"
        :data-source="roles"
        :loading="listLoading"
        :pagination="{ current: page, pageSize: size, total, onChange: onPageChange }"
        row-key="roleId"
        size="small"
        :expandable="{ expandedRowKeys, onExpand }"
      >
        <template #expandedRowRender="{ record }">
          <div class="role-perms">
            <a-tag v-for="p in record.functionPerms" :key="p" color="geekblue">{{ p }}</a-tag>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'dataLevel'">L{{ record.dataLevel }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- 用户生效权限排障 -->
    <a-card :bordered="false" title="用户生效权限（排障）" class="pane-card">
      <div class="filter-row">
        <a-input-number v-model:value="permUserId" placeholder="用户 ID" class="filter-item" :min="1" />
        <a-button type="primary" size="small" @click="loadEffective">查询</a-button>
      </div>
      <div v-if="effectivePerms" class="perm-result">
        <p class="perm-line">角色：<a-tag v-for="r in effectivePerms.roles" :key="r" color="blue">{{ r }}</a-tag></p>
        <p class="perm-line">
          数据范围：
          <a-tag v-for="d in effectivePerms.dataScopes" :key="d.orgNodeId" color="green">
            {{ d.orgName }}（scope={{ d.scope }}）
          </a-tag>
        </p>
        <p class="perm-line">
          字段策略：<a-tag v-for="f in effectivePerms.fieldPolicies" :key="f" color="orange">{{ f }}</a-tag>
        </p>
        <p class="perm-line">
          功能权限：<a-tag v-for="p in effectivePerms.functionPerms" :key="p" color="geekblue">{{ p }}</a-tag>
        </p>
        <p class="perm-line perm-time">生效时间：{{ formatDateTime(effectivePerms.effectiveAt) }}</p>
      </div>
    </a-card>

    <!-- 新建角色 Modal -->
    <a-modal v-model:open="modalOpen" title="新建角色" :confirm-loading="saving" @ok="submitModal">
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="角色编码" name="roleCode">
          <a-input v-model:value="form.roleCode" placeholder="如 DATA_ANALYST" />
        </a-form-item>
        <a-form-item label="角色名称" name="roleName">
          <a-input v-model:value="form.roleName" placeholder="如 数据分析员" />
        </a-form-item>
        <a-form-item label="数据级别" name="dataLevel">
          <a-input-number v-model:value="form.dataLevel" :min="1" :max="3" class="num-input" />
        </a-form-item>
        <a-form-item label="功能权限码" name="functionPerms">
          <a-select v-model:value="form.functionPerms" placeholder="选择功能权限码（可多选）" mode="multiple">
            <a-select-option v-for="p in PERM_OPTIONS" :key="p" :value="p">{{ p }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { adminApi } from '@/api'
import type { EffectivePermissions, RoleView } from '@/api/types'

/** 可选功能权限码（对齐菜单 required-perm 与路由 meta.permission） */
const PERM_OPTIONS = [
  'admin:view',
  'admin:tenant:manage',
  'admin:user:manage',
  'admin:authz:manage',
  'admin:llm:manage',
  'admin:audit:read',
  'admin:system:manage',
  'admin:data:read',
  'admin:eval:manage',
]

// ---------------- 角色列表 ----------------
const roles = ref<RoleView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const listLoading = ref(false)
const expandedRowKeys = ref<number[]>([])

const roleColumns = [
  { title: '角色 ID', key: 'roleId', dataIndex: 'roleId', width: 90 },
  { title: '编码', key: 'roleCode', dataIndex: 'roleCode', width: 160 },
  { title: '名称', key: 'roleName', dataIndex: 'roleName' },
  { title: '数据级别', key: 'dataLevel', width: 90 },
]

// ---------------- 新建角色 ----------------
interface RoleForm {
  roleCode: string
  roleName: string
  dataLevel: number | undefined
  functionPerms: string[]
}

const modalOpen = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<RoleForm>({ roleCode: '', roleName: '', dataLevel: undefined, functionPerms: [] })

const rules = {
  roleCode: [{ required: true, message: '请填写角色编码', trigger: 'blur' }],
  roleName: [{ required: true, message: '请填写角色名称', trigger: 'blur' }],
  dataLevel: [{ required: true, message: '请填写数据级别', trigger: 'change' }],
}

function openCreate() {
  Object.assign(form, { roleCode: '', roleName: '', dataLevel: undefined, functionPerms: [] })
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
    await adminApi.createRole({
      roleCode: form.roleCode.trim(),
      roleName: form.roleName.trim(),
      dataLevel: form.dataLevel ?? 1,
      functionPerms: form.functionPerms,
    })
    message.success('角色已创建')
    modalOpen.value = false
    await loadRoles()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 用户生效权限（排障） ----------------
const permUserId = ref(1)
const effectivePerms = ref<EffectivePermissions | null>(null)

async function loadEffective() {
  try {
    const res = await adminApi.effectivePermissions(permUserId.value)
    effectivePerms.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '查询生效权限失败')
  }
}

// ---------------- 加载 ----------------
async function loadRoles() {
  listLoading.value = true
  try {
    const res = await adminApi.listRoles(page.value, size.value)
    roles.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载角色失败')
  } finally {
    listLoading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  loadRoles()
}

function onExpand(expanded: boolean, record: RoleView) {
  expandedRowKeys.value = expanded
    ? [...expandedRowKeys.value, record.roleId]
    : expandedRowKeys.value.filter((id) => id !== record.roleId)
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(loadRoles)
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

.num-input {
  width: 100%;
}

.role-perms {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 0;
}

.perm-result {
  padding: 12px;
  background: #fafafa;
  border-radius: 6px;
}

.perm-line {
  margin: 6px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.perm-time {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
