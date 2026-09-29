// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseSSEEvent, useSSE } from './useSSE'

function sseBlock(seq: number, event: string, payload: Record<string, unknown>): string {
  return `event: ${event}\ndata: ${JSON.stringify({ seq, event, ts: 't', payload })}\n\n`
}

function mockFetch(frames: string[]) {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const f of frames) controller.enqueue(encoder.encode(f))
      controller.close()
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, { status: 200 })))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseSSEEvent', () => {
  it('解析 data 与 event 字段', () => {
    const raw = 'event: MESSAGE_DELTA\ndata: {"a":1}\n\n'
    const parsed = parseSSEEvent(raw)
    expect(parsed?.event).toBe('MESSAGE_DELTA')
    expect(parsed?.data).toBe('{"a":1}')
  })

  it('忽略注释行与空块', () => {
    expect(parseSSEEvent(': ping')).toBeNull()
    expect(parseSSEEvent('')).toBeNull()
  })

  it('多行 data 以换行拼接', () => {
    const raw = 'data: line1\ndata: line2\n\n'
    expect(parseSSEEvent(raw)?.data).toBe('line1\nline2')
  })
})

describe('useSSE 乱序缓冲重排', () => {
  it('乱序到达的帧按 seq 顺序回调', async () => {
    mockFetch([sseBlock(2, 'ANSWER_DONE', {}), sseBlock(1, 'MESSAGE_DELTA', { delta: 'x' })])
    const received: number[] = []
    useSSE(
      'http://x/stream',
      (frame) => received.push(frame.seq),
      { maxRetries: 0 },
    )
    await new Promise((r) => setTimeout(r, 50))
    expect(received).toEqual([1, 2])
  })

  it('心跳帧（seq=-1）立即放行且不破坏序号', async () => {
    mockFetch([sseBlock(-1, 'HEARTBEAT', {}), sseBlock(1, 'ANSWER_DONE', {})])
    const received: number[] = []
    useSSE('http://x/stream', (frame) => received.push(frame.seq), { maxRetries: 0 })
    await new Promise((r) => setTimeout(r, 50))
    expect(received).toEqual([-1, 1])
  })
})
