import { getCurrentScope, onScopeDispose, ref } from 'vue'
import {
  getChartAnalysis,
  postInsightTask,
  retryInsightTask,
  type ChartAnalysisView,
  type ChartDataView,
  type InsightTaskView,
  type InsightView,
} from '@/api/reports'

interface ChartState {
  loading: boolean
  data: ChartDataView | null
  dataVersion?: string
}

export interface InsightState {
  loading: boolean
  data: InsightView | null
  error: boolean
  /** NONE | PENDING | RUNNING | COMPLETE | DEGRADED | FAILED | CANCELLED | INTERRUPTED */
  status: string
  dataVersion?: string
  taskId: number | null
  attempt: number | null
}

/** 轮询间隔与上限：后端异常时也不会无限轮询。 */
const POLL_INTERVAL_MS = 1500
const MAX_POLL_ATTEMPTS = 40
/** 合并快速连续的下钻切换，仅最新视图发起 ensure 模型调用。 */
const ENSURE_DEBOUNCE_MS = 300

function isPending(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 当前活跃的视图标识：请求序号 + 维度值 + 任务/版本/视图键，用于丢弃过期响应。 */
interface ActiveKey {
  request: number
  dimValue: string | undefined
  taskId: number | null
  dataVersion: string | null
  viewKey: string | null
}

/**
 * 统一刷新图文；请求序号与视图标识共同用于丢弃过期/乱序响应。
 * PENDING/RUNNING 时轮询只读图文端点直至终态；图表就绪但洞察缺失（NONE）时幂等 ensure 任务。
 */
export function useReportChartAnalysis(reportId: number, onError: (error: unknown) => void) {
  const chartStates = ref<Record<number, ChartState>>({})
  const insightStates = ref<Record<number, InsightState>>({})
  const requests = new Map<number, number>()
  const scopes = new Map<number, string | undefined>()
  const activeKeys = new Map<number, ActiveKey>()
  const pollTimers = new Map<number, ReturnType<typeof setInterval>>()
  const ensureTimers = new Map<number, ReturnType<typeof setTimeout>>()
  const pollAttempts = new Map<number, number>()
  let disposed = false

  function stopPolling(componentId: number) {
    const timer = pollTimers.get(componentId)
    if (timer !== undefined) {
      clearInterval(timer)
      pollTimers.delete(componentId)
    }
    pollAttempts.delete(componentId)
  }

  function stopEnsure(componentId: number) {
    const timer = ensureTimers.get(componentId)
    if (timer !== undefined) {
      clearTimeout(timer)
      ensureTimers.delete(componentId)
    }
  }

  function stopAll() {
    Array.from(pollTimers.keys()).forEach(stopPolling)
    Array.from(ensureTimers.keys()).forEach(stopEnsure)
  }

  if (getCurrentScope()) onScopeDispose(() => { disposed = true; stopAll() })

  function isCurrent(componentId: number, request: number, dimValue?: string): boolean {
    if (disposed) return false
    if (requests.get(componentId) !== request) return false
    const active = activeKeys.get(componentId)
    return !!active && active.request === request && active.dimValue === dimValue
  }

  /** 过期保护：仅当响应匹配当前活跃视图（序号/维度/任务/版本/视图键）时才应用。 */
  function canApply(componentId: number, data: ChartAnalysisView, request: number, dimValue?: string): boolean {
    if (!isCurrent(componentId, request, dimValue)) return false
    const active = activeKeys.get(componentId)!
    if (active.viewKey !== null && data.viewKey !== active.viewKey) return false
    if (active.dataVersion !== null && (data.dataVersion ?? null) !== active.dataVersion) return false
    if (active.taskId !== null && (data.taskId ?? null) !== active.taskId) return false
    return true
  }

  /** 应用只读图文响应并返回洞察状态。图表先更新，洞察状态缺失时另行轮询/ensure。 */
  function applyAnalysis(componentId: number, data: ChartAnalysisView, request: number, dimValue?: string): string {
    activeKeys.set(componentId, {
      request,
      dimValue,
      taskId: data.taskId ?? null,
      dataVersion: data.dataVersion ?? null,
      viewKey: data.viewKey ?? null,
    })
    const chartReady = data.chartStatus === 'READY' && !!data.chart
    if (!chartReady) {
      chartStates.value[componentId] = { loading: false, data: null, dataVersion: data.dataVersion ?? undefined }
      if (data.chartStatus === 'VIEW_NOT_DERIVABLE') {
        onError(new Error('当前数据版本无法派生该下钻视图，请显式刷新数据'))
      }
    } else {
      chartStates.value[componentId] = {
        loading: false,
        data: data.chart,
        dataVersion: data.dataVersion ?? undefined,
      }
    }
    const status = data.insightStatus || 'NONE'
    insightStates.value[componentId] = {
      loading: false,
      data: data.insight ?? null,
      error: (data.insight?.points ?? []).some((point) => point.type === 'degraded')
        || status === 'FAILED'
        || status === 'INTERRUPTED',
      status,
      dataVersion: data.dataVersion ?? undefined,
      taskId: data.taskId ?? null,
      attempt: data.attempt ?? null,
    }
    return status
  }

  /** ensure/retry 返回的任务视图：保留图表，仅更新洞察状态并续轮询。 */
  function applyTask(componentId: number, task: InsightTaskView, request: number, dimValue?: string) {
    insightStates.value[componentId] = {
      loading: false,
      data: task.insight ?? null,
      error: task.status === 'FAILED' || task.status === 'INTERRUPTED',
      status: task.status,
      dataVersion: task.dataVersion ?? undefined,
      taskId: task.taskId,
      attempt: task.attempt,
    }
    activeKeys.set(componentId, {
      request,
      dimValue,
      taskId: task.taskId,
      dataVersion: task.dataVersion ?? null,
      viewKey: task.viewKey ?? null,
    })
    if (isPending(task.status)) startPolling(componentId, dimValue, request)
  }

  function startPolling(componentId: number, dimValue: string | undefined, request: number) {
    stopPolling(componentId)
    pollAttempts.set(componentId, 0)
    const timer = setInterval(() => {
      const attempts = (pollAttempts.get(componentId) ?? 0) + 1
      pollAttempts.set(componentId, attempts)
      if (attempts > MAX_POLL_ATTEMPTS || !isCurrent(componentId, request, dimValue)) {
        stopPolling(componentId)
        return
      }
      void pollOnce(componentId, dimValue, request)
    }, POLL_INTERVAL_MS)
    pollTimers.set(componentId, timer)
  }

  async function pollOnce(componentId: number, dimValue: string | undefined, request: number) {
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue)
      if (!canApply(componentId, data, request, dimValue)) return
      const status = applyAnalysis(componentId, data, request, dimValue)
      if (!isPending(status)) stopPolling(componentId)
    } catch {
      // 瞬时失败：保留轮询，直到终态或达到上限
    }
  }

  /** 幂等 ensure：仅图表就绪且洞察缺失（NONE）时调用，防抖合并快速切换。 */
  function scheduleEnsure(componentId: number, dimValue: string | undefined, request: number) {
    stopEnsure(componentId)
    const timer = setTimeout(() => {
      ensureTimers.delete(componentId)
      void ensureInsight(componentId, dimValue, request)
    }, ENSURE_DEBOUNCE_MS)
    ensureTimers.set(componentId, timer)
  }

  async function ensureInsight(componentId: number, dimValue?: string, request?: number) {
    const token = request ?? requests.get(componentId) ?? 0
    const scope = dimValue ?? scopes.get(componentId)
    if (!isCurrent(componentId, token, scope)) return
    try {
      const { data } = await postInsightTask(reportId, componentId, scope)
      if (!isCurrent(componentId, token, scope)) return
      applyTask(componentId, data, token, scope)
    } catch (error) {
      if (!isCurrent(componentId, token, scope)) return
      onError(error)
    }
  }

  /** 重试：复用同一快照，POST retry 后继续轮询。 */
  async function retryInsight(componentId: number) {
    const active = activeKeys.get(componentId)
    const taskId = active?.taskId ?? insightStates.value[componentId]?.taskId ?? null
    if (taskId == null) return
    const request = requests.get(componentId) ?? 0
    const dimValue = scopes.get(componentId)
    const previous = insightStates.value[componentId]
    insightStates.value[componentId] = {
      loading: false,
      data: previous?.data ?? null,
      error: false,
      status: 'PENDING',
      dataVersion: previous?.dataVersion,
      taskId,
      attempt: previous?.attempt ?? null,
    }
    try {
      const { data } = await retryInsightTask(reportId, componentId, taskId)
      if (!isCurrent(componentId, request, dimValue)) return
      applyTask(componentId, data, request, dimValue)
    } catch (error) {
      if (!isCurrent(componentId, request, dimValue)) return
      onError(error)
    }
  }

  async function loadChartData(componentId: number, dimValue?: string) {
    const request = (requests.get(componentId) ?? 0) + 1
    requests.set(componentId, request)
    scopes.set(componentId, dimValue)
    activeKeys.set(componentId, { request, dimValue, taskId: null, dataVersion: null, viewKey: null })
    stopPolling(componentId)
    stopEnsure(componentId)
    chartStates.value[componentId] = { loading: true, data: null }
    insightStates.value[componentId] = { loading: true, data: null, error: false, status: 'PENDING', taskId: null, attempt: null }
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue)
      if (!canApply(componentId, data, request, dimValue)) return
      const status = applyAnalysis(componentId, data, request, dimValue)
      const chartReady = !!chartStates.value[componentId]?.data
      if (isPending(status)) {
        startPolling(componentId, dimValue, request)
      } else if (status === 'NONE' && chartReady) {
        // 仅下钻/新视图缺失洞察时才确保任务，回溯到已有洞察的视图不会走到这里
        scheduleEnsure(componentId, dimValue, request)
      }
    } catch (error) {
      if (!isCurrent(componentId, request, dimValue)) return
      chartStates.value[componentId] = { loading: false, data: null }
      insightStates.value[componentId] = { loading: false, data: null, error: true, status: 'FAILED', taskId: null, attempt: null }
      onError(error)
    }
  }

  // 洞察重试也刷新图表，继续使用当前条件，保证同一版本。
  function loadInsight(componentId: number) {
    return loadChartData(componentId, scopes.get(componentId))
  }

  return { chartStates, insightStates, loadChartData, loadInsight, ensureInsight, retryInsight }
}
