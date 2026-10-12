import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getChartAnalysis,
  postInsightTask,
  retryInsightTask,
  type ChartAnalysisView,
  type InsightSource,
  type InsightStatus,
  type InsightTaskView,
} from '@/api/reports'
import { useReportChartAnalysis } from './useReportChartAnalysis'

vi.mock('@/api/reports', () => ({
  getChartAnalysis: vi.fn(),
  postInsightTask: vi.fn(),
  retryInsightTask: vi.fn(),
}))

/** 来源身份：ensure/retry 必须原样回传的快照与范围标识。 */
function source(version: string, dimValue: string | null = null): InsightSource {
  return {
    reportId: 7,
    componentId: 23,
    dataVersion: version,
    viewKey: dimValue ? `dim=org|value=${encodeURIComponent(dimValue)}` : 'root',
    snapshotId: `snap-${version}`,
    scopeRef: 'scope-1',
  }
}

function retryable(status: InsightStatus): boolean {
  return status === 'FAILED' || status === 'DEGRADED' || status === 'INTERRUPTED' || status === 'CANCELLED'
}

/** 仅图表就绪且该状态带洞察时才附带 insight；否则为 null。 */
function snapshot(
  version: string,
  dimValue: string | null = null,
  status: InsightStatus = 'COMPLETE',
): { data: ChartAnalysisView } {
  const hasInsight = status === 'COMPLETE' || status === 'DEGRADED'
  const hasTask = status !== 'MISSING' && status !== 'NOT_APPLICABLE'
  const src = status === 'NOT_APPLICABLE' ? null : source(version, dimValue)
  return { data: {
    dataVersion: version,
    viewKey: dimValue ? `dim=org|value=${encodeURIComponent(dimValue)}` : 'root',
    dimValue,
    chartStatus: 'READY',
    insightStatus: status,
    taskId: hasTask ? 7 : null,
    attempt: hasTask ? 1 : null,
    chart: { chartType: 'BAR', categories: [version], series: [{ name: '人数', data: [2] }], pieData: [] },
    insight: hasInsight
      ? { reportName: '报表', metricName: '人数', summary: version, points: [], limitations: [], actions: [] }
      : null,
    source: src,
    canEnsure: status === 'MISSING' && !!src,
    canRetry: retryable(status) && !!src,
  } }
}

/** 洞察任务视图（ensure/retry 返回）。 */
function task(status: InsightStatus, version = 'v1', dimValue: string | null = null): InsightTaskView {
  const hasInsight = status === 'COMPLETE' || status === 'DEGRADED'
  const src = source(version, dimValue)
  return {
    taskId: 7,
    attempt: 1,
    status,
    dataVersion: version,
    viewKey: src.viewKey,
    scopeRef: src.scopeRef,
    insightRevision: 'r1',
    degradationReason: null,
    failureReason: null,
    insightGeneratedAt: null,
    insight: hasInsight
      ? { reportName: '报表', metricName: '人数', summary: version, points: [], limitations: [], actions: [] }
      : null,
    generation: null,
    source: src,
    canRetry: retryable(status),
  }
}

function deferred() {
  let resolve!: (value: ReturnType<typeof snapshot>) => void
  let reject!: (error: Error) => void
  const promise = new Promise<ReturnType<typeof snapshot>>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// API 的其余外层字段与联动无关，测试只构造 data。
function respond(value: ReturnType<typeof snapshot>) {
  vi.mocked(getChartAnalysis).mockResolvedValueOnce(value as Awaited<ReturnType<typeof getChartAnalysis>>)
}

function respondTask(value: InsightTaskView) {
  vi.mocked(postInsightTask).mockResolvedValueOnce({ data: value } as Awaited<ReturnType<typeof postInsightTask>>)
}

describe('报表图表与洞察同步', () => {
  beforeEach(() => { vi.resetAllMocks() })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

  it('首次加载、下钻和回溯均同步清空并更新图文版本', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('root'))
    await state.loadChartData(23)
    const pending = deferred()
    vi.mocked(getChartAnalysis).mockReturnValueOnce(pending.promise as ReturnType<typeof getChartAnalysis>)
    const task = state.loadChartData(23, '研发中心')
    expect(state.chartStates.value[23].data).toBeNull()
    expect(state.insightStates.value[23].data).toBeNull()
    expect(getChartAnalysis).toHaveBeenLastCalledWith(7, 23, '研发中心')
    pending.resolve(snapshot('child', '研发中心'))
    await task
    expect(state.chartStates.value[23].dataVersion).toBe('child')
    expect(state.insightStates.value[23].dataVersion).toBe('child')
    respond(snapshot('root-v2'))
    await state.loadChartData(23)
    expect(getChartAnalysis).toHaveBeenLastCalledWith(7, 23, undefined)
    expect(state.insightStates.value[23].data?.summary).toBe('root-v2')
  })

  it.each(['resolve', 'reject'] as const)('忽略过期请求的 %s，不覆盖新图文或报错', async (outcome) => {
    const onError = vi.fn()
    const state = useReportChartAnalysis(7, onError)
    const old = deferred()
    vi.mocked(getChartAnalysis).mockReturnValueOnce(old.promise as ReturnType<typeof getChartAnalysis>)
    const task = state.loadChartData(23, '旧组织')
    respond(snapshot('new', '新组织'))
    await state.loadChartData(23, '新组织')
    if (outcome === 'resolve') old.resolve(snapshot('old'))
    else old.reject(new Error('旧请求失败'))
    await task
    expect(state.chartStates.value[23].dataVersion).toBe('new')
    expect(state.insightStates.value[23].data?.summary).toBe('new')
    expect(onError).not.toHaveBeenCalled()
  })

  it('洞察降级时保留同版图表，重试沿用当前组织并一起刷新', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    const degraded = snapshot('v1', '研发中心', 'DEGRADED')
    degraded.data.insight!.points = [{ type: 'degraded', label: '暂不可用' }]
    respond(degraded)
    await state.loadChartData(23, '研发中心')
    expect(state.chartStates.value[23].data).not.toBeNull()
    expect(state.insightStates.value[23].error).toBe(true)
    respond(snapshot('v2', '研发中心'))
    await state.loadInsight(23)
    expect(getChartAnalysis).toHaveBeenLastCalledWith(7, 23, '研发中心')
    expect(state.chartStates.value[23].dataVersion).toBe('v2')
    expect(state.insightStates.value[23].dataVersion).toBe('v2')
    expect(state.insightStates.value[23].error).toBe(false)
  })

  it('当前请求失败清除旧结果，其他组件不受影响', async () => {
    const onError = vi.fn()
    const state = useReportChartAnalysis(7, onError)
    respond(snapshot('other'))
    await state.loadChartData(24)
    respond(snapshot('old'))
    await state.loadChartData(23)
    vi.mocked(getChartAnalysis).mockRejectedValueOnce(new Error('取数失败'))
    await state.loadChartData(23, '研发中心')
    expect(state.chartStates.value[23].data).toBeNull()
    expect(state.insightStates.value[23]).toMatchObject({ loading: false, data: null, error: true })
    expect(state.chartStates.value[24].dataVersion).toBe('other')
    expect(onError).toHaveBeenCalledOnce()
  })

  it('离开页面后忽略尚未完成的请求', async () => {
    const scope = effectScope()
    const onError = vi.fn()
    const state = scope.run(() => useReportChartAnalysis(7, onError))!
    const pending = deferred()
    vi.mocked(getChartAnalysis).mockReturnValueOnce(pending.promise as ReturnType<typeof getChartAnalysis>)
    const task = state.loadChartData(23)
    scope.stop()
    pending.reject(new Error('已离开'))
    await task
    expect(state.chartStates.value[23].data).toBeNull()
    expect(onError).not.toHaveBeenCalled()
  })

  it('洞察进入终态后停止轮询', async () => {
    vi.useFakeTimers()
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'PENDING'))
    await state.loadChartData(23)
    expect(state.insightStates.value[23].status).toBe('PENDING')
    respond(snapshot('v1', null, 'COMPLETE'))
    await vi.advanceTimersByTimeAsync(1500)
    expect(state.insightStates.value[23].status).toBe('COMPLETE')
    const calls = vi.mocked(getChartAnalysis).mock.calls.length
    await vi.advanceTimersByTimeAsync(1500 * 5)
    expect(vi.mocked(getChartAnalysis).mock.calls.length).toBe(calls)
  })

  it('下钻切换后丢弃过期的轮询响应', async () => {
    vi.useFakeTimers()
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('va', 'A', 'PENDING'))
    await state.loadChartData(23, 'A')
    const pendingPoll = deferred()
    vi.mocked(getChartAnalysis).mockReturnValueOnce(pendingPoll.promise as ReturnType<typeof getChartAnalysis>)
    await vi.advanceTimersByTimeAsync(1500)
    // 切换维度期间，旧视图的轮询响应到达
    respond(snapshot('vb', 'B'))
    await state.loadChartData(23, 'B')
    expect(state.chartStates.value[23].dataVersion).toBe('vb')
    pendingPoll.resolve(snapshot('va2', 'A', 'PENDING'))
    await Promise.resolve()
    expect(state.chartStates.value[23].dataVersion).toBe('vb')
    expect(state.insightStates.value[23].dataVersion).toBe('vb')
  })

  it('仅当图表就绪且洞察缺失时幂等 ensure 任务', async () => {
    vi.useFakeTimers()
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'COMPLETE'))
    await state.loadChartData(23)
    await vi.advanceTimersByTimeAsync(1000)
    expect(postInsightTask).not.toHaveBeenCalled()
    respond(snapshot('v2', null, 'MISSING'))
    await state.loadChartData(23)
    respondTask(task('PENDING', 'v2'))
    await vi.advanceTimersByTimeAsync(1000)
    // 携带该视图的来源身份（快照/范围）而非仅 dimValue
    expect(postInsightTask).toHaveBeenCalledWith(7, 23, source('v2'))
    expect(state.insightStates.value[23].status).toBe('PENDING')
  })

  it('不支持洞察的视图（NOT_APPLICABLE）不发起 ensure', async () => {
    vi.useFakeTimers()
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'NOT_APPLICABLE'))
    await state.loadChartData(23)
    await vi.advanceTimersByTimeAsync(1000)
    expect(postInsightTask).not.toHaveBeenCalled()
    expect(state.insightStates.value[23].status).toBe('NOT_APPLICABLE')
  })

  it('重试复用同一快照并携带来源身份与预期 attempt', async () => {
    vi.useFakeTimers()
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'FAILED'))
    await state.loadChartData(23)
    expect(state.insightStates.value[23].status).toBe('FAILED')
    vi.mocked(retryInsightTask).mockResolvedValueOnce({ data: task('PENDING', 'v1') } as Awaited<ReturnType<typeof retryInsightTask>>)
    await state.retryInsight(23)
    expect(retryInsightTask).toHaveBeenCalledWith(7, 23, 7, source('v1'), 1)
    expect(state.insightStates.value[23].status).toBe('PENDING')
  })
})

// R1/R1-R6 验收负例（原 docs/evaluation-runs/r1-r6-review-20261012）转为正式回归用例。
describe('R1 版本竞态与 A6 重试恢复', () => {
  beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers() })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

  it('拒绝来源不一致的 ensure 响应，不把新版洞察贴到旧图', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'MISSING'))
    respondTask(task('COMPLETE', 'v2'))
    await state.loadChartData(23)
    await vi.advanceTimersByTimeAsync(400)
    expect(state.chartStates.value[23].dataVersion).toBe('v1')
    expect(state.insightStates.value[23].dataVersion).toBe('v1')
  })

  it('轮询携带固定来源版本，避免新版本发布后读到 V2', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'PENDING'))
    await state.loadChartData(23)
    respond(snapshot('v2', null, 'COMPLETE'))
    await vi.advanceTimersByTimeAsync(1500)
    expect(JSON.stringify(vi.mocked(getChartAnalysis).mock.calls.at(-1))).toContain('v1')
  })

  it('重试网络失败后不留下无人执行的 PENDING，保留可重试状态', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'FAILED'))
    await state.loadChartData(23)
    vi.mocked(retryInsightTask).mockRejectedValueOnce(new Error('offline'))
    await state.retryInsight(23)
    expect(state.insightStates.value[23].status).toBe('FAILED')
    expect(state.insightStates.value[23].canRetry).toBe(true)
  })

  it('重试后丢弃旧轮次的迟到轮询响应，不回退 attempt', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'PENDING'))
    await state.loadChartData(23)
    const old = deferred()
    vi.mocked(getChartAnalysis).mockReturnValueOnce(old.promise as ReturnType<typeof getChartAnalysis>)
    await vi.advanceTimersByTimeAsync(1500)
    respond(snapshot('v1', null, 'FAILED'))
    await vi.advanceTimersByTimeAsync(1500)
    const next = task('PENDING', 'v1'); next.attempt = 2
    vi.mocked(retryInsightTask).mockResolvedValueOnce({ data: next } as Awaited<ReturnType<typeof retryInsightTask>>)
    await state.retryInsight(23)
    old.resolve(snapshot('v1', null, 'COMPLETE'))
    await vi.advanceTimersByTimeAsync(0)
    expect(state.insightStates.value[23].attempt).toBe(2)
  })
})

// B4 反例（原 docs/evaluation-runs/r1-r6-recheck-20261012）转为正式回归用例：
// 不确定提交的只读回读必须按统一状态机续接轮询，而不是停在 RUNNING。
describe('重试不确定提交后的回读恢复（B4）', () => {
  beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers() })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

  it('回读得到 RUNNING 与新轮次时恢复轮询直至终态', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'FAILED'))
    await state.loadChartData(23)
    vi.mocked(retryInsightTask).mockRejectedValueOnce(new Error('response lost'))
    const running = snapshot('v1', null, 'RUNNING'); running.data.attempt = 2
    respond(running)
    await state.retryInsight(23)
    expect(state.insightStates.value[23].status).toBe('RUNNING')
    const done = snapshot('v1', null, 'COMPLETE'); done.data.attempt = 2
    respond(done)
    await vi.advanceTimersByTimeAsync(3000)
    expect(state.insightStates.value[23].status).toBe('COMPLETE')
  })

  it('409 attempt 冲突后按回读的最新轮次续接，且不重发生成请求', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    respond(snapshot('v1', null, 'FAILED'))
    await state.loadChartData(23)
    vi.mocked(retryInsightTask).mockRejectedValueOnce(new Error('attempt conflict'))
    const conflict = snapshot('v1', null, 'RUNNING'); conflict.data.attempt = 2
    respond(conflict)
    await state.retryInsight(23)
    const done = snapshot('v1', null, 'COMPLETE'); done.data.attempt = 2
    respond(done)
    await vi.advanceTimersByTimeAsync(3000)
    expect(state.insightStates.value[23].status).toBe('COMPLETE')
    expect(retryInsightTask).toHaveBeenCalledTimes(1)
    expect(postInsightTask).not.toHaveBeenCalled()
  })

  it('回读响应轮次回退时不予采纳，保留重试前的可信状态', async () => {
    const state = useReportChartAnalysis(7, vi.fn())
    const failed = snapshot('v1', null, 'FAILED'); failed.data.attempt = 2
    respond(failed)
    await state.loadChartData(23)
    vi.mocked(retryInsightTask).mockRejectedValueOnce(new Error('offline'))
    const stale = snapshot('v1', null, 'COMPLETE'); stale.data.attempt = 1
    respond(stale)
    await state.retryInsight(23)
    expect(state.insightStates.value[23].status).toBe('FAILED')
    expect(state.insightStates.value[23].attempt).toBe(2)
  })
})
