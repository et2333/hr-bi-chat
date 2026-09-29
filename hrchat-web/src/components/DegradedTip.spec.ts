import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import DegradedTip from './DegradedTip.vue'

const mountOpts = { global: { plugins: [Antd] } }

describe('components/DegradedTip', () => {
  it('渲染降级提示文案', () => {
    const wrapper = mount(DegradedTip, { props: { message: 'AI 服务暂不可用，已使用模板直查' }, ...mountOpts })
    expect(wrapper.text()).toContain('AI 服务暂不可用')
  })

  it('无 message 时不报错', () => {
    const wrapper = mount(DegradedTip, { props: {}, ...mountOpts })
    expect(wrapper.exists()).toBe(true)
  })
})
