import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import StateEmpty from './StateEmpty.vue'

const mountOpts = { global: { plugins: [Antd] } }

describe('components/StateEmpty', () => {
  it('默认空态文案', () => {
    const wrapper = mount(StateEmpty, { props: { state: 'empty' }, ...mountOpts })
    expect(wrapper.text()).toContain('暂无数据')
  })

  it('越权态展示权限文案', () => {
    const wrapper = mount(StateEmpty, { props: { state: 'forbidden' }, ...mountOpts })
    expect(wrapper.text()).toContain('无访问权限')
  })

  it('自定义标题与操作按钮触发 action', async () => {
    const wrapper = mount(
      StateEmpty,
      { props: { state: 'failed', title: '自定义错误', actionText: '重试' }, ...mountOpts },
    )
    expect(wrapper.text()).toContain('自定义错误')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('action')).toBeTruthy()
  })
})
