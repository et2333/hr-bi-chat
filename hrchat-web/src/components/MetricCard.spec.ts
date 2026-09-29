import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import MetricCard from './MetricCard.vue'
import type { Conclusion } from '@/api/types'

const NUMBER_CARD: Conclusion = {
  type: 'NUMBER_CARD',
  value: 128,
  unit: '人',
  compare: { period: '上月', value: 120, direction: 'UP' },
}

const mountOpts = { global: { plugins: [Antd] } }

describe('components/MetricCard', () => {
  it('渲染数值、单位与环比', () => {
    const wrapper = mount(MetricCard, {
      props: { conclusion: NUMBER_CARD, caliber: null },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('128')
    expect(wrapper.text()).toContain('人')
    expect(wrapper.text()).toContain('较上月 120')
  })

  it('上行环比标记 UP', () => {
    const wrapper = mount(MetricCard, { props: { conclusion: NUMBER_CARD, caliber: null }, ...mountOpts })
    expect(wrapper.find('.direction.up').exists()).toBe(true)
  })

  it('展示口径信息', () => {
    const wrapper = mount(MetricCard, {
      props: {
        conclusion: NUMBER_CARD,
        caliber: { metric: 'headcount', definition: '期末在职人数', dataUpdatedAt: '2026-09-27T00:00:00' },
      },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('headcount')
    expect(wrapper.text()).toContain('数据更新于')
  })
})
