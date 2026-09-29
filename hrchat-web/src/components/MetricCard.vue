<template>
  <div class="metric-card">
    <div class="metric-value">
      <span class="metric-number">{{ displayValue }}</span>
      <span v-if="conclusion.unit" class="metric-unit">{{ conclusion.unit }}</span>
    </div>
    <div v-if="conclusion.compare" class="metric-compare">
      <span :class="['direction', directionClass]">
        {{ conclusion.compare.direction === 'UP' ? '↑' : conclusion.compare.direction === 'DOWN' ? '↓' : '→' }}
      </span>
      <span class="compare-text">
        较{{ conclusion.compare.period }} {{ formatValue(conclusion.compare.value) }}
      </span>
    </div>
    <div v-if="caliber" class="metric-caliber" :title="caliber.definition">
      口径：{{ caliber.metric }}
      <span v-if="caliber.dataUpdatedAt" class="caliber-time">
        数据更新于 {{ formatTime(caliber.dataUpdatedAt) }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Caliber, Conclusion } from '@/api/types'

const props = defineProps<{
  conclusion: Conclusion
  caliber?: Caliber | null
}>()

function formatValue(value: unknown): string {
  if (typeof value === 'number') return value.toLocaleString()
  if (value === null || value === undefined) return '-'
  return String(value)
}

const displayValue = computed(() => formatValue(props.conclusion.value))
const directionClass = computed(() => {
  const d = props.conclusion.compare?.direction
  if (d === 'UP') return 'up'
  if (d === 'DOWN') return 'down'
  return 'flat'
})

function formatTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.metric-card {
  padding: 4px 0;
}

.metric-value {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.metric-number {
  font-size: 40px;
  font-weight: 700;
  color: #1677ff;
  line-height: 1.2;
}

.metric-unit {
  font-size: 16px;
  color: rgba(0, 0, 0, 0.65);
}

.metric-compare {
  margin-top: 4px;
  font-size: 13px;
}

.direction {
  display: inline-block;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  text-align: center;
  line-height: 20px;
  margin-right: 6px;
  color: #fff;
  font-weight: 600;
}

.direction.up {
  background: #f5222d;
}

.direction.down {
  background: #52c41a;
}

.direction.flat {
  background: #8c8c8c;
}

.compare-text {
  color: rgba(0, 0, 0, 0.75);
}

.metric-caliber {
  margin-top: 8px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.caliber-time {
  margin-left: 12px;
}
</style>
