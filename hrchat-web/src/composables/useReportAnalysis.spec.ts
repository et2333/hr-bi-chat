import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
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

  it('never freezes an empty session key before a valid source is loaded', async () => {
    const params = reactive({ reportId: 42, dataVersionId: 0, capabilityId: '' })
    let scoped!: ReturnType<typeof useReportAnalysis>
    const w = mount(defineComponent({
      setup() { scoped = useReportAnalysis(() => ({ ...params })); return () => null },
    }))
    const reportKeys = () => Object.keys(sessionStorage).filter((k) => k.startsWith('hrchat_report_analysis:'))

    // 初始空 props：不得坐实任何恢复键
    await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportKeys()).toEqual([])
    expect(reportsApi.startReportAnalysis).not.toHaveBeenCalled()

    // 载入 V7 后才建立唯一的合法会话键
    params.dataVersionId = 7
    params.capabilityId = capability
    await scoped.prepare()
    await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportKeys()).toEqual([storageKey])
    expect(sessionStorage.getItem(storageKey)).toBe('task1')
    w.unmount()
  })

  it('ignores a prepare result that arrives after an explicit session reset', async () => {
    let complete!: (value: ReturnType<typeof response<ReportAnalysisPrefill>>) => void
    vi.mocked(reportsApi.getReportAnalysisContext).mockReturnValueOnce(
      new Promise((resolve) => { complete = resolve }))
    const pending = vm.prepare()
    vm.resetSession()
    complete(response(context))
    await pending
    expect(vm.context.value).toBeNull()
  })

  it('keeps the new session busy when a stale prepare resolves late (B2)', async () => {
    let stale!: (value: ReturnType<typeof response<ReportAnalysisPrefill>>) => void
    vi.mocked(reportsApi.getReportAnalysisContext).mockReturnValueOnce(
      new Promise((resolve) => { stale = resolve }))
    const first = vm.prepare()

    vm.resetSession()
    let second!: (value: ReturnType<typeof response<ReportAnalysisPrefill>>) => void
    vi.mocked(reportsApi.getReportAnalysisContext).mockReturnValueOnce(
      new Promise((resolve) => { second = resolve }))
    const pendingSecond = vm.prepare()

    // 旧代次请求迟到返回：不得解锁新一代仍在途的 busy
    stale(response(context))
    await first
    expect(vm.busy.value).toBe(true)

    second(response(context))
    await pendingSecond
    expect(vm.busy.value).toBe(false)
  })

  it('keeps the frozen source for start even when the live props change', async () => {
    const params = reactive({ reportId: 42, dataVersionId: 7, capabilityId: capability })
    let scoped!: ReturnType<typeof useReportAnalysis>
    const w = mount(defineComponent({
      setup() { scoped = useReportAnalysis(() => ({ ...params })); return () => null },
    }))
    await scoped.prepare()
    // prepare 之后主图切到 V8，但未显式 resetSession：任务参数必须仍用冻结的 V7
    params.dataVersionId = 8
    await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledWith(
      42, { dataVersionId: 7, baselinePeriod: context.suggestedBaselinePeriod, mode: 'dual' }, expect.any(String))
    w.unmount()
  })

  it('binds the report action reference into context, start body and session key (A2)', async () => {
    const ref = { componentId: 23, viewKey: 'root', actionId: 'act-1' }
    let scoped!: ReturnType<typeof useReportAnalysis>
    const w = mount(defineComponent({
      setup() {
        scoped = useReportAnalysis(() => ({ reportId: 42, dataVersionId: 7, capabilityId: capability, actionRef: ref }))
        return () => null
      },
    }))
    await scoped.prepare()
    expect(reportsApi.getReportAnalysisContext).toHaveBeenCalledWith(42, 7, capability, ref)
    await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    expect(reportsApi.startReportAnalysis).toHaveBeenCalledWith(
      42,
      {
        dataVersionId: 7, baselinePeriod: context.suggestedBaselinePeriod, mode: 'dual',
        componentId: 23, viewKey: 'root', actionId: 'act-1',
      },
      expect.any(String),
    )
    // 会话键含动作引用：同一能力的不同视图/动作各自独立恢复，不互相复用任务
    const refKey = `hrchat_report_analysis:${JSON.stringify(['t01', 'hr01', 42, 7, capability, 23, 'root', 'act-1'])}`
    expect(sessionStorage.getItem(refKey)).toBe('task1')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    w.unmount()
  })

  it('resetSession re-resolves the key so a switched source gets its own session', async () => {
    const params = reactive({ reportId: 42, dataVersionId: 7, capabilityId: capability })
    let scoped!: ReturnType<typeof useReportAnalysis>
    const w = mount(defineComponent({
      setup() { scoped = useReportAnalysis(() => ({ ...params })); return () => null },
    }))
    await scoped.prepare(); await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    expect(sessionStorage.getItem(storageKey)).toBe('task1')

    const nextKey = `hrchat_report_analysis:${JSON.stringify(['t01', 'hr01', 42, 8, 'other_capability'])}`
    params.dataVersionId = 8
    params.capabilityId = 'other_capability'
    await scoped.resetSession()
    expect(scoped.task.value).toBeNull()
    await scoped.prepare()
    vi.mocked(reportsApi.startReportAnalysis).mockResolvedValue(response({ ...running, taskId: 'task2' }))
    await scoped.start(context.suggestedBaselinePeriod!, 'dual')
    // 新来源落到独立键，旧键内容不被覆盖
    expect(sessionStorage.getItem(nextKey)).toBe('task2')
    expect(sessionStorage.getItem(storageKey)).toBe('task1')
    w.unmount()
  })
})
