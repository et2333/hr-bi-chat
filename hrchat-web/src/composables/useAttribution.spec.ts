import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useAttribution } from './useAttribution'
import * as api from '@/api/attribution'
import type { AnalysisContext, AnalysisEvent, AnalysisResult, AnalysisTask } from '@/api/attribution'

vi.mock('@/api/attribution', () => ({ getAnalysisContext: vi.fn(), startAnalysis: vi.fn(), getAnalysisTask: vi.fn(),
  cancelAnalysis: vi.fn(), streamAnalysis: vi.fn() }))
const context: AnalysisContext = { sourceAskId: 'ask1', metricCode: 'leave_count', metricName: '离职人数', unit: '人', scopeLabel: '研发中心',
  currentPeriod: { start: '2026-09-01', end: '2026-10-01' }, suggestedBaselinePeriod: { start: '2026-08-01', end: '2026-09-01' } }
const running: AnalysisTask = { taskId: 'task1', sourceAskId: 'ask1', status: 'RUNNING', context, result: null, events: [] }
const response = <T>(data: T) => ({ code: 'SUCCESS', message: '', traceId: 't', data })
let apiFrame: (event: AnalysisEvent) => void
let apiError: (message: string) => void
let endStream: () => void
const close = vi.fn()
let vm: ReturnType<typeof useAttribution>
let wrapper: ReturnType<typeof mount>
const storageKey = 'hrchat_analysis:["t01","hr01","ask1"]'

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  vi.mocked(api.getAnalysisContext).mockResolvedValue(response(context))
  vi.mocked(api.startAnalysis).mockResolvedValue(response(running))
  vi.mocked(api.getAnalysisTask).mockResolvedValue(response(running))
  vi.mocked(api.streamAnalysis).mockImplementation((_id, _seq, frame, error) => {
    apiFrame = frame; apiError = error
    return { close, done: new Promise<void>(resolve => { endStream = resolve }) }
  })
  wrapper = mount(defineComponent({ setup() { vm = useAttribution('ask1'); return () => null } }))
})
afterEach(() => wrapper.unmount())

describe('analysis task lifecycle', () => {
  it('prepares only context and starts the confirmed periods with an idempotency key', async () => {
    await vm.prepare()
    expect(api.startAnalysis).not.toHaveBeenCalled()
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    expect(api.startAnalysis).toHaveBeenCalledWith('ask1', context.suggestedBaselinePeriod, expect.any(String), 'dual')
    expect(sessionStorage.getItem(storageKey)).toBe('task1')
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    expect(api.startAnalysis).toHaveBeenCalledTimes(1)
  })
  it('reuses the key after an uncertain POST failure, but changes it for changed periods', async () => {
    vi.mocked(api.startAnalysis).mockRejectedValue(new Error('network'))
    await vm.prepare()
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    const key = vi.mocked(api.startAnalysis).mock.calls[0][2]
    await vm.start(context.suggestedBaselinePeriod!, 'dual')
    expect(vi.mocked(api.startAnalysis).mock.calls[1][2]).toBe(key)
    await vm.start({ start: '2026-07-01', end: '2026-08-01' }, 'dual')
    expect(vi.mocked(api.startAnalysis).mock.calls[2][2]).not.toBe(key)
  })
  it('shows actual incremental stage messages and ignores replayed sequence numbers', async () => {
    await vm.prepare(); await vm.start(context.suggestedBaselinePeriod!, 'dual')
    const frame = { seq: 1, event: 'PLAN_UPDATE', payload: { message: '制定部门贡献分析计划' } }
    apiFrame(frame); apiFrame(frame)
    apiFrame({ seq: 2, event: 'TOOL_CALL_START', payload: { message: '读取两期部门聚合' } })
    expect(vm.stages.value).toEqual(['制定部门贡献分析计划', '读取两期部门聚合'])
    expect(vm.task.value?.status).toBe('RUNNING')
  })
  it('restores a disconnected task without starting another model run', async () => {
    await vm.prepare(); await vm.start(context.suggestedBaselinePeriod!, 'dual')
    apiFrame({ seq: 1, event: 'PLAN_UPDATE', payload: { message: '开始分析' } })
    apiError('连接中断'); endStream(); await flushPromises()
    expect(vm.disconnected.value).toBe(true)
    await vm.restore()
    expect(api.getAnalysisTask).toHaveBeenCalledWith('task1')
    expect(api.streamAnalysis).toHaveBeenLastCalledWith('task1', 1, expect.any(Function), expect.any(Function))
    expect(api.startAnalysis).toHaveBeenCalledTimes(1)
  })
  it('preserves verified PARTIAL data after replaying an error and rechecking authorization', async () => {
    const result = { summary: { current_total: 2, baseline_total: 1, delta: 1, closure_verified: true } } as AnalysisResult
    vi.mocked(api.getAnalysisTask).mockResolvedValue(response({ ...running, status: 'PARTIAL', result,
      events: [{ seq: 3, event: 'ERROR', payload: { status: 'PARTIAL' } }] }))
    sessionStorage.setItem(storageKey, 'task1')
    await vm.restore()
    expect(vm.task.value?.result?.summary?.delta).toBe(1)
    expect(api.streamAnalysis).not.toHaveBeenCalled()
  })
  it('hides an earlier result when reauthorization fails', async () => {
    sessionStorage.setItem(storageKey, 'task1')
    await vm.restore()
    vi.mocked(api.getAnalysisTask).mockRejectedValue(new Error('当前权限已变化'))
    await vm.restore()
    expect(vm.task.value).toBeNull()
    expect(vm.error.value).toContain('权限已变化')
    expect(api.startAnalysis).not.toHaveBeenCalled()
  })
  it('cancels at the service and closes progress; unmount alone only disconnects', async () => {
    await vm.prepare(); await vm.start(context.suggestedBaselinePeriod!, 'dual')
    vi.mocked(api.cancelAnalysis).mockResolvedValue(response({ ...running, status: 'CANCELLED' }))
    await vm.cancel()
    expect(api.cancelAnalysis).toHaveBeenCalledWith('task1')
    expect(close).toHaveBeenCalled()
    expect(vm.task.value?.status).toBe('CANCELLED')
    wrapper.unmount()
    expect(api.cancelAnalysis).toHaveBeenCalledTimes(1)
  })
})
