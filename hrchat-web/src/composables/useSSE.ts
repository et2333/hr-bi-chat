/**
 * SSE 组合式函数（S6 增强版）
 *
 * 能力：
 * 1. 事件流消费：fetch + ReadableStream 按空行切分事件块；
 * 2. 乱序缓冲重排：按帧内 seq 单调递增顺序回调，乱序帧暂存等待；
 * 3. 断线重连：流中断且未到终态时按 maxRetries 重连，携带 Last-Event-ID；
 * 4. 支持 GET / POST（问句与澄清为 POST 流）。
 *
 * 回调帧为 { seq, event, ts, payload }（后端 SSE 契约，见接口文档 2.2.11）。
 */

/** 解析后的单个 SSE 事件 */
export interface SSEEvent {
  data: string
  event?: string
  id?: string
}

/** 帧（event + data 内 JSON 统一结构） */
export interface SSEFrame {
  seq: number
  event: string
  ts: string
  payload: Record<string, unknown>
}

export interface UseSSEOptions {
  headers?: Record<string, string>
  method?: 'GET' | 'POST'
  body?: unknown
  /** 断线重连次数（默认 2；0 表示不重连） */
  maxRetries?: number
}

export interface UseSSEResult {
  close: () => void
}

const TERMINAL_EVENTS = new Set(['ANSWER_DONE', 'ERROR'])

export function useSSE(
  url: string,
  onFrame: (frame: SSEFrame) => void,
  options: UseSSEOptions = {},
): UseSSEResult {
  const controller = new AbortController()
  const maxRetries = options.maxRetries ?? 2
  /** 已回调的最大 seq（Last-Event-ID 依据） */
  let lastSeq = 0
  /** 乱序缓冲：seq → frame */
  const pending = new Map<number, SSEFrame>()
  let retries = 0
  let closed = false

  const flushPending = (untilSeq: number) => {
    let next = lastSeq + 1
    while (pending.has(next) && next <= untilSeq) {
      const frame = pending.get(next)!
      pending.delete(next)
      lastSeq = next
      onFrame(frame)
      next = lastSeq + 1
    }
  }

  const handleFrame = (event: SSEEvent) => {
    if (closed || !event.data.trim()) return
    let parsed: Partial<SSEFrame> | null = null
    try {
      parsed = JSON.parse(event.data) as Partial<SSEFrame>
    } catch {
      return
    }
    const seq = Number(parsed.seq ?? -1)
    const frame: SSEFrame = {
      seq: Number.isNaN(seq) ? -1 : seq,
      event: parsed.event ?? event.event ?? 'MESSAGE',
      ts: parsed.ts ?? '',
      payload: (parsed.payload as Record<string, unknown>) ?? {},
    }
    // 心跳（seq=-1）直接放行
    if (frame.seq <= 0) {
      onFrame(frame)
      return
    }
    if (frame.seq === lastSeq + 1) {
      lastSeq = frame.seq
      onFrame(frame)
      flushPending(frame.seq)
    } else if (frame.seq > lastSeq + 1) {
      pending.set(frame.seq, frame)
    }
    // 终态事件到达：重置重连计数（不主动冲刷，等待前置乱序帧补齐后自然推进）
    if (TERMINAL_EVENTS.has(frame.event)) {
      retries = 0
    }
  }

  const run = async (lastEventId: number) => {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      ...options.headers,
    }
    if (lastEventId > 0) headers['Last-Event-ID'] = String(lastEventId)
    const init: RequestInit = {
      method: options.method ?? 'GET',
      headers,
      signal: controller.signal,
    }
    if (options.body !== undefined && options.method === 'POST') {
      init.body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
    }

    const res = await fetch(url, init)
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
        const rawEvent = buffer.slice(0, sepIndex)
        buffer = buffer.slice(sepIndex + 2)
        const parsed = parseSSEEvent(rawEvent)
        if (parsed) handleFrame(parsed)
      }
    }
    // 正常断流：冲刷剩余缓冲帧
    flushPending(Number.MAX_SAFE_INTEGER)
  }

  const start = () => {
    run(lastSeq).catch((err: unknown) => {
      if (closed || (err instanceof DOMException && err.name === 'AbortError')) return
      if (retries < maxRetries) {
        retries += 1
        start()
      } else {
        onFrame({
          seq: lastSeq + 1,
          event: 'ERROR',
          ts: new Date().toISOString(),
          payload: {
            code: 'HRS-3001',
            message: '连接中断，请稍后重试',
            recoverable: true,
          },
        })
      }
    })
  }

  start()

  /** 中止连接 */
  function close() {
    closed = true
    controller.abort()
  }

  return { close }
}

/** 解析单个 SSE 事件块（data 多行以 \n 拼接，忽略注释行） */
export function parseSSEEvent(raw: string): SSEEvent | null {
  const dataLines: string[] = []
  let event: string | undefined
  let id: string | undefined

  for (const line of raw.split('\n')) {
    if (line.startsWith(':')) continue // 注释/心跳行
    const colonIdx = line.indexOf(':')
    const field = colonIdx === -1 ? line : line.slice(0, colonIdx)
    const value = colonIdx === -1 ? '' : line.slice(colonIdx + 1).replace(/^ /, '')
    if (field === 'data') dataLines.push(value)
    else if (field === 'event') event = value
    else if (field === 'id') id = value
  }

  if (dataLines.length === 0) return null
  return { data: dataLines.join('\n'), event, id }
}

export default useSSE
