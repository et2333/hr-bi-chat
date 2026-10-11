import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useReportAnalysis } from './useReportAnalysis'
import * as reportsApi from '@/api/reports'
import * as api from '@/api/attribution'
import type { ReportAnalysisPrefill, ReportAnalysisTask } from '@/api/reports'
import type { AnalysisEvent, AnalysisTask } from '@/api/attribution'

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
const running: ReportAnalysisTask = {
  taskId: 'task1', sourceAskId: null, status: 'RUNNING', context, result: null, events: [],
}
const response = <T>(data: T) => ({ code: 'SUCCESS', message: '', traceId: 't', data })
/** 读取/取消端点按任务 ID 通用；报表来源上下文经服务端重新鉴权后返回。 */
const asTask = (task: ReportAnalysisTask): AnalysisTask => task as unknown as AnalysisTask
let apiFrame: (event: AnalysisEvent) => void
let endStream: () => void
const close = vi.fn()
let vm: ReturnType<typeof useReportAnalysis>
let wrapper: ReturnType<typeof mount>
const storageKey = `hrchat_report_analysis:${JSON.stringify(['t01', 'hr01', 42, 7, capability])}`

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  vi.mocked(reportsApi.getReportAnalysisContext).mockResolvedValue(response(context))
  vi.mocked(reportsApi.startReportAnalysis).mockResolvedValue(response(running))
  vi.mocked(api.getAnalysisTask).mockResolvedValue(response(asTask(running)))
  vi.mocked(api.streamAnalysis).mockImplementation((_id, _seq, frame) => {
    apiFrame = frame
    return { close, done: new Promise<void>(resolve => { endStream = resolve }) }
  })
  wrapper = mount(defineComponent({
    setup() { vm = useReportAnalysis(() => ({ reportId: 42, dataVersionId: 7, capabilityId: capability })); return () => null },
  }))
})
afterEach(() => wrapper.unmount())

describe('report-source analysis lifecycle', () => {
  it('prefills the trusted report scope and creates a task only after confirmation', async () => {
    await vm.prepare()
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledWith(42, 7, capability)
    expect(reportsApi.startReportAnalysis).not.toHaveBeenCalled()
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledWith(
      42, { dataVersionId: 7, baselinePeriod: context.suggestedBaselinePeriod, mode: 'dual' }, expect.any(String))
    expect(sessionStorage.getItem(storageKey)).toBe('task1')
    expect(vm.task.value?.status).toBe('RUNNING')
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledTimes(1)
  })

  it('restores a stored report task by id without starting another model run', async () => {
    sessionStorage.setItem(storageKey, 'task1')
    await vm.restore()
    expect(api.getAnalysisTask).toHaveBeenCalledWith('task1')
    expect(api.streamAnalysis).toHaveBeenLastCalledWith('task1', 0, expect.any(Function), expect.any(Function))
    expect(reportsApi.startReportAnalysis).not.toHaveBeenCalled()
  })

  it('cancels at the service and stops the progress stream', async () => {
    await vm.prepare(); await vm.start(context.suggestedBaselinePeriod!, 'dual')
    vi.mocked(api.cancelAnalysis).mockResolvedValue(response(asTask({ ...running, status: 'CANCELLED' })))
    await vm.cancel()
    expect(api.cancelAnalysis).toHaveBeenCalledWith('task1')
    expect(close).toHaveBeenCalled()
    expect(vm.task.value?.status).toBe('CANCELLED')
  })

  it('hides a stale result when report authorization is revoked', async () => {
    sessionStorage.setItem(storageKey, 'task1')
    vi.mocked(api.getAnalysisTask).mockRejectedValue(new Error('当前权限已变化'))
    await vm.restore()
    expect(vm.task.value).toBeNull()
    expect(vm.error.value).toContain('权限已变化')
    expect(reportsApi.startReportAnalysis).not.toHaveBeenCalled()
  })

  it('drops task state when the API returns an invalid snapshot', async () => {
    await vm.prepare(); await vm.start(context.suggestedBaselinePeriod!, 'dual')
    apiFrame({ seq: 1, event: 'ERROR', payload: { message: '连接中断' } })
    expect(vm.error.value).toBe('连接中断')
    expect(vm.task.value?.result).toBeNull()
    endStream(); await flushPromises()
  })
})
