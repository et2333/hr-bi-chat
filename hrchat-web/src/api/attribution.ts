import { get, post, http, TENANT_NO_KEY, TENANT_SWITCH_REASON_KEY, USER_NO_KEY } from './http'
import type { ApiResponse } from './types'

/** All API periods use an exclusive end; the form displays inclusive dates. */
export interface AnalysisPeriod { start: string; end: string }
export interface AnalysisContext {
  sourceAskId: string
  metricCode: 'leave_count'
  metricName: string
  unit: string
  scopeLabel: string
  currentPeriod: AnalysisPeriod
  suggestedBaselinePeriod: AnalysisPeriod | null
}
export type AnalysisStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
export type AnalysisMode = 'deterministic' | 'dual'
export interface Contribution {
  org_id: number
  org_name: string
  current_count: number
  baseline_count: number
  delta: number
}
export interface AnalysisResult {
  status: AnalysisStatus
  mode: string
  summary: { current_total: number; baseline_total: number; delta: number; closure_verified: boolean } | null
  contributions: Contribution[]
  facts: Record<string, Record<string, string | number>>
  claims: Array<{ claim_id: string; kind: string; fact_id?: string; hypothesis?: string; next_check?: string; evidence_ids: string[] }>
  evidence: Array<{ evidence_id: string; status: string; metric_version: string; data_version: string; query: { detail: string }; daily?: Array<{ period: string; date: string; count: number }> }>
  data_quality: string[]
  supplement_assessment?: { claim_id: string; evidence_id: string; fact_ids: string[]; conclusion: 'descriptive_only' | 'insufficient' } | null
  unresolved: string[]
  disclaimer: string
  usage: { elapsed_ms?: number; [key: string]: unknown }
}
export interface AnalysisEvent {
  seq: number
  event: string
  payload: Record<string, unknown>
}
export interface AnalysisTask {
  taskId: string
  sourceAskId: string
  status: AnalysisStatus
  context: AnalysisContext & { baselinePeriod?: AnalysisPeriod }
  result: AnalysisResult | null
  events: AnalysisEvent[]
}

export function getAnalysisContext(askId: string) {
  return get<ApiResponse<AnalysisContext>>(`/chat/asks/${encodeURIComponent(askId)}/attribution/context`)
}
export function startAnalysis(askId: string, baselinePeriod: AnalysisPeriod, idempotencyKey: string, mode: AnalysisMode = 'dual') {
  return post<ApiResponse<AnalysisTask>>(`/chat/asks/${encodeURIComponent(askId)}/attribution`,
    { baselinePeriod, mode }, { headers: { 'X-Idempotency-Key': idempotencyKey } })
}
export function getAnalysisTask(taskId: string) {
  return get<ApiResponse<AnalysisTask>>(`/chat/attribution/tasks/${encodeURIComponent(taskId)}`)
}
export function cancelAnalysis(taskId: string) {
  return post<ApiResponse<AnalysisTask>>(`/chat/attribution/tasks/${encodeURIComponent(taskId)}/cancel`)
}

/** Reconnect by task ID and sequence; this endpoint never creates an analysis. */
export function streamAnalysis(taskId: string, afterSeq: number, onFrame: (frame: AnalysisEvent) => void,
  onError: (message: string) => void) {
  const controller = new AbortController()
  const switchReason = sessionStorage.getItem(TENANT_SWITCH_REASON_KEY)
  const done = (async () => {
    const response = await fetch(`${http.defaults.baseURL ?? '/api/v1'}/chat/attribution/tasks/${encodeURIComponent(taskId)}/events`, {
      headers: {
        Accept: 'text/event-stream',
        'X-User-No': localStorage.getItem(USER_NO_KEY) || 'hr01',
        'X-Tenant-No': localStorage.getItem(TENANT_NO_KEY) || 't01',
        'Last-Event-ID': String(afterSeq),
        ...(switchReason ? { 'X-Tenant-Switch-Reason': switchReason } : {}),
      },
      signal: controller.signal,
    })
    if (!response.ok || !response.body) throw new Error('无法读取分析进度，请恢复查看以重新核验权限')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      while (true) {
        const chunk = await reader.read()
        if (chunk.done) break
        buffer += decoder.decode(chunk.value, { stream: true })
        // Normalize the complete buffer so a CR/LF pair split across chunks is safe.
        buffer = buffer.replace(/\r\n/g, '\n')
        let separator: number
        while ((separator = buffer.indexOf('\n\n')) >= 0) {
          const raw = buffer.slice(0, separator)
          buffer = buffer.slice(separator + 2)
          const data = raw.split('\n').filter(line => line.startsWith('data:'))
            .map(line => line.slice(5).replace(/^ /, '')).join('\n')
          if (!data) continue
          let parsed: unknown
          try { parsed = JSON.parse(data) } catch { continue }
          if (!parsed || typeof parsed !== 'object') continue
          const frame = parsed as AnalysisEvent
          if (typeof frame.event === 'string' && Number.isInteger(frame.seq)
            && frame.seq > afterSeq && frame.payload && typeof frame.payload === 'object') onFrame(frame)
        }
      }
    } finally { reader.releaseLock() }
  })().catch((error: unknown) => {
    if (!controller.signal.aborted) onError(error instanceof Error ? error.message : '分析连接中断')
  })
  return { close: () => controller.abort(), done }
}
