<template>
  <div class="profile-page">
    <a-card :bordered="false" class="profile-card">
      <div class="profile-head">
        <a-avatar :size="64" class="profile-avatar">{{ auth.currentUser.name.slice(0, 1) }}</a-avatar>
        <div>
          <h2 class="profile-name">{{ auth.currentUser.name }}</h2>
          <p class="profile-meta">
            工号：{{ auth.currentUser.empNo }} · 角色：{{ auth.currentUser.role }}
          </p>
        </div>
      </div>
    </a-card>

    <a-card :bordered="false" title="生效权限" class="profile-card">
      <a-skeleton v-if="loading" active :paragraph="{ rows: 4 }" />
      <template v-else>
        <template v-if="perms">
          <p class="perm-line">
            角色：
            <a-tag v-for="r in perms.roles" :key="r" color="blue">{{ r }}</a-tag>
          </p>
          <p class="perm-line">
            数据范围：
            <a-tag v-for="d in perms.dataScopes" :key="d.orgNodeId" color="green">
              {{ d.orgName }}（scope={{ d.scope }}）
            </a-tag>
            <span v-if="perms.dataScopes.length === 0" class="perm-none">无</span>
          </p>
          <p class="perm-line">
            字段策略：
            <a-tag v-for="f in perms.fieldPolicies" :key="f" color="orange">{{ f }}</a-tag>
            <span v-if="perms.fieldPolicies.length === 0" class="perm-none">无</span>
          </p>
          <p class="perm-line">
            功能权限：
            <a-tag v-for="p in perms.functionPerms" :key="p" color="geekblue">{{ p }}</a-tag>
          </p>
        </template>
        <a-alert v-else type="warning" show-icon message="无法加载权限信息，请确认身份切换后刷新" />
      </template>
    </a-card>

    <a-card :bordered="false" title="身份切换（本地演示模式）" class="profile-card">
      <a-select v-model:value="selectedUser" style="width: 260px" @change="switchUser">
        <a-select-option v-for="u in auth.MOCK_USERS" :key="u.empNo" :value="u.empNo">
          {{ u.name }}（{{ u.empNo }}）· {{ u.role }}
        </a-select-option>
      </a-select>
      <p class="switch-tip">切换身份后将在全站生效（X-User-No 请求头）。</p>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '@/api/http'
import type { ApiResponse, EffectivePermissions } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const selectedUser = ref(auth.empNo)
const perms = ref<EffectivePermissions | null>(null)
const loading = ref(true)

onMounted(loadPerms)

async function loadPerms() {
  loading.value = true
  perms.value = null

  try {
    const res = await get<ApiResponse<EffectivePermissions>>('/me/permissions')
    perms.value = res.data
  } catch {
    perms.value = null
  } finally {
    loading.value = false
  }
}

function switchUser(empNo: string) {
  auth.switchUser(empNo)
  loadPerms()
}
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 720px;
  margin: 0 auto;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-avatar {
  background: #1677ff;
  color: #fff;
  font-size: 28px;
  flex-shrink: 0;
}

.profile-name {
  margin: 0;
}

.profile-meta {
  margin: 6px 0 0;
  color: rgba(0, 0, 0, 0.55);
}

.perm-line {
  margin: 10px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 14px;
}

.perm-none {
  color: rgba(0, 0, 0, 0.35);
}

.switch-tip {
  margin-top: 12px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
