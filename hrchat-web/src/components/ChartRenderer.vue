<template>
  <div class="chart-renderer">
    <!-- NUMBER_CARD：无 echarts，渲染居中数字卡片 -->
    <div
      v-if="chartType === 'NUMBER_CARD'"
      class="number-card"
      :style="{ height: `${height}px` }"
    >
      <div class="number-card-value">{{ numberValue }}</div>
      <div v-if="numberName" class="number-card-name">{{ numberName }}</div>
    </div>

    <!-- BAR / LINE / PIE：复用 EChart 容器与尺寸约定 -->
    <EChart
      v-else-if="hasData"
      :option="chartOption"
      :height="height"
      :on-events="barChartEvents"
    />

    <div v-else class="chart-empty">暂无数据</div>
  </div>
</template>

<script lang="ts">
export type ChartType = 'BAR' | 'LINE' | 'PIE' | 'NUMBER_CARD'

export interface ChartSeriesData {
  name: string
  data: number[]
}

export interface ChartPieDatum {
  name: string
  value: number
}
</script>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'

/** 项目主色板（ant-design-vue 默认蓝为主色） */
const PALETTE = ['#1677ff', '#52c41a', '#faad14', '#f5222d', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16']

const props = withDefaults(
  defineProps<{
    chartType: ChartType
    /** BAR/LINE：类目轴 */
    categories?: string[]
    /** BAR/LINE：系列（后端 ChartSeries） */
    series?: ChartSeriesData[]
    /** PIE：分片（后端 ChartPieDatum） */
    pieData?: ChartPieDatum[]
    /** 可选标题 */
    title?: string
    /** 容器高度（px），默认 320 */
    height?: number
    /** 是否可点击柱条下钻（组织维度图表） */
    drillable?: boolean
  }>(),
  {
    categories: () => [],
    series: () => [],
    pieData: () => [],
    title: '',
    height: 320,
    drillable: false,
  },
)

const emit = defineEmits<{ (e: 'barClick', category: string): void }>()

/** BAR 图表点击事件：回传类目值供下钻（仅组织维度图表启用） */
const barChartEvents = computed<Record<string, (params: unknown) => void> | undefined>(() => {
  if (props.chartType !== 'BAR' || !props.drillable) return undefined
  return {
    click: (params) => {
      const p = params as { dataIndex?: number; name?: string }
      if (p.name) emit('barClick', String(p.name))
    },
  }
})

/** BAR/LINE/PIE 是否有可渲染数据 */
const hasData = computed(() => {
  if (props.chartType === 'PIE') return props.pieData.length > 0
  if (props.chartType === 'BAR' || props.chartType === 'LINE') {
    return props.series.length > 0 && props.categories.length > 0
  }
  return false
})

/** NUMBER_CARD：取首个系列首个数据点（或首个分片）作为数值 */
const numberValue = computed(() => {
  const s = props.series[0]
  if (s && s.data.length) return String(s.data[0])
  const p = props.pieData[0]
  if (p) return String(p.value)
  return '—'
})

const numberName = computed(() => props.series[0]?.name ?? props.pieData[0]?.name ?? props.title)

/** 归一化后的 echarts option */
const chartOption = computed<Record<string, unknown>>(() => {
  switch (props.chartType) {
    case 'PIE':
      return pieOption()
    case 'BAR':
    case 'LINE':
      return cartesianOption()
    default:
      return {}
  }
})

function baseTitle() {
  return props.title
    ? {
        text: props.title,
        left: 'center',
        textStyle: { fontSize: 14, color: 'rgba(0, 0, 0, 0.85)' },
      }
    : undefined
}

function cartesianOption(): Record<string, unknown> {
  const isBar = props.chartType === 'BAR'
  const many = props.categories.length > 12
  return {
    color: PALETTE,
    title: baseTitle(),
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 48, right: 16, top: props.title ? 44 : 24, bottom: many ? 64 : 28 },
    xAxis: {
      type: 'category',
      data: props.categories,
      axisLabel: { interval: 0, rotate: props.categories.length > 8 ? 30 : 0 },
    },
    yAxis: { type: 'value' },
    dataZoom: many ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 0 }] : undefined,
    series: props.series.map((s) => ({
      name: s.name,
      type: isBar ? 'bar' : 'line',
      data: s.data,
      smooth: !isBar,
      barMaxWidth: 32,
    })),
  }
}

function pieOption(): Record<string, unknown> {
  return {
    color: PALETTE,
    title: baseTitle(),
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, orient: 'horizontal' },
    series: [
      {
        name: props.title || '占比',
        type: 'pie',
        radius: '60%',
        center: ['50%', props.title ? '46%' : '52%'],
        label: { show: true, formatter: '{b}: {c}' },
        data: props.pieData.map((d) => ({ name: d.name, value: d.value })),
      },
    ],
  }
}

defineExpose({ chartOption, hasData, numberValue, numberName })
</script>

<style scoped>
.chart-renderer {
  width: 100%;
}

.number-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #fafafa;
  border-radius: 8px;
}

.number-card-value {
  font-size: 48px;
  font-weight: 600;
  line-height: 1;
  color: #1677ff;
}

.number-card-name {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
}

.chart-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.35);
}
</style>
