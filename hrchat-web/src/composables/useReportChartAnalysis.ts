import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { getChartAnalysis, type ChartDataView, type InsightView } from '@/api/reports'

interface ChartState {
  loading: boolean
  data: ChartDataView | null
  dataVersion?: string
}

interface InsightState {
  loading: boolean
  data: InsightView | null
  error: boolean
  /** READY | PENDING | NONE | DEGRADED | FAILED */
  status?: string
  dataVersion?: string
}

/** 统一刷新图文；请求序号仅用于防乱序，dataVersion 来自服务端共享快照。 */
export function useReportChartAnalysis(reportId: number, onError: (error: unknown) => void) {
  const chartStates = ref<Record<number, ChartState>>({})
  const insightStates = ref<Record<number, InsightState>>({})
  const requests = new Map<number, number>()
  const scopes = new Map<number, string | undefined>()
  let disposed = false
  if (getCurrentScope()) onScopeDispose(() => { disposed = true })

  async function loadChartData(componentId: number, dimValue?: string) {
    const request = (requests.get(componentId) ?? 0) + 1
    requests.set(componentId, request)
    scopes.set(componentId, dimValue)
    const isCurrent = () => !disposed && requests.get(componentId) === request
    chartStates.value[componentId] = { loading: true, data: null }
    insightStates.value[componentId] = { loading: true, data: null, error: false, status: 'PENDING' }
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue)
      if (!isCurrent()) return
      if (data.chartStatus === 'NEED_GENERATE' || data.chartStatus === 'VIEW_NOT_DERIVABLE' || !data.chart) {
        chartStates.value[componentId] = {
          loading: false,
          data: null,
          dataVersion: data.dataVersion ?? undefined,
        }
        const pendingInsight = data.insightStatus === 'PENDING' || data.insightStatus === 'NONE'
        insightStates.value[componentId] = {
          loading: false,
          data: null,
          error: data.chartStatus === 'VIEW_NOT_DERIVABLE',
          status: pendingInsight ? data.insightStatus : (data.chartStatus === 'VIEW_NOT_DERIVABLE' ? 'FAILED' : data.insightStatus),
          dataVersion: data.dataVersion ?? undefined,
        }
        if (data.chartStatus === 'VIEW_NOT_DERIVABLE') {
          onError(new Error('当前数据版本无法派生该下钻视图，请显式刷新数据'))
        }
        return
      }
      chartStates.value[componentId] = {
        loading: false,
        data: data.chart,
        dataVersion: data.dataVersion ?? undefined,
      }
      if (data.insight) {
        insightStates.value[componentId] = {
          loading: false,
          data: data.insight,
          dataVersion: data.dataVersion ?? undefined,
          status: data.insightStatus || 'READY',
          error: data.insight.points.some((point) => point.type === 'degraded'),
        }
      } else if (data.insightStatus === 'PENDING' || data.insightStatus === 'NONE') {
        insightStates.value[componentId] = {
          loading: false,
          data: null,
          error: false,
          status: data.insightStatus,
          dataVersion: data.dataVersion ?? undefined,
        }
      } else {
        delete insightStates.value[componentId]
      }
    } catch (error) {
      if (!isCurrent()) return
      chartStates.value[componentId] = { loading: false, data: null }
      insightStates.value[componentId] = { loading: false, data: null, error: true, status: 'FAILED' }
      onError(error)
    }
  }

  // 洞察重试也刷新图表，继续使用当前条件，保证同一版本。
  function loadInsight(componentId: number) {
    return loadChartData(componentId, scopes.get(componentId))
  }

  return { chartStates, insightStates, loadChartData, loadInsight }
}
