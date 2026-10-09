import { onBeforeUnmount, ref } from 'vue'
import { cancelAnalysis, getAnalysisContext, getAnalysisTask, startAnalysis, streamAnalysis } from '@/api/attribution'
import type { AnalysisContext, AnalysisEvent, AnalysisMode, AnalysisPeriod, AnalysisTask } from '@/api/attribution'
import { TENANT_NO_KEY, USER_NO_KEY } from '@/api/http'

/** Only task IDs survive navigation. Every restored payload is re-authorized by Java. */
export function useAttribution(sourceAskId: string) {
  const context = ref<AnalysisContext | null>(null)
  const task = ref<AnalysisTask | null>(null)
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
  const storageKey = `hrchat_analysis:${JSON.stringify([...identity, sourceAskId])}`
  const isCurrent = () => alive && identity[0] === (localStorage.getItem(TENANT_NO_KEY) || 't01')
    && identity[1] === (localStorage.getItem(USER_NO_KEY) || 'hr01')
  const saveId = (id: string) => { try { sessionStorage.setItem(storageKey, id) } catch { /* storage may be disabled */ } }
  const clearId = () => { try { sessionStorage.removeItem(storageKey) } catch { /* optional cache */ } }
  const stageLabels: Record<string, string> = {
    planning: '规划分析步骤', fetching: '获取授权数据', fetching_evidence: '获取授权数据',
    analysing: '整理变化与证据', analyzing: '整理变化与证据', reviewing: '复核结论与证据',
    supplementing: '补充核查证据', verifying: '核对总分一致性', completed: '分析完成',
  }

  function stopStream() { generation++; stream?.close(); stream = undefined }
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

  function applyTask(next: AnalysisTask) {
    if (!isCurrent()) return
    for (const frame of next.events ?? []) handleFrame(frame)
    // Replayed ERROR can precede an authorised PARTIAL snapshot with verified facts.
    task.value = next
    if (next.status !== 'RUNNING') disconnected.value = false
  }

  async function readTask(taskId: string): Promise<boolean> {
    try {
      const response = await getAnalysisTask(taskId)
      if (!isCurrent()) return false
      applyTask(response.data)
      return true
    } catch (reason) {
      if (!isCurrent()) return false
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
    try {
      const response = await getAnalysisContext(sourceAskId)
      if (isCurrent()) context.value = response.data
    } catch (reason) {
      if (isCurrent()) {
        context.value = null
        error.value = reason instanceof Error ? reason.message : '无法读取分析范围，请重新问数'
      }
    } finally { if (isCurrent()) busy.value = false }
  }

  async function start(baseline: AnalysisPeriod, mode: AnalysisMode = 'dual') {
    if (busy.value || task.value?.status === 'RUNNING' || !context.value) return
    busy.value = true
    error.value = ''
    const signature = JSON.stringify({ baseline, mode })
    if (requestSignature !== signature || !idempotencyKey) {
      idempotencyKey = crypto.randomUUID()
      requestSignature = signature
    }
    try {
      const response = await startAnalysis(sourceAskId, baseline, idempotencyKey, mode)
      if (!isCurrent()) return
      lastSeq = 0
      stages.value = []
      applyTask(response.data)
      saveId(response.data.taskId)
      if (response.data.status === 'RUNNING') subscribe(response.data.taskId)
    } catch (reason) {
      if (isCurrent()) error.value = reason instanceof Error ? reason.message : '无法启动分析，请重试'
    } finally { if (isCurrent()) busy.value = false }
  }

  async function restore() {
    let id: string | null = null
    try { id = sessionStorage.getItem(storageKey) } catch { /* optional cache */ }
    if (!id || busy.value) return
    busy.value = true
    error.value = ''
    stopStream()
    const restored = await readTask(id)
    if (isCurrent()) {
      if (restored && task.value?.status === 'RUNNING') subscribe(id)
      busy.value = false
    }
  }

  async function cancel() {
    if (!task.value || task.value.status !== 'RUNNING' || cancelling.value) return
    cancelling.value = true
    error.value = ''
    try {
      const response = await cancelAnalysis(task.value.taskId)
      if (isCurrent()) { stopStream(); applyTask(response.data) }
    } catch (reason) {
      if (isCurrent()) error.value = reason instanceof Error ? reason.message : '取消未确认，请恢复查看任务状态'
    } finally { if (isCurrent()) cancelling.value = false }
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

  onBeforeUnmount(() => { alive = false; stopStream() })
  return { context, task, stages, busy, cancelling, disconnected, error, prepare, start, restore, cancel, chooseAgain }
}
