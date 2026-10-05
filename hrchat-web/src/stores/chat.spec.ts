import { describe, expect, it } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useChatStore } from './chat'

describe('stores/chat', () => {
  it('会话选择与消息轮次管理', () => {
    setActivePinia(createPinia())
    const store = useChatStore()

    const userId = store.pushUserTurn('研发中心在职人数')
    expect(userId).toBeTruthy()
    const assistantId = store.pushAssistantTurn('研发中心在职人数')
    expect(store.turns).toHaveLength(2)
    expect(store.turns[0].role).toBe('user')
    expect(store.turns[0].question).toBe('研发中心在职人数')
    expect(store.turns[1].question).toBe('研发中心在职人数')

    store.updateTurn(assistantId, { state: 'streaming', streamingText: '正在解析' })
    expect(store.turns.find((t) => t.id === assistantId)?.streamingText).toBe('正在解析')

    store.clearTurns()
    expect(store.turns).toHaveLength(0)
  })

  it('asking 状态与会话列表写入', () => {
    setActivePinia(createPinia())
    const store = useChatStore()

    store.asking = true
    expect(store.asking).toBe(true)

    store.setSessions([
      { id: 1, title: '会话一', status: 1, lastActiveAt: '2026-09-28T08:00:00', pinned: false, createdAt: '2026-09-28T08:00:00' },
    ])
    store.selectSession(1)
    expect(store.currentSessionId).toBe(1)
    expect(store.sessions).toHaveLength(1)
  })
})
