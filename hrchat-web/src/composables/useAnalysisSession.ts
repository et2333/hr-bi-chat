import { onBeforeUnmount, ref, shallowRef } from 'vue'
import { cancelAnalysis, getAnalysisTask, streamAnalysis } from '@/api/attribution'
import type { AnalysisEvent, AnalysisMode, AnalysisPeriod, AnalysisTask } from '@/api/attribution'
import { TENANT_NO_KEY, USER_NO_KEY } from '@/api/http'

/**
 * 分析任务的来源适配：ASK 与 REPORT 共用同一套任务生命周期、进度订阅与恢复语义。
 * 仅任务 ID 会写入会话存储；其余展示数据一律由服务端重新鉴权后返回。
 */
export interface AnalysisTaskSource<C, T> {
  /**
   * 解析会话标识（含租户/用户与来源），仅用于存放任务 ID。
   * 来源尚未就绪（如初始 dataVersion=0、空 capability）时返回 null，禁止坐实空键。
   */
  resolveKey: () => string | null
  /** 读取预填上下文（指标/组织/本期基期/来源版本）。 */
  loadContext: () => Promise<C>
  /** 用户确认后创建任务；不确定结果重试时复用同一幂等键。 */
  startTask: (baseline: AnalysisPeriod, mode: AnalysisMode, idempotencyKey: string) => Promise<AnalysisTask<T>>
  /** 会话代次重置（显式切换来源）时回调：调用方据此丢弃已冻结的来源副本。 */
  onReset?: () => void
}

/** Only task IDs survive navigation. Every restored payload is re-authorized by Java. */
export function useAnalysisSession<C, T = C>(source: AnalysisTaskSource<C, T>) {
  const context = shallowRef<C | null>(null)
  const task = shallowRef<AnalysisTask<T> | null>(null)
  const stages = ref<string[]>([])
  const busy = ref(false)
  const cancelling = ref(false)
  const disconnected = ref(false)
  const error = ref('')
  let lastSeq = 0
  let alive = true
  let stream: ReturnType<typeof streamAnalysis> | undefined
  let generation = 0
  let idempotencyKey = ''
  let requestSignature = ''
  const identity = [localStorage.getItem(TENANT_NO_KEY) || 't01', localStorage.getItem(USER_NO_KEY) || 'hr01']
  const isCurrent = () => alive && identity[0] === (localStorage.getItem(TENANT_NO_KEY) || 't01')
    && identity[1] === (localStorage.getItem(USER_NO_KEY) || 'hr01')
  // 会话键在首次取得合法来源时冻结，之后不随运行中请求重新解析；来源无效时保持 null 以便后续重试。
  let sessionKey: string | null = null
  const currentKey = (): string | null => (sessionKey ??= source.resolveKey() || null)
  const saveId = (id: string) => {
    const key = currentKey()
    if (!key) return
    try { sessionStorage.setItem(key, id) } catch { /* storage may be disabled */ }
  }
  const clearId = () => {
    const key = currentKey()
    if (!key) return
    try { sessionStorage.removeItem(key) } catch { /* optional cache */ }
  }
  const readId = (): string | null => {
    const key = currentKey()
    if (!key) return null
    try { return sessionStorage.getItem(key) } catch { return null }
  }
  const stageLabels: Record<string, string> = {
    planning: '规划分析步骤', fetching: '获取授权数据', fetching_evidence: '获取授权数据',
    analysing: '整理变化与证据', analyzing: '整理变化与证据', reviewing: '复核结论与证据',
    supplementing: '补充核查证据', verifying: '核对总分一致性', completed: '分析完成',
  }

  function stopStream() { generation++; stream?.close(); stream = undefined }
  /** 会话代次：来源/恢复键/准备/启动/读取/SSE 共用同一代次，只接受当前代次响应。 */
  const currentGeneration = () => generation
  function handleFrame(frame: AnalysisEvent) {
    if (!isCurrent() || frame.seq <= lastSeq) return
    lastSeq = frame.seq
    if (['ANALYSIS_STAGE', 'PLAN_UPDATE', 'TOOL_CALL_START', 'TOOL_CALL_END'].includes(frame.event)) {
      if (frame.event === 'TOOL_CALL_END' && !frame.payload.message) return
      const label = String(frame.payload.message ?? frame.payload.label ?? stageLabels[String(frame.payload.stage)] ?? '正在分析')
      if (stages.value.at(-1) !== label) stages.value.push(label)
    }
    if (frame.event === 'ERROR') {
      // A failure may represent revoked authorization. Hide cached result immediately.
      if (task.value) task.value = { ...task.value, result: null }
      error.value = String(frame.payload.message ?? '分析未完成，请恢复查看任务状态')
    }
  }

  function applyTask(next: AnalysisTask<T>) {
    if (!isCurrent()) return
    for (const frame of next.events ?? []) handleFrame(frame)
    // Replayed ERROR can precede an authorised PARTIAL snapshot with verified facts.
    task.value = next
    if (next.status !== 'RUNNING') disconnected.value = false
  }

  async function readTask(taskId: string): Promise<boolean> {
    const gen = generation
    try {
      const response = await getAnalysisTask(taskId)
      if (!isCurrent() || gen !== currentGeneration()) return false
      applyTask(response.data as AnalysisTask<T>)
      return true
    } catch (reason) {
      if (!isCurrent() || gen !== currentGeneration()) return false
      // No stale authorized result is kept as an error fallback.
      task.value = null
      stages.value = []
      error.value = reason instanceof Error ? reason.message : '任务已失效或当前权限不允许读取，请重新问数'
      disconnected.value = true
      return false
    }
  }

  function subscribe(taskId: string) {
    stopStream()
    const version = generation
    disconnected.value = false
    stream = streamAnalysis(taskId, lastSeq, (frame) => {
      if (version === generation) handleFrame(frame)
    }, (text) => {
      if (isCurrent() && version === generation) {
        error.value = text
        disconnected.value = true
        if (task.value) task.value = { ...task.value, result: null }
      }
    })
    void stream.done.then(async () => {
      if (!isCurrent() || version !== generation) return
      // Final events are followed by an authorized snapshot; never start another task.
      const read = await readTask(taskId)
      if (!isCurrent() || version !== generation) return
      if (read && task.value?.status !== 'RUNNING') error.value = ''
      else if (read) disconnected.value = true
    })
  }

  async function prepare() {
    if (busy.value) return
    busy.value = true
    error.value = ''
    const gen = generation
    try {
      const response = await source.loadContext()
      // 只接受当前会话代次的响应：显式 resetSession 后旧 prepare 的返回必须丢弃。
      if (isCurrent() && gen === currentGeneration()) context.value = response
    } catch (reason) {
      if (isCurrent() && gen === currentGeneration()) {
        context.value = null
        error.value = reason instanceof Error ? reason.message : '无法读取分析范围，请重新问数'
      }
    } finally { if (isCurrent() && gen === currentGeneration()) busy.value = false }
  }

  async function start(baseline: AnalysisPeriod, mode: AnalysisMode = 'dual') {
    if (busy.value || task.value?.status === 'RUNNING' || !context.value) return
    busy.value = true
    error.value = ''
    const gen = generation
    const signature = JSON.stringify({ baseline, mode })
    if (requestSignature !== signature || !idempotencyKey) {
      idempotencyKey = crypto.randomUUID()
      requestSignature = signature
    }
    try {
      const response = await source.startTask(baseline, mode, idempotencyKey)
      if (!isCurrent() || gen !== currentGeneration()) return
      // 本代次请求已完成：先复位 busy 再订阅——订阅会推进会话代次，不能等 finally 再判断。
      busy.value = false
      lastSeq = 0
      stages.value = []
      applyTask(response)
      saveId(response.taskId)
      if (response.status === 'RUNNING') subscribe(response.taskId)
    } catch (reason) {
      if (isCurrent() && gen === currentGeneration()) {
        error.value = reason instanceof Error ? reason.message : '无法启动分析，请重试'
      }
    } finally { if (isCurrent() && gen === currentGeneration()) busy.value = false }
  }

  async function restore() {
    const id = readId()
    if (!id || busy.value) return
    busy.value = true
    error.value = ''
    stopStream()
    const gen = generation
    const restored = await readTask(id)
    if (isCurrent() && gen === currentGeneration()) {
      if (restored && task.value?.status === 'RUNNING') subscribe(id)
      busy.value = false
    }
  }

  async function cancel() {
    if (!task.value || task.value.status !== 'RUNNING' || cancelling.value) return
    cancelling.value = true
    error.value = ''
    const gen = generation
    try {
      const response = await cancelAnalysis(task.value.taskId)
      if (isCurrent() && gen === currentGeneration()) {
        stopStream(); applyTask(response.data as AnalysisTask<T>)
      }
    } catch (reason) {
      if (isCurrent() && gen === currentGeneration()) {
        error.value = reason instanceof Error ? reason.message : '取消未确认，请恢复查看任务状态'
      }
    } finally { if (isCurrent() && gen === currentGeneration()) cancelling.value = false }
  }

  function chooseAgain() {
    if (task.value?.status === 'RUNNING') return
    stopStream()
    task.value = null
    context.value = null
    stages.value = []
    disconnected.value = false
    lastSeq = 0
    idempotencyKey = ''
    requestSignature = ''
    clearId()
    void prepare()
  }

  /**
   * 显式切换来源/会话：丢弃当前展示并解除键冻结，下一次访问按新来源重新解析。
   * 运行中任务不在此处取消——调用方需先确认不是运行中，或显式取消。
   */
  function resetSession() {
    stopStream()
    context.value = null
    task.value = null
    stages.value = []
    disconnected.value = false
    error.value = ''
    lastSeq = 0
    idempotencyKey = ''
    requestSignature = ''
    sessionKey = null
    busy.value = false
    // 通知调用方丢弃已冻结的来源副本，下一次访问按新来源重新解析。
    source.onReset?.()
  }

  onBeforeUnmount(() => { alive = false; stopStream() })
  return {
    context, task, stages, busy, cancelling, disconnected, error,
    prepare, start, restore, cancel, chooseAgain, resetSession,
  }
}
