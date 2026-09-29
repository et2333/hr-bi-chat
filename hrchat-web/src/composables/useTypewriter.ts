import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/**
 * 打字机：把流式文本逐字揭示（单 UI 载体，不改后端协议）。
 *
 * - source 为“追加”增长时按 interval 逐字吐出；落后较多时每拍多吐字，避免长文等待过久
 * - source 被清空/换成非追加文本（新问题、重置）时立即同步，不播放动画
 * - 组件卸载自动清理定时器，防止切会话/连续发问后的错时渲染
 *
 * @param source 完整目标文本（通常为流式累积的答案正文）
 * @param intervalMs 每拍间隔（ms）
 */
export function useTypewriter(source: Ref<string>, intervalMs = 28) {
  /** 已揭示文本 */
  const displayed = ref('')
  let timer: ReturnType<typeof setInterval> | null = null

  function clearTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 是否已揭示完毕 */
  function isDone() {
    return displayed.value === source.value
  }

  function tick() {
    const target = source.value
    const shown = displayed.value
    // 目标始终以已揭示文本为前缀（同一答案的增量到达）；否则视为重置，直接同步
    if (!target.startsWith(shown)) {
      displayed.value = target
      clearTimer()
      return
    }
    if (shown.length >= target.length) {
      clearTimer()
      return
    }
    // 按码点切分，避免截断 emoji/代理对
    const targetChars = Array.from(target)
    const shownCount = Array.from(shown).length
    const lag = targetChars.length - shownCount
    const step = lag > 30 ? Math.ceil(lag / 12) : 1
    displayed.value = targetChars.slice(0, shownCount + step).join('')
    if (displayed.value.length >= target.length) {
      clearTimer()
    }
  }

  watch(
    source,
    (val) => {
      if (!val) {
        displayed.value = ''
        clearTimer()
        return
      }
      // 非追加变化（新一问的正文与上一段无前缀关系）：直接展示新文本
      if (displayed.value && !val.startsWith(displayed.value)) {
        displayed.value = val
        clearTimer()
        return
      }
      if (!isDone() && timer === null) {
        timer = setInterval(tick, intervalMs)
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(clearTimer)

  return displayed
}
