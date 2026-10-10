<template>
  <p v-if="usage" class="query-usage">
    模型调用 {{ usage.call_count }} 次 · 已记录 Token {{ usage.actual_usage_known_sum.total_tokens ?? '未知' }}
    <template v-if="usage.unknown_cost_count || !usage.call_count"> · 估算费用未知</template>
    <template v-else> · 估算费用 {{ Object.entries(usage.known_cost_by_currency).map(([currency, value]) => `${value} ${currency}`).join(' / ') }}</template>
  </p>
</template>
<script setup lang="ts">
import type { QueryUsage } from '@/api/types'
defineProps<{ usage?: QueryUsage | null }>()
</script>
<style scoped>.query-usage { color: #657386; font-size: 12px; margin: 8px 0; }</style>
