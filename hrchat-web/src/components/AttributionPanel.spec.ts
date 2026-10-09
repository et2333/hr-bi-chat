import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import AttributionPanel from './AttributionPanel.vue'
import * as api from '@/api/attribution'
import type { AnalysisContext, AnalysisResult } from '@/api/attribution'

vi.mock('@/api/attribution', () => ({ getAnalysisContext: vi.fn(), startAnalysis: vi.fn(), getAnalysisTask: vi.fn(),
  cancelAnalysis: vi.fn(), streamAnalysis: vi.fn() }))
const context: AnalysisContext = { sourceAskId: 'ask1', metricCode: 'leave_count', metricName: '离职人数', unit: '人', scopeLabel: '研发中心',
  currentPeriod: { start: '2026-09-01', end: '2026-10-01' }, suggestedBaselinePeriod: { start: '2026-08-01', end: '2026-09-01' } }
const result: AnalysisResult = { status: 'PARTIAL', mode: 'dual',
  summary: { current_total: 4, baseline_total: 4, delta: 0, closure_verified: true },
  contributions: [{ org_id: 101, org_name: '研发一部', current_count: 3, baseline_count: 1, delta: 2 },
    { org_id: 102, org_name: '研发二部', current_count: 1, baseline_count: 3, delta: -2 }],
  facts: {}, claims: [{ claim_id: 'h1', kind: 'unverified_hypothesis', hypothesis: 'timing_concentration', evidence_ids: ['ev_department'] }],
  evidence: [{ evidence_id: 'ev_department', status: 'verified', metric_version: '1', data_version: 'snapshot1', query: { detail: 'department' } }],
  data_quality: ['统计贡献不等于真实离职原因。'], unresolved: ['supplement_limit_reached'], disclaimer: '辅助分析，仅供参考', usage: {},
}
const response = <T>(data: T) => ({ code: 'SUCCESS', message: '', traceId: 't', data })
const render = () => mount(AttributionPanel, { props: { sourceAskId: 'ask1' }, global: { plugins: [Antd] } })

beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear()
  vi.mocked(api.getAnalysisContext).mockResolvedValue(response(context))
  vi.mocked(api.startAnalysis).mockResolvedValue(response({ taskId: 'task1', sourceAskId: 'ask1', status: 'PARTIAL',
    context: { ...context, baselinePeriod: context.suggestedBaselinePeriod! }, result, events: [] }))
})

describe('AttributionPanel', () => {
  it('fetches scope on open, shows both periods, and only starts AI after explicit confirmation', async () => {
    const wrapper = render()
    expect(api.startAnalysis).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="open-analysis"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="current-period"]').text()).toBe('2026-09-01 至 2026-09-30')
    expect(wrapper.get<HTMLInputElement>('[data-testid="baseline-start"]').element.value).toBe('2026-08-01')
    expect(wrapper.get<HTMLInputElement>('[data-testid="baseline-end"]').element.value).toBe('2026-08-31')
    expect(api.startAnalysis).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="baseline-start"]').setValue('2026-07-01')
    await wrapper.get('[data-testid="baseline-end"]').setValue('2026-07-31')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(api.startAnalysis).toHaveBeenCalledWith('ask1', { start: '2026-07-01', end: '2026-08-01' }, expect.any(String), 'dual')
    wrapper.unmount()
  })
  it('does not invent a baseline when no full month suggestion exists', async () => {
    vi.mocked(api.getAnalysisContext).mockResolvedValue(response({ ...context, suggestedBaselinePeriod: null }))
    const wrapper = render()
    await wrapper.get('[data-testid="open-analysis"]').trigger('click'); await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[data-testid="baseline-start"]').element.value).toBe('')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(api.startAnalysis).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写完整、有效的基期起止日期')
    wrapper.unmount()
  })
  it('renders offsetting department changes, partial status, evidence and unverified hypothesis without a confidence score', async () => {
    const wrapper = render()
    await wrapper.get('[data-testid="open-analysis"]').trigger('click'); await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('部分完成')
    expect(wrapper.text()).toContain('以下仅展示已经核对的数据')
    expect(wrapper.text()).toContain('总量未变化')
    expect(wrapper.get('tbody').text()).toContain('+2')
    expect(wrapper.get('tbody').text()).toContain('-2')
    expect(wrapper.text()).toContain('ev_department')
    expect(wrapper.text()).toContain('snapshot1')
    expect(wrapper.text()).toContain('待核查假设')
    expect(wrapper.text()).toContain('已达到补查次数上限')
    expect(wrapper.text()).toContain('统计贡献不等于真实离职原因')
    expect(wrapper.text()).not.toContain('92%')
    wrapper.unmount()
  })
  it('shows the verified daily facts used in supplement review with their limits', async () => {
    vi.mocked(api.startAnalysis).mockResolvedValue(response({ taskId: 'task1', sourceAskId: 'ask1', status: 'COMPLETED',
      context: { ...context, baselinePeriod: context.suggestedBaselinePeriod! }, events: [],
      result: { ...result, status: 'COMPLETED', unresolved: [],
        facts: { 'daily_peak:current': { date: '2026-09-02', count: 4, total: 4 } },
        supplement_assessment: { claim_id: 'h1', evidence_id: 'ev_daily', fact_ids: ['daily_peak:current'], conclusion: 'descriptive_only' },
      } }))
    const wrapper = render()
    await wrapper.get('[data-testid="open-analysis"]').trigger('click'); await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    const supplement = wrapper.get('[data-testid="supplement-facts"]').text()
    expect(supplement).toContain('2026-09-02 的 4 人')
    expect(supplement).toContain('ev_daily')
    expect(supplement).toContain('尚不能据此确认日期集中或真实离职原因')
    wrapper.unmount()
  })
})
