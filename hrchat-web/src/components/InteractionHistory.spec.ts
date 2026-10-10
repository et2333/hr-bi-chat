import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import InteractionHistory from './InteractionHistory.vue'

it('replays multiple questions, actual choices and failures without actionable old buttons', () => {
  const wrapper = mount(InteractionHistory, { props: { entries: [
    { sequence: 1, at: '', kind: 'clarification', question: '哪个指标？', options: [{ optionId: 'leave_count', label: '离职人数' }] },
    { sequence: 2, at: '', kind: 'selection', selected: '离职人数' },
    { sequence: 3, at: '', kind: 'clarification', question: '哪个期间？' },
    { sequence: 4, at: '', kind: 'selection', selected: '上月' },
    { sequence: 5, at: '', kind: 'outcome', status: 'failed', message: '确认后处理失败，可重新提问' },
  ] } })
  expect(wrapper.text()).toContain('哪个指标？')
  expect(wrapper.text()).toContain('你的选择：离职人数')
  expect(wrapper.text()).toContain('哪个期间？')
  expect(wrapper.text()).toContain('你的选择：上月')
  expect(wrapper.text()).toContain('确认后处理失败')
  expect(wrapper.findAll('button')).toHaveLength(0)
})

it('does not invent historical choices when none was recorded', () => {
  const wrapper = mount(InteractionHistory, { props: { entries: [] } })
  expect(wrapper.text()).toBe('')
})
