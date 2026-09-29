import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import ClarifyCard from './ClarifyCard.vue'
import type { ClarifyQuestions } from '@/api/types'

const mountOpts = { global: { plugins: [Antd] } }

const CLARIFY: ClarifyQuestions = {
  interruptType: 'INTERRUPT',
  askId: 'ask_1',
  questions: [
    {
      questionId: 'q1',
      question: '需要统计哪个组织？',
      multiple: false,
      options: [
        { optionId: 'RD', label: '研发中心' },
        { optionId: 'SALES', label: '销售部' },
      ],
    },
    {
      questionId: 'q2',
      question: '统计时间范围？',
      multiple: true,
      options: [
        { optionId: 'M1', label: '近一个月' },
        { optionId: 'Q1', label: '本季度' },
      ],
    },
  ],
}

describe('components/ClarifyCard', () => {
  it('渲染澄清问题与选项', () => {
    const wrapper = mount(ClarifyCard, { props: { clarify: CLARIFY }, ...mountOpts })
    expect(wrapper.text()).toContain('需要统计哪个组织？')
    expect(wrapper.text()).toContain('研发中心')
    expect(wrapper.text()).toContain('统计时间范围？')
  })

  it('未全部作答时禁用提交', async () => {
    const wrapper = mount(ClarifyCard, { props: { clarify: CLARIFY }, ...mountOpts })
    const button = wrapper.find('button[type=button]')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('作答后提交对应 answers', async () => {
    const wrapper = mount(ClarifyCard, { props: { clarify: CLARIFY }, ...mountOpts })
    // 单选：选中研发中心
    const radios = wrapper.findAll('input[type="radio"]')
    await radios[0].setValue()
    // 多选：选中近一个月
    const boxes = wrapper.findAll('input[type="checkbox"]')
    await boxes[0].setValue()
    await wrapper.find('button[type=button]').trigger('click')
    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const answers = emitted![0][0] as Array<{ questionId: string; optionIds: string[] }>
    expect(answers).toContainEqual({ questionId: 'q1', optionIds: ['RD'] })
  })
})
