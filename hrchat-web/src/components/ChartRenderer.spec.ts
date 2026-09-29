import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ChartRenderer from './ChartRenderer.vue'

// happy-dom 无 canvas 实现：mock echarts，仅验证容器渲染与 option 归一化
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

interface ChartOption {
  xAxis?: { data: string[] }
  series: Array<Record<string, unknown>>
  tooltip?: { trigger: string }
  legend?: unknown
  title?: { text: string }
  dataZoom?: unknown[]
}

function optionOf(wrapper: VueWrapper): ChartOption {
  return (wrapper.vm as unknown as { chartOption: ChartOption }).chartOption
}

describe('components/ChartRenderer', () => {
  it('BAR：渲染 echarts 容器，option 归一化为柱状图', () => {
    const wrapper = mount(ChartRenderer, {
      props: {
        chartType: 'BAR',
        title: '月度在职人数',
        categories: ['1月', '2月', '3月'],
        series: [{ name: '在职人数', data: [100, 120, 130] }],
      },
    })
    expect(wrapper.find('.echart-container').exists()).toBe(true)
    const opt = optionOf(wrapper)
    expect(opt.xAxis?.data).toEqual(['1月', '2月', '3月'])
    expect(opt.series[0].type).toBe('bar')
    expect(opt.series[0].data).toEqual([100, 120, 130])
    expect(opt.tooltip?.trigger).toBe('axis')
    expect(opt.legend).toBeTruthy()
    expect(opt.title?.text).toBe('月度在职人数')
  })

  it('LINE：option 归一化为折线图（smooth）', () => {
    const wrapper = mount(ChartRenderer, {
      props: {
        chartType: 'LINE',
        categories: ['a', 'b'],
        series: [{ name: '离职率', data: [0.1, 0.08] }],
      },
    })
    expect(wrapper.find('.echart-container').exists()).toBe(true)
    const opt = optionOf(wrapper)
    expect(opt.series[0].type).toBe('line')
    expect(opt.series[0].smooth).toBe(true)
  })

  it('数据量多时自动加 dataZoom', () => {
    const categories = Array.from({ length: 15 }, (_, i) => `d${i}`)
    const wrapper = mount(ChartRenderer, {
      props: { chartType: 'BAR', categories, series: [{ name: 's', data: categories.map((_, i) => i) }] },
    })
    expect(optionOf(wrapper).dataZoom).toBeTruthy()
  })

  it('PIE：给定 pieData 归一化为饼图（label show）', () => {
    const wrapper = mount(ChartRenderer, {
      props: {
        chartType: 'PIE',
        pieData: [
          { name: '研发', value: 60 },
          { name: '销售', value: 40 },
        ],
      },
    })
    expect(wrapper.find('.echart-container').exists()).toBe(true)
    const opt = optionOf(wrapper)
    expect(opt.series[0].type).toBe('pie')
    expect(opt.series[0].data).toEqual([
      { name: '研发', value: 60 },
      { name: '销售', value: 40 },
    ])
    expect((opt.series[0].label as { show: boolean }).show).toBe(true)
  })

  it('NUMBER_CARD：无 echarts，渲染居中数字与名称', () => {
    const wrapper = mount(ChartRenderer, {
      props: { chartType: 'NUMBER_CARD', series: [{ name: '在职人数', data: [128] }] },
    })
    expect(wrapper.find('.echart-container').exists()).toBe(false)
    expect(wrapper.find('.number-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('128')
    expect(wrapper.text()).toContain('在职人数')
  })

  it('无数据时展示空态', () => {
    const wrapper = mount(ChartRenderer, { props: { chartType: 'BAR', categories: [], series: [] } })
    expect(wrapper.find('.chart-empty').exists()).toBe(true)
  })
})
