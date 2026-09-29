import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { useTypewriter } from './useTypewriter'

describe('composables/useTypewriter', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('目标文本按拍逐字揭示，最终完整输出', async () => {
    const source = ref('')
    const displayed = useTypewriter(source, 10)

    source.value = '你好AB'
    await nextTick()
    expect(displayed.value).toBe('')

    vi.advanceTimersByTime(10)
    expect(displayed.value).toBe('你')
    vi.advanceTimersByTime(10)
    expect(displayed.value).toBe('你好')
    vi.advanceTimersByTime(20)
    expect(displayed.value).toBe('你好AB')
  })

  it('增量到达时继续在原文后逐字追加', async () => {
    const source = ref('')
    const displayed = useTypewriter(source, 10)

    source.value = '你好'
    await nextTick()
    vi.advanceTimersByTime(20)
    expect(displayed.value).toBe('你好')

    source.value = '你好世界'
    await nextTick()
    vi.advanceTimersByTime(10)
    expect(displayed.value).toBe('你好世')
    vi.advanceTimersByTime(10)
    expect(displayed.value).toBe('你好世界')
  })

  it('source 清空时立即复位且不再继续动画', async () => {
    const source = ref('一句完整的结论')
    const displayed = useTypewriter(source, 10)
    await nextTick()
    expect(displayed.value).toBe('')
    vi.advanceTimersByTime(100)
    expect(displayed.value).toBe('一句完整的结论')

    source.value = ''
    await nextTick()
    expect(displayed.value).toBe('')
    vi.advanceTimersByTime(100)
    expect(displayed.value).toBe('')
  })
})
