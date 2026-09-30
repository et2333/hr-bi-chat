<template>
  <div class="admin-page">
    <a-alert
      class="semantic-tip"
      type="info"
      show-icon
      message="语义层是问数口径的唯一来源：指标新增保存即生效；口径/公式变更需经审批通过后发布"
    />
    <a-card :bordered="false" class="pane-card">
      <a-tabs v-model:activeKey="activeTab" @change="onTabChange">
        <a-tab-pane key="metric">
          <template #tab>
            <span><FundOutlined />指标管理</span>
          </template>
          <MetricPanel v-if="activeTab === 'metric' || loaded.has('metric')" />
        </a-tab-pane>
        <a-tab-pane key="dimension">
          <template #tab>
            <span><ApartmentOutlined />维度管理</span>
          </template>
          <DimensionPanel v-if="activeTab === 'dimension' || loaded.has('dimension')" />
        </a-tab-pane>
        <a-tab-pane v-if="canApprove" key="approval">
          <template #tab>
            <a-badge :count="todoCount" :offset="[8, -2]">
              <span><AuditOutlined />审批待办</span>
            </a-badge>
          </template>
          <ApprovalTodoPanel
            v-if="activeTab === 'approval' || loaded.has('approval')"
            @update:count="todoCount = $event"
          />
        </a-tab-pane>
        <a-tab-pane key="synonym">
          <template #tab>
            <span><SwapOutlined />同义词管理</span>
          </template>
          <SynonymPanel v-if="activeTab === 'synonym' || loaded.has('synonym')" />
        </a-tab-pane>
      </a-tabs>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApartmentOutlined, AuditOutlined, FundOutlined, SwapOutlined } from '@ant-design/icons-vue'
import MetricPanel from './MetricPanel.vue'
import DimensionPanel from './DimensionPanel.vue'
import ApprovalTodoPanel from './ApprovalTodoPanel.vue'
import SynonymPanel from './SynonymPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { hasPerm } from '@/layouts/adminMenu'

const auth = useAuthStore()
const canApprove = computed(() =>
  hasPerm(auth.functionPerms, 'admin:semantic:approve'))

const activeTab = ref('metric')
/** 已访问过的 Tab：切走后保留组件状态（避免来回切换重复加载） */
const loaded = reactive(new Set(['metric']))
/** 审批待办计数（ApprovalTodoPanel 挂载后回报） */
const todoCount = ref(0)

onMounted(() => {
  loaded.add('metric')
  // 有审批权限时预挂载待办面板，使 Tab 徽标即时显示计数
  if (canApprove.value) {
    loaded.add('approval')
  }
})

function onTabChange(key: string) {
  loaded.add(key)
}
</script>

<style scoped>
.semantic-tip {
  margin-bottom: 16px;
}

.pane-card :deep(.ant-card-body) {
  padding-top: 8px;
}
</style>
