<template>
  <div class="state-empty">
    <component :is="iconComponent" class="state-icon" />
    <p class="state-title">{{ title }}</p>
    <p v-if="description" class="state-desc">{{ description }}</p>
    <a-button v-if="actionText" type="primary" @click="emit('action')">{{ actionText }}</a-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  CloseCircleOutlined,
  ClockCircleOutlined,
  DisconnectOutlined,
  InboxOutlined,
  SafetyOutlined,
} from '@ant-design/icons-vue'

const props = defineProps<{
  /** empty / forbidden / failed / timeout / offline */
  state: 'empty' | 'forbidden' | 'failed' | 'timeout' | 'offline'
  title?: string
  description?: string
  actionText?: string
}>()

const emit = defineEmits<{ action: [] }>()

const iconComponent = computed(() => {
  switch (props.state) {
    case 'forbidden':
      return SafetyOutlined
    case 'failed':
      return CloseCircleOutlined
    case 'timeout':
      return ClockCircleOutlined
    case 'offline':
      return DisconnectOutlined
    default:
      return InboxOutlined
  }
})

const DEFAULTS: Record<string, { title: string; description: string }> = {
  empty: { title: '暂无数据', description: '当前没有可展示的内容' },
  forbidden: { title: '无访问权限', description: '您暂无查看该内容的权限，请联系管理员' },
  failed: { title: '请求失败', description: '服务暂时不可用，请稍后重试' },
  timeout: { title: '处理超时', description: '请求处理超时，任务已转入后台异步执行' },
  offline: { title: '连接中断', description: '网络连接中断，请检查网络后重试' },
}

const title = computed(() => props.title ?? DEFAULTS[props.state].title)
const description = computed(() => props.description ?? DEFAULTS[props.state].description)
</script>

<style scoped>
.state-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  text-align: center;
}

.state-icon {
  font-size: 48px;
  color: #bfbfbf;
  margin-bottom: 12px;
}

.state-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.state-desc {
  margin: 8px 0 16px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
