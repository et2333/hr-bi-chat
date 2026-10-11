import { effectScope } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getChartAnalysis, type ChartAnalysisView } from '@/api/reports'
import { useReportChartAnalysis } from './useReportChartAnalysis'

vi.mock('@/api/reports', () => ({ getChartAnalysis: vi.fn() }))

function snapshot(version: string, dimValue: string | null = null): { data: ChartAnalysisView } {
  return { data: {
    dataVersion: version, dimValue,
    chart: { chartType: 'BAR', categories: [version], series: [{ name: '人数', data: [2] }], pieData: [] },
    insight: { reportName: '报表', metricName: '人数', summary: version, points: [] },
  } }
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

describe('报表图表与洞察同步', () => {
  beforeEach(() => { vi.resetAllMocks() })

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
    const degraded = snapshot('v1', '研发中心')
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
})
