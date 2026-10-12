import { getCurrentScope, onScopeDispose, ref } from 'vue'
import {
  getChartAnalysis,
  postInsightTask,
  retryInsightTask,
  type ChartAnalysisView,
  type ChartDataView,
  type InsightSource,
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
  /** MISSING | NOT_APPLICABLE | PENDING | RUNNING | COMPLETE | DEGRADED | FAILED | CANCELLED | INTERRUPTED */
  status: string
  dataVersion?: string
  taskId: number | null
  attempt: number | null
  /** 当前视图的来源身份（ensure/retry 必须原样回传） */
  source: InsightSource | null
  /** 后端判定可发起一次 ensure（图表就绪但无任务） */
  canEnsure: boolean
  /** 后端判定可重试（失败/降级/中断且快照可用） */
  canRetry: boolean
  /** 轮询超时：保留任务身份，仅提示用户显式刷新，不自动重试 */
  timedOut: boolean
}

/** 轮询间隔与上限：后端异常时也不会无限轮询。 */
const POLL_INTERVAL_MS = 1500
const MAX_POLL_ATTEMPTS = 40
/** 合并快速连续的下钻切换，仅最新视图发起 ensure 模型调用。 */
const ENSURE_DEBOUNCE_MS = 300

function isPending(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 当前活跃的视图标识：请求序号 + 维度值 + 来源身份 + 任务/版本/视图键，用于丢弃过期响应。 */
interface ActiveKey {
  request: number
  dimValue: string | undefined
  taskId: number | null
  attempt: number | null
  dataVersion: string | null
  viewKey: string | null
  source: InsightSource | null
}

/** 来源身份一致比较：reportId/componentId/dataVersion/viewKey/snapshotId/scopeRef 全等。 */
function sameSource(a: InsightSource | null, b: InsightSource | null): boolean {
  if (a === null || b === null) return a === b
  return (
    a.reportId === b.reportId &&
    a.componentId === b.componentId &&
    a.dataVersion === b.dataVersion &&
    a.viewKey === b.viewKey &&
    a.snapshotId === b.snapshotId &&
    a.scopeRef === b.scopeRef
  )
}

/**
 * 统一刷新图文；请求序号与视图/来源标识共同用于丢弃过期/乱序响应。
 * PENDING/RUNNING 时轮询只读图文端点直至终态；图表就绪但洞察缺失（MISSING 且 canEnsure）时幂等 ensure 任务。
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
  /** 正在提交与服务器状态区分的重试：同一组件只允许一次在途提交。 */
  const retrying = new Set<number>()
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

  /**
   * 过期保护：仅当响应匹配当前活跃视图（序号/维度/来源/任务/版本/视图键/轮次）时才成组应用。
   * 轮次（attempt）也必须一致，避免重试后旧轮次的迟到响应把状态退回。
   */
  function canApply(componentId: number, data: ChartAnalysisView, request: number, dimValue?: string): boolean {
    if (!isCurrent(componentId, request, dimValue)) return false
    const active = activeKeys.get(componentId)!
    if (active.viewKey !== null && data.viewKey !== active.viewKey) return false
    if (active.dataVersion !== null && (data.dataVersion ?? null) !== active.dataVersion) return false
    if (active.taskId !== null && (data.taskId ?? null) !== active.taskId) return false
    if (active.attempt !== null && (data.attempt ?? null) !== active.attempt) return false
    // 已绑定来源身份后，迟到响应若指向其他版本/快照一律丢弃，避免把新版洞察贴到旧图
    if (active.source !== null && !sameSource(active.source, data.source ?? null)) return false
    return true
  }

  /** 应用只读图文响应并返回洞察状态。图表先更新，洞察状态缺失时另行轮询/ensure。 */
  function applyAnalysis(componentId: number, data: ChartAnalysisView, request: number, dimValue?: string): string {
    if (data.chartStatus === 'STALE') {
      // 固定来源不可恢复：保留当前任务身份，仅标记需显式刷新，绝不静默改读当前版本（D1）。
      const state = insightStates.value[componentId]
      if (state) insightStates.value[componentId] = { ...state, loading: false, timedOut: true }
      return data.insightStatus
    }
    activeKeys.set(componentId, {
      request,
      dimValue,
      taskId: data.taskId ?? null,
      attempt: data.attempt ?? null,
      dataVersion: data.dataVersion ?? null,
      viewKey: data.viewKey ?? null,
      source: data.source ?? null,
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
    const status = data.insightStatus || 'MISSING'
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
      source: data.source ?? null,
      canEnsure: !!data.canEnsure,
      canRetry: !!data.canRetry && !!data.source,
      timedOut: false,
    }
    return status
  }

  /**
   * ensure/retry 返回的任务视图：保留图表，仅更新洞察状态并续轮询。
   * 与 GET 共用同一套过期/来源校验：迟到的旧轮次响应或指向其他来源的任务视图一律丢弃。
   */
  function applyTask(componentId: number, task: InsightTaskView, request: number, dimValue?: string) {
    if (!isCurrent(componentId, request, dimValue)) return
    const active = activeKeys.get(componentId)
    if (active && active.source !== null && !sameSource(active.source, task.source ?? null)) return
    if (active && active.attempt !== null && task.attempt !== null && task.attempt < active.attempt) return
    insightStates.value[componentId] = {
      loading: false,
      data: task.insight ?? null,
      error: task.status === 'FAILED' || task.status === 'INTERRUPTED',
      status: task.status,
      dataVersion: task.dataVersion ?? undefined,
      taskId: task.taskId,
      attempt: task.attempt,
      source: task.source ?? null,
      canEnsure: false,
      canRetry: !!task.canRetry && !!task.source,
      timedOut: false,
    }
    activeKeys.set(componentId, {
      request,
      dimValue,
      taskId: task.taskId,
      attempt: task.attempt,
      dataVersion: task.dataVersion ?? null,
      viewKey: task.viewKey ?? null,
      source: task.source ?? null,
    })
    if (isPending(task.status)) startPolling(componentId, dimValue, request)
  }

  function startPolling(componentId: number, dimValue: string | undefined, request: number) {
    stopPolling(componentId)
    pollAttempts.set(componentId, 0)
    const timer = setInterval(() => {
      const attempts = (pollAttempts.get(componentId) ?? 0) + 1
      pollAttempts.set(componentId, attempts)
      if (!isCurrent(componentId, request, dimValue)) {
        stopPolling(componentId)
        return
      }
      if (attempts > MAX_POLL_ATTEMPTS) {
        // 轮询超时≠失败：保留任务身份与状态，仅标记超时，等待用户显式刷新/重试
        stopPolling(componentId)
        const state = insightStates.value[componentId]
        if (state && isPending(state.status)) {
          insightStates.value[componentId] = { ...state, timedOut: true }
        }
        return
      }
      void pollOnce(componentId, dimValue, request)
    }, POLL_INTERVAL_MS)
    pollTimers.set(componentId, timer)
  }

  async function pollOnce(componentId: number, dimValue: string | undefined, request: number) {
    // 轮询必须携带当前视图的来源身份，服务端据此读取固定版本，避免发布新版本后读到 V2。
    const expected = activeKeys.get(componentId)?.source ?? null
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue, expected)
      if (!canApply(componentId, data, request, dimValue)) return
      const status = applyAnalysis(componentId, data, request, dimValue)
      if (!isPending(status)) stopPolling(componentId)
      if (needsEnsure(data)) scheduleEnsure(componentId, dimValue, request)
    } catch {
      // 瞬时失败：保留轮询，直到终态或达到上限
    }
  }

  /** 允许 ensure 的唯一条件：视图支持洞察、图表就绪、无任务且后端提供了来源身份。 */
  function needsEnsure(data: ChartAnalysisView): boolean {
    return data.insightStatus === 'MISSING' && !!data.canEnsure && !!data.source
  }

  /** 幂等 ensure：仅图表就绪且洞察缺失（MISSING）时调用，防抖合并快速切换。 */
  function scheduleEnsure(componentId: number, dimValue: string | undefined, request: number) {
    stopEnsure(componentId)
    const timer = setTimeout(() => {
      ensureTimers.delete(componentId)
      void ensureInsight(componentId, dimValue, request)
    }, ENSURE_DEBOUNCE_MS)
    ensureTimers.set(componentId, timer)
  }

  async function ensureInsight(componentId: number, dimValue?: string, request?: number) {
    const active = activeKeys.get(componentId)
    const source = active?.source ?? insightStates.value[componentId]?.source ?? null
    if (!source) return
    const token = request ?? requests.get(componentId) ?? 0
    const scope = dimValue ?? scopes.get(componentId)
    if (!isCurrent(componentId, token, scope)) return
    try {
      const { data } = await postInsightTask(reportId, componentId, source)
      if (!isCurrent(componentId, token, scope)) return
      applyTask(componentId, data, token, scope)
    } catch (error) {
      if (!isCurrent(componentId, token, scope)) return
      onError(error)
    }
  }

  /**
   * 重试：携带来源身份与预期 attempt，复用同一快照，POST 成功后继续轮询。
   * 提交期间保留重试前的可信状态（不伪造 PENDING）并只允许一次在途提交；
   * 请求失败时不假定提交结果，先只读恢复服务端任务状态，读不到再回退到重试前状态（A6）。
   */
  async function retryInsight(componentId: number) {
    const active = activeKeys.get(componentId)
    const state = insightStates.value[componentId]
    const taskId = active?.taskId ?? state?.taskId ?? null
    const source = active?.source ?? state?.source ?? null
    const attempt = active?.attempt ?? state?.attempt ?? null
    if (taskId == null || attempt == null || !source) return
    if (retrying.has(componentId)) return
    const previous = state
    // 提升响应代次：使重试前在途的旧轮次轮询响应全部失效。
    const request = (requests.get(componentId) ?? 0) + 1
    requests.set(componentId, request)
    const dimValue = scopes.get(componentId)
    insightStates.value[componentId] = {
      loading: true,
      data: previous?.data ?? null,
      error: previous?.error ?? false,
      status: previous?.status ?? 'FAILED',
      dataVersion: previous?.dataVersion,
      taskId,
      attempt,
      source,
      canEnsure: false,
      canRetry: previous?.canRetry ?? true,
      timedOut: false,
    }
    activeKeys.set(componentId, {
      request, dimValue, taskId, attempt,
      dataVersion: previous?.dataVersion ?? null, viewKey: source.viewKey, source,
    })
    retrying.add(componentId)
    try {
      const { data } = await retryInsightTask(reportId, componentId, taskId, source, attempt)
      if (!isCurrent(componentId, request, dimValue)) return
      applyTask(componentId, data, request, dimValue)
    } catch (error) {
      if (!isCurrent(componentId, request, dimValue)) return
      const restored = await readBack(componentId, source, request, dimValue)
      if (!restored && isCurrent(componentId, request, dimValue)) {
        insightStates.value[componentId] = {
          loading: false,
          data: previous?.data ?? null,
          error: previous?.error ?? false,
          status: previous?.status ?? 'FAILED',
          dataVersion: previous?.dataVersion,
          taskId,
          attempt,
          source,
          canEnsure: false,
          canRetry: previous?.canRetry ?? true,
          timedOut: false,
        }
      }
      onError(error)
    } finally {
      retrying.delete(componentId)
    }
  }

  /**
   * 只读回读服务端真实任务状态（不触发取数/模型），用于提交结果不确定时的恢复。
   * 回读结果同样要核验来源/任务/轮次：只接受同一来源与任务、轮次不回退的响应；
   * 成功后按统一状态机续接——PENDING/RUNNING 恢复绑定来源的轮询，终态停止，缺失则 ensure。
   * 不自动重发生成请求。
   */
  async function readBack(
    componentId: number,
    source: InsightSource,
    request: number,
    dimValue: string | undefined,
  ): Promise<boolean> {
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue, source)
      if (!isCurrent(componentId, request, dimValue)) return false
      if (data.chartStatus === 'STALE') return false
      const active = activeKeys.get(componentId)
      if (active) {
        if (!sameSource(active.source, source) || !sameSource(active.source, data.source ?? null)) return false
        if (active.taskId !== null && (data.taskId ?? null) !== active.taskId) return false
        // 提交可能已被受理：只拒绝轮次回退，允许回读结果把 attempt 前移。
        if (active.attempt !== null && data.attempt != null && data.attempt < active.attempt) return false
      }
      const status = applyAnalysis(componentId, data, request, dimValue)
      if (isPending(status)) startPolling(componentId, dimValue, request)
      else if (needsEnsure(data)) scheduleEnsure(componentId, dimValue, request)
      return true
    } catch {
      return false
    }
  }

  async function loadChartData(componentId: number, dimValue?: string) {
    const request = (requests.get(componentId) ?? 0) + 1
    requests.set(componentId, request)
    scopes.set(componentId, dimValue)
    activeKeys.set(componentId, {
      request, dimValue, taskId: null, attempt: null, dataVersion: null, viewKey: null, source: null,
    })
    stopPolling(componentId)
    stopEnsure(componentId)
    chartStates.value[componentId] = { loading: true, data: null }
    insightStates.value[componentId] = {
      loading: true, data: null, error: false, status: 'PENDING',
      taskId: null, attempt: null, source: null, canEnsure: false, canRetry: false, timedOut: false,
    }
    try {
      const { data } = await getChartAnalysis(reportId, componentId, dimValue)
      if (!canApply(componentId, data, request, dimValue)) return
      const status = applyAnalysis(componentId, data, request, dimValue)
      if (isPending(status)) {
        startPolling(componentId, dimValue, request)
      } else if (needsEnsure(data)) {
        // 仅「图表就绪但无任务」时确保一次任务；已有洞察或不可派生视图不会走到这里
        scheduleEnsure(componentId, dimValue, request)
      }
    } catch (error) {
      if (!isCurrent(componentId, request, dimValue)) return
      chartStates.value[componentId] = { loading: false, data: null }
      insightStates.value[componentId] = {
        loading: false, data: null, error: true, status: 'FAILED',
        taskId: null, attempt: null, source: null, canEnsure: false, canRetry: false, timedOut: false,
      }
      onError(error)
    }
  }

  // 洞察重试也刷新图表，继续使用当前条件，保证同一版本。
  function loadInsight(componentId: number) {
    return loadChartData(componentId, scopes.get(componentId))
  }

  return { chartStates, insightStates, loadChartData, loadInsight, ensureInsight, retryInsight }
}
