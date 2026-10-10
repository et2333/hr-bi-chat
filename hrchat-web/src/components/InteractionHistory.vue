<template>
  <details v-if="visible.length" class="interaction-history" open>
    <summary>条件确认记录</summary>
    <div v-for="entry in visible" :key="entry.sequence" class="interaction-entry">
      <template v-if="entry.kind === 'clarification'">
        <strong>系统询问：</strong>{{ entry.question }}
        <details v-if="entry.options?.length"><summary>查看当时选项</summary>{{ entry.options.map(o => o.label).join(' / ') }}</details>
      </template>
      <template v-else-if="entry.kind === 'selection'"><strong>你的选择：</strong>{{ entry.selected }}</template>
      <template v-else>{{ entry.message }}</template>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { InteractionEntry } from '@/api/types'
const props = defineProps<{ entries?: InteractionEntry[] }>()
const visible = computed(() => props.entries?.some(e => e.kind === 'clarification')
  ? props.entries.filter(e => e.kind !== 'request') : [])
</script>

<style scoped>
.interaction-history { padding: 10px 14px; margin-bottom: 10px; background: #f5f7fa; border-radius: 8px; }
.interaction-entry { padding: 5px 0; white-space: pre-wrap; }
summary { cursor: pointer; color: #526077; }
</style>
