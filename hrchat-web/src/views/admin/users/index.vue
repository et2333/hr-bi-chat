<template>
  <div class="admin-page">
    <!-- 用户列表 -->
    <a-card :bordered="false" title="用户管理" class="pane-card">
      <div class="filter-row">
        <a-input
          v-model:value="keyword"
          placeholder="关键词：工号/姓名"
          allow-clear
          class="filter-item"
          @pressEnter="onSearch"
        />
        <a-button size="small" @click="onSearch">查询</a-button>
        <a-button type="primary" size="small" @click="openCreate">新建用户</a-button>
      </div>
      <a-table
        :columns="columns"
        :data-source="users"
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
          <a-empty description="暂无用户" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'roles'">
            <template v-if="record.roles.length">
              <a-tag v-for="r in record.roles" :key="r" color="blue" class="role-tag">{{ r }}</a-tag>
            </template>
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'mustChangePwd'">
            <a-tag v-if="record.mustChangePwd === 1" color="orange">需改密</a-tag>
            <a-tag v-else color="default">无需</a-tag>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ record.createdAt ? formatDateTime(record.createdAt) : '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button size="small" type="link" @click="openAssignRoles(record)">分配角色</a-button>
            <a-popconfirm title="确定重置该用户密码？" @confirm="resetPassword(record)">
              <a-button size="small" type="link">重置密码</a-button>
            </a-popconfirm>
            <a-popconfirm
              :title="record.status === 1 ? '确定停用该用户？' : '确定启用该用户？'"
              @confirm="toggleStatus(record)"
            >
              <a-button size="small" type="link">{{ record.status === 1 ? '停用' : '启用' }}</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建用户 Modal -->
    <a-modal
      v-model:open="modalOpen"
      title="新建用户"
      :confirm-loading="saving"
      @ok="submitModal"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="工号" name="empNo">
          <a-input v-model:value="form.empNo" placeholder="如 emp001" />
        </a-form-item>
        <a-form-item label="姓名" name="displayName">
          <a-input v-model:value="form.displayName" placeholder="如 张三" />
        </a-form-item>
        <a-form-item label="邮箱" name="email">
          <a-input v-model:value="form.email" placeholder="如 zhangsan@example.com" />
        </a-form-item>
        <a-form-item label="所属组织" name="orgNodeId">
          <a-select v-model:value="form.orgNodeId" placeholder="选择组织" allow-clear>
            <a-select-option v-for="o in ORG_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="角色" name="roleCodes">
          <a-select v-model:value="form.roleCodes" placeholder="选择角色（可多选）" mode="multiple">
            <a-select-option v-for="r in roleOptions" :key="r.roleCode" :value="r.roleCode">{{ r.roleName }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 分配角色 Modal -->
    <a-modal
      v-model:open="assignOpen"
      title="分配角色"
      :confirm-loading="assignSaving"
      @ok="submitAssignRoles"
    >
      <p class="assign-tip">用户：{{ assigningUser ? `${assigningUser.displayName}（${assigningUser.empNo}）` : '' }}</p>
      <a-select v-model:value="assignRoleCodes" placeholder="选择角色（可多选）" mode="multiple" style="width: 100%">
        <a-select-option v-for="r in roleOptions" :key="r.roleCode" :value="r.roleCode">{{ r.roleName }}</a-select-option>
      </a-select>
    </a-modal>

    <!-- 初始密码展示 Modal（initialPassword 仅一次返回，只展示一次） -->
    <a-modal
      v-model:open="pwdModalOpen"
      :title="pwdResult?.title ?? '提示'"
      :footer="null"
    >
      <a-alert
        type="warning"
        show-icon
        :message="`初始密码：${pwdResult?.initialPassword ?? '-'}`"
        description="该密码仅本次返回，请立即告知用户并妥善保存，关闭后不再显示。"
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { adminApi, userApi } from '@/api'
import type { RoleView } from '@/api'
import type { UserCreateRequest, UserView } from '@/api/users'

// 组织下拉来源说明：后端暂无独立组织列表接口（sec_org_node 仅作镜像），
// 此处按种子数据提供固定示例组织，value 对应 sec_org_node.id。
const ORG_OPTIONS = [
  { value: 2, label: '研发中心' },
  { value: 3, label: '研发一部' },
  { value: 4, label: '研发二部' },
  { value: 5, label: '销售部' },
  { value: 6, label: '职能部' },
  { value: 7, label: '人力部' },
  { value: 8, label: '财务部' },
]

// ---------------- 列表 ----------------
const users = ref<UserView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)

const columns = [
  { title: '工号', key: 'empNo', dataIndex: 'empNo', width: 90 },
  { title: '姓名', key: 'displayName', dataIndex: 'displayName', width: 110 },
  { title: '邮箱', key: 'email', dataIndex: 'email' },
  { title: '组织', key: 'orgName', dataIndex: 'orgName', width: 110 },
  { title: '角色', key: 'roles', width: 160 },
  { title: '状态', key: 'status', width: 80 },
  { title: '租户', key: 'tenantId', dataIndex: 'tenantId', width: 80 },
  { title: '是否需改密', key: 'mustChangePwd', width: 100 },
  { title: '创建时间', key: 'createdAt', width: 160 },
  { title: '操作', key: 'actions', width: 220 },
]

// ---------------- 角色选项（复用既有角色接口 /admin/authz/roles） ----------------
const roleOptions = ref<RoleView[]>([])

async function loadRoles() {
  try {
    const res = await adminApi.listRoles(1, 100)
    roleOptions.value = res.data.records
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载角色失败')
  }
}

// ---------------- 新建用户 ----------------
interface UserForm {
  empNo: string
  displayName: string
  email: string
  orgNodeId: number | undefined
  roleCodes: string[]
}

const modalOpen = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<UserForm>({
  empNo: '',
  displayName: '',
  email: '',
  orgNodeId: undefined,
  roleCodes: [],
})

const rules = {
  empNo: [{ required: true, message: '请填写工号', trigger: 'blur' }],
  displayName: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  email: [{ required: true, message: '请填写邮箱', trigger: 'blur' }],
}

function openCreate() {
  Object.assign(form, { empNo: '', displayName: '', email: '', orgNodeId: undefined, roleCodes: [] })
  formRef.value?.clearValidate()
  modalOpen.value = true
}

async function submitModal() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload: UserCreateRequest = {
    empNo: form.empNo.trim(),
    displayName: form.displayName.trim(),
    email: form.email.trim(),
    orgNodeId: form.orgNodeId ?? null,
    roleCodes: form.roleCodes,
  }
  saving.value = true
  try {
    const res = await userApi.createUser(payload)
    showPasswordResult('用户创建成功', res.data.initialPassword)
    message.success(`用户已创建，初始密码：${res.data.initialPassword}`)
    modalOpen.value = false
    await loadUsers()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 分配角色 ----------------
const assignOpen = ref(false)
const assignSaving = ref(false)
const assigningUser = ref<UserView | null>(null)
const assignRoleCodes = ref<string[]>([])

function openAssignRoles(record: UserView) {
  assigningUser.value = record
  assignRoleCodes.value = [...record.roles]
  assignOpen.value = true
}

async function submitAssignRoles() {
  if (!assigningUser.value) return
  assignSaving.value = true
  try {
    await userApi.assignUserRoles(assigningUser.value.id, assignRoleCodes.value)
    message.success('角色已更新')
    assignOpen.value = false
    await loadUsers()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '分配角色失败')
  } finally {
    assignSaving.value = false
  }
}

// ---------------- 重置密码 / 启用停用 ----------------
const pwdModalOpen = ref(false)
const pwdResult = ref<{ title: string; initialPassword: string } | null>(null)

function showPasswordResult(title: string, initialPassword: string) {
  pwdResult.value = { title, initialPassword }
  pwdModalOpen.value = true
}

async function resetPassword(record: UserView) {
  try {
    const res = await userApi.resetUserPassword(record.id)
    showPasswordResult('密码已重置', res.data.initialPassword)
    message.success(`新初始密码：${res.data.initialPassword}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重置密码失败')
  }
}

async function toggleStatus(record: UserView) {
  const next = record.status === 1 ? 0 : 1
  try {
    await userApi.patchUserStatus(record.id, next)
    message.success(next === 1 ? '用户已启用' : '用户已停用')
    await loadUsers()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

// ---------------- 加载 ----------------
async function loadUsers() {
  listLoading.value = true
  try {
    const res = await userApi.listUsers(page.value, size.value, keyword.value.trim())
    users.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载用户列表失败')
  } finally {
    listLoading.value = false
  }
}

function onSearch() {
  page.value = 1
  loadUsers()
}

function onPageChange(p: number) {
  page.value = p
  loadUsers()
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  loadUsers()
  loadRoles()
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

.role-tag {
  margin-bottom: 2px;
}

.assign-tip {
  margin-bottom: 12px;
  color: rgba(0, 0, 0, 0.65);
}
</style>
