import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import AnswerCard from './AnswerCard.vue'
import type { AnswerPayload, ClarifyQuestions } from '@/api/types'

// jsdom 无 canvas 实现：mock echarts，仅验证图表区是否按类型挂载
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), off: vi.fn(), on: vi.fn() })),
}))

const mountOpts = { global: { plugins: [Antd] } }

const COMPLETED: AnswerPayload = {
  askId: 'ask_1',
  answerId: 'ans_1',
  status: 'COMPLETED',
  intent: 'QUERY',
  degraded: false,
  conclusion: { type: 'NUMBER_CARD', value: 128, unit: '人', compare: { period: '上月', value: 120, direction: 'UP' } },
  table: { columns: [{ key: 'headcount', name: '在职人数', type: 'number', masked: false }], rows: [{ headcount: 128 }], total: 1, page: 1, size: 20 },
  chart: null,
  caliber: { metric: 'headcount', definition: '期末在职人数', dataUpdatedAt: '2026-09-27T00:00:00' },
  followups: ['查看明细'],
  elapsedMs: 120,
}

describe('components/AnswerCard', () => {
  it('完成态渲染三段式：结论/数据/口径/追问/反馈', () => {
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload: COMPLETED, canViewSql: true }, ...mountOpts })
    expect(wrapper.text()).toContain('128')
    expect(wrapper.text()).toContain('人')
    expect(wrapper.text()).toContain('数据明细')
    expect(wrapper.text()).toContain('在职人数')
    expect(wrapper.text()).toContain('期末在职人数')
    expect(wrapper.text()).toContain('查看明细')
    expect(wrapper.text()).toContain('耗时 120ms')
  })

  it('点赞反馈 emit(feedback, UP)', async () => {
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload: COMPLETED }, ...mountOpts })
    await wrapper.find('.feedback-bar button').trigger('click')
    expect(wrapper.emitted('feedback')?.[0]).toEqual(['UP'])
  })

  it('完成态展示「存为报表」并 emit(saveReport)', async () => {
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload: COMPLETED }, ...mountOpts })
    expect(wrapper.text()).toContain('存为报表')
    const saveBtn = wrapper.findAllComponents({ name: 'AButton' }).find((b) => b.text().includes('存为报表'))
    expect(saveBtn).toBeTruthy()
    expect(saveBtn!.props('disabled')).toBeFalsy()
    await saveBtn!.trigger('click')
    expect(wrapper.emitted('saveReport')).toBeTruthy()
  })

  it('降级提示条（degraded=true）', () => {
    const degraded: AnswerPayload = { ...COMPLETED, degraded: true, degradedTip: 'AI 服务暂不可用，已使用模板直查' }
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload: degraded }, ...mountOpts })
    expect(wrapper.text()).toContain('AI 服务暂不可用')
  })

  it('越权态 forbidden → 返回动作', () => {
    const wrapper = mount(AnswerCard, { props: { state: 'forbidden' }, ...mountOpts })
    // antd 中文按钮字间含空格（"返 回"），归一化后断言
    expect(wrapper.text().replace(/\s/g, '')).toContain('返回')
  })

  it('失败态显示错误标题与描述，重试动作 emit(retry)', async () => {
    const wrapper = mount(AnswerCard, {
      props: { state: 'failed', errorTitle: '解析失败', errorMessage: '未能理解您的问句' },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('解析失败')
    expect(wrapper.text()).toContain('未能理解您的问句')
    await wrapper.find('.state-empty button, button').trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })

  it('澄清态渲染 ClarifyCard', () => {
    const clarify: ClarifyQuestions = {
      interruptType: 'CLARIFY',
      askId: 'ask_2',
      questions: [{ questionId: 'q1', question: '您指的是哪个指标？', options: [{ optionId: 'hire_count', label: '入职人数' }], multiple: false }],
    }
    const wrapper = mount(AnswerCard, { props: { state: 'clarifying', clarify }, ...mountOpts })
    expect(wrapper.text()).toContain('您指的是哪个指标？')
  })

  it('NUMBER_CARD（无真实图表）不渲染图表区', () => {
    const payload: AnswerPayload = {
      ...COMPLETED,
      chart: { type: 'NUMBER_CARD', recommended: true, config: { value: '128', unit: '人' } },
    }
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload }, ...mountOpts })
    expect(wrapper.find('.chart-area').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('图表')
  })

  it('LINE 折线图渲染图表区', () => {
    const payload: AnswerPayload = {
      ...COMPLETED,
      chart: {
        type: 'LINE',
        recommended: true,
        config: {
          xAxis: { type: 'category', data: ['2026-07', '2026-08'] },
          yAxis: { type: 'value' },
          series: [{ type: 'line', data: [2, 3] }],
        },
      },
    }
    const wrapper = mount(AnswerCard, { props: { state: 'completed', payload }, ...mountOpts })
    expect(wrapper.find('.chart-area').exists()).toBe(true)
  })

  it('streaming 阶段先显示进度提示，正文逐字打字并带光标', async () => {
    const wrapper = mount(AnswerCard, {
      props: { state: 'streaming', hint: '正在解析您的问句…' },
      ...mountOpts,
    })
    expect(wrapper.find('.streaming-hint').text()).toContain('正在解析')
    expect(wrapper.find('.type-cursor').exists()).toBe(false)

    await wrapper.setProps({ hint: '正在生成智能解读…', streamingText: '近三月离职率环比上升' })
    // 打字机首拍（28ms）后出现首字，且打字中显示光标
    await new Promise((r) => setTimeout(r, 40))
    expect(wrapper.text()).toContain('近')
    expect(wrapper.find('.type-cursor').exists()).toBe(true)

    // 全部揭示完毕后光标消失，并通知外层打字完成
    await new Promise((r) => setTimeout(r, 400))
    expect(wrapper.text()).toContain('近三月离职率环比上升')
    expect(wrapper.find('.type-cursor').exists()).toBe(false)
    expect(wrapper.emitted('typingDone')).toBeTruthy()
  })

  it('正文打字未完成时不触发 typingDone，打完才触发', async () => {
    const wrapper = mount(AnswerCard, {
      props: { state: 'streaming', streamingText: '近三月离职率环比下降明显' },
      ...mountOpts,
    })
    await new Promise((r) => setTimeout(r, 40))
    expect(wrapper.emitted('typingDone')).toBeFalsy()

    await new Promise((r) => setTimeout(r, 500))
    expect(wrapper.emitted('typingDone')).toBeTruthy()
  })
})
