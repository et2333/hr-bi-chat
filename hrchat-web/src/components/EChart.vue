<template>
  <div ref="el" class="echart-container" :style="{ height: `${height}px` }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps<{
  option: Record<string, unknown>
  height?: number
  /** echarts 事件绑定：{ click: (params) => void }（下钻等交互） */
  onEvents?: Record<string, (params: unknown) => void>
}>()

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value || !props.option || Object.keys(props.option).length === 0) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(props.option as echarts.EChartsOption, true)
}

function bindEvents() {
  if (!chart || !props.onEvents) return
  for (const [name, handler] of Object.entries(props.onEvents)) {
    chart.off(name)
    chart.on(name, handler)
  }
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  render()
  bindEvents()
  window.addEventListener('resize', resize)
})

watch(() => props.option, render, { deep: true })
watch(() => props.onEvents, bindEvents, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.echart-container {
  width: 100%;
}
</style>
