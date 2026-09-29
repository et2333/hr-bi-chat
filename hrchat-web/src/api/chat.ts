import { get, post, del as deleteReq, http, TENANT_NO_KEY, USER_NO_KEY } from './http'
import type {
  ApiResponse,
  AnswerPayload,
  ChatSession,
  ClarifyAnswerRequest,
  FeedbackRequest,
  PageResult,
  SqlView,
  TableData,
  TurnView,
} from './types'

/** 提问请求体 */
export interface AskRequest {
  question: string
  mode?: 'STREAM' | 'SYNC'
}

// ---------------- 会话 ----------------

export function createSession(title?: string) {
  return post<ApiResponse<ChatSession>>('/chat/sessions', { title })
}

export function listSessions(page = 1, size = 20) {
  return get<ApiResponse<PageResult<ChatSession>>>('/chat/sessions', { params: { page, size } })
}

export function renameSession(sessionId: number, title: string) {
  return post<ApiResponse<ChatSession>>(`/chat/sessions/${sessionId}`, { title })
}

export function deleteSession(sessionId: number) {
  return deleteReq<ApiResponse<void>>(`/chat/sessions/${sessionId}`)
}

export function listTurns(sessionId: number) {
  return get<ApiResponse<TurnView[]>>(`/chat/sessions/${sessionId}/turns`)
}

// ---------------- 问答（SSE 流式） ----------------

/**
 * 提交问句并以 SSE 消费事件流。
 * 返回 close 用于中断；事件回调收到的 data 已解析为 JSON。
 */
export function askStream(
  sessionId: number,
  request: AskRequest,
  onData: (data: Record<string, unknown>) => void,
) {
  return streamRequest(`/chat/sessions/${sessionId}/asks`, request, onData)
}

/** 澄清应答续流（POST …/asks/{askId}/clarifications） */
export function clarifyStream(
  sessionId: number,
  askId: string,
  answers: ClarifyAnswerRequest['answers'],
  onData: (data: Record<string, unknown>) => void,
) {
  return streamRequest(`/chat/sessions/${sessionId}/asks/${askId}/clarifications`, { answers }, onData)
}

/** 通用 SSE 流式请求（fetch + ReadableStream 解析，事件帧 data 为 JSON） */
export function streamRequest(
  url: string,
  body: unknown,
  onData: (data: Record<string, unknown>) => void,
  headers: Record<string, string> = {},
) {
  const controller = new AbortController()
  const userNo = localStorage.getItem(USER_NO_KEY) || 'hr01'
  const tenantNo = localStorage.getItem(TENANT_NO_KEY) || 't01'

  const run = async () => {
    const res = await fetch(`${http.defaults.baseURL ?? '/api/v1'}${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'X-User-No': userNo,
        'X-Tenant-No': tenantNo,
        ...headers,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
    if (!res.ok || !res.body) {
      throw new Error(`SSE 请求失败: HTTP ${res.status}`)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let sepIndex: number
      while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, sepIndex)
        buffer = buffer.slice(sepIndex + 2)
        const dataLine = raw
          .split('\n')
          .filter((l) => l.startsWith('data:'))
          .map((l) => l.slice(5).replace(/^ /, ''))
          .join('\n')
        if (!dataLine || dataLine.startsWith(':')) continue
        try {
          onData(JSON.parse(dataLine) as Record<string, unknown>)
        } catch {
          // 非 JSON 心跳等直接忽略
        }
      }
    }
  }

  /** 流结束（成功或异常）后 resolve */
  const done = run().then(
    () => undefined,
    (err: unknown) => {
      if (err instanceof DOMException && err.name === 'AbortError') return undefined
      onData({
        event: 'ERROR',
        payload: {
          code: 'HRS-3001',
          message: String(err instanceof Error ? err.message : err),
          recoverable: true,
        },
      })
      return undefined
    },
  )

  return { close: () => controller.abort(), done }
}

// ---------------- 任务状态 / SQL / 表格 / 反馈 ----------------

export function getAsk(askId: string) {
  return get<ApiResponse<AnswerPayload>>(`/chat/asks/${askId}`)
}

export function getSql(askId: string) {
  return get<ApiResponse<SqlView>>(`/chat/asks/${askId}/sql`)
}

export function getTable(askId: string, page = 1, size = 20) {
  return get<ApiResponse<TableData>>(`/chat/asks/${askId}/table`, { params: { page, size } })
}

export function feedback(askId: string, request: FeedbackRequest) {
  return post<ApiResponse<void>>(`/chat/asks/${askId}/feedback`, request)
}
