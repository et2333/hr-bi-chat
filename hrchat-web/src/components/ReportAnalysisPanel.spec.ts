import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import ReportAnalysisPanel from './ReportAnalysisPanel.vue'
import * as reportsApi from '@/api/reports'
import * as api from '@/api/attribution'
import type { ReportAnalysisPrefill, ReportAnalysisTask } from '@/api/reports'
import type { AnalysisResult, AnalysisTask } from '@/api/attribution'

vi.mock('@/api/reports', () => ({ getReportAnalysisContext: vi.fn(), startReportAnalysis: vi.fn() }))
vi.mock('@/api/attribution', () => ({ getAnalysisTask: vi.fn(), cancelAnalysis: vi.fn(), streamAnalysis: vi.fn() }))

const capability = 'leave_count_department_contribution'
const context: ReportAnalysisPrefill = {
  sourceType: 'REPORT', sourceAskId: null, sourceReportId: 42, sourceDataVersionId: 7,
  metricCode: 'leave_count', metricName: '离职人数', unit: '人', scopeLabel: '报表授权范围',
  currentPeriod: { start: '2026-09-01', end: '2026-10-01' },
  suggestedBaselinePeriod: { start: '2026-08-01', end: '2026-09-01' },
  scopeOrgIds: [2, 3], capabilityId: capability, supported: true, executable: true, missingParams: [],
}
const result: AnalysisResult = {
  status: 'PARTIAL', mode: 'dual',
  summary: { current_total: 4, baseline_total: 4, delta: 0, closure_verified: true },
  contributions: [{ org_id: 101, org_name: '研发一部', current_count: 3, baseline_count: 1, delta: 2 }],
  facts: {}, claims: [], evidence: [], data_quality: ['统计贡献不等于真实离职原因。'], unresolved: [],
  disclaimer: '辅助分析，仅供参考', usage: {},
}
const running: ReportAnalysisTask = { taskId: 'task1', sourceAskId: null, status: 'RUNNING', context, result: null, events: [] }
const response = <T>(data: T) => ({ code: 'SUCCESS', message: '', traceId: 't', data })
const asTask = (task: ReportAnalysisTask): AnalysisTask => task as unknown as AnalysisTask
const close = vi.fn()
const render = () => mount(ReportAnalysisPanel, {
  props: { open: true, reportId: 42, dataVersionId: 7, capabilityId: capability },
  global: { plugins: [Antd], stubs: { 'a-drawer': { template: '<div class="drawer-stub"><slot /></div>' } } },
})

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear(); sessionStorage.clear()
  vi.mocked(reportsApi.getReportAnalysisContext).mockResolvedValue(response(context))
  vi.mocked(reportsApi.startReportAnalysis).mockResolvedValue(response(running))
  vi.mocked(api.streamAnalysis).mockImplementation(() => ({ close, done: new Promise<void>(() => { /* stays open */ }) }))
})

describe('ReportAnalysisPanel', () => {
  it('prefills the report scope on open without creating a task', async () => {
    const wrapper = render()
    await flushPromises()
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledWith(42, 7, capability)
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.get('[data-testid="current-period"]').text()).toBe('2026-09-01 至 2026-09-30')
    expect(reportsApi.startReportAnalysis).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('creates the report task on confirmation and renders contributions', async () => {
    vi.mocked(reportsApi.startReportAnalysis).mockResolvedValue(response({ ...running, status: 'PARTIAL', result }))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledWith(
      42, { dataVersionId: 7, baselinePeriod: { start: '2026-08-01', end: '2026-09-01' }, mode: 'dual' }, expect.any(String))
    expect(wrapper.get('[role="status"]').text()).toBe('部分完成')
    expect(wrapper.get('tbody').text()).toContain('研发一部')
    expect(api.streamAnalysis).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('collapse stops display without cancelling, and reopening restores the same task', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    const collapse = wrapper.findAll('button').find((button) => button.text().replace(/\s/g, '').includes('收起'))
    expect(collapse).toBeTruthy()
    await collapse!.trigger('click')
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
    expect(api.cancelAnalysis).not.toHaveBeenCalled()
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true }); await flushPromises()
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledTimes(1)
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('cancels the running report task explicitly', async () => {
    vi.mocked(api.cancelAnalysis).mockResolvedValue(response(asTask({ ...running, status: 'CANCELLED' })))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    const cancel = wrapper.findAll('button').find((button) => button.text().replace(/\s/g, '').includes('取消分析'))
    expect(cancel).toBeTruthy()
    await cancel!.trigger('click'); await flushPromises()
    expect(api.cancelAnalysis).toHaveBeenCalledWith('task1')
    expect(wrapper.text()).toContain('已取消分析')
    wrapper.unmount()
  })

  it('builds no session key while props are empty, then adopts the loaded version', async () => {
    const wrapper = mount(ReportAnalysisPanel, {
      props: { open: false, reportId: 42, dataVersionId: 0, capabilityId: '' },
      global: { plugins: [Antd], stubs: { 'a-drawer': { template: '<div class="drawer-stub"><slot /></div>' } } },
    })
    await flushPromises()
    // 初始 version=0/capability=''：不读取范围、不建立任何恢复键
    expect(reportsApi.getReportAnalysisContext).not.toHaveBeenCalled()
    expect(Object.keys(sessionStorage)).toEqual([])

    await wrapper.setProps({ dataVersionId: 7, capabilityId: capability, open: true })
    await flushPromises()
    // 载入 V7 后只为合法来源读取范围，仍不写空键
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledWith(42, 7, capability)
    expect(Object.keys(sessionStorage)).toEqual([])
    wrapper.unmount()
  })

  it('protects a running session when the requested source changes', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledTimes(1)

    // 另一个动作版本到来时，运行中任务不被重新读取或重建
    await wrapper.setProps({ dataVersionId: 8, capabilityId: 'other_capability' })
    await flushPromises()
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledTimes(1)
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('adopts a new source once the previous task has finished', async () => {
    vi.mocked(reportsApi.startReportAnalysis).mockResolvedValue(response({ ...running, status: 'PARTIAL', result }))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit'); await flushPromises()

    await wrapper.setProps({ dataVersionId: 8, capabilityId: 'other_capability' })
    await flushPromises()
    expect(reportsApi.getReportAnalysisContext).toHaveBeenLastCalledWith(42, 8, 'other_capability')
    wrapper.unmount()
  })

  // B2 反例（原 docs/evaluation-runs/r1-r6-recheck-20261012）转为正式回归用例：
  // 首次 prepare 仍在途时切换来源，也必须让旧来源的会话/在途请求失效，而不是等到有返回数据才重置。
  it('resets the session when the source changes while the initial prepare is pending', async () => {
    let complete!: (value: ReturnType<typeof response<ReportAnalysisPrefill>>) => void
    vi.mocked(reportsApi.getReportAnalysisContext).mockReturnValueOnce(
      new Promise((resolve) => { complete = resolve }) as ReturnType<typeof reportsApi.getReportAnalysisContext>)

    const wrapper = render()
    await flushPromises()
    await wrapper.setProps({ dataVersionId: 8 })
    await flushPromises()
    // 切换后必须按新来源重新读取范围，而不是只发过 V7 一次
    expect(reportsApi.getReportAnalysisContext).toHaveBeenLastCalledWith(42, 8, capability)
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledTimes(2)

    // 迟到的 V7 响应不得写回已被新来源接管的会话
    complete(response({ ...context, sourceDataVersionId: 7 }))
    await flushPromises()
    expect(wrapper.get('[data-testid="current-period"]').text()).toBe('2026-09-01 至 2026-09-30')
    wrapper.unmount()
  })
})
