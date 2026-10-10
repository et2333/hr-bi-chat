import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { AnswerPayload, ClarifyQuestions, ChatSession, InteractionEntry, QueryUsage } from '@/api/types'

/** 当前会话消息（assistant 为 AnswerCard 数据源） */
export interface ChatTurn {
  id: string
  role: 'user' | 'assistant'
  question?: string
  state: 'loading' | 'streaming' | 'clarifying' | 'completed' | 'failed' | 'forbidden' | 'timeout'
  payload?: AnswerPayload | null
  clarify?: ClarifyQuestions | null
  interactionHistory?: InteractionEntry[]
  usage?: QueryUsage
  progress?: Array<{ stage: string; status: string; message?: string; elapsed_ms?: number }>
  /** 答案正文（SUMMARIZING 实际内容，逐字打字机渲染） */
  streamingText?: string
  /** 瞬时进度提示（如“正在解析您的问句…”），不与正文拼接 */
  hint?: string
  /** 打字机是否已追上正文（ANSWER_DONE 后据此决定何时切 completed） */
  typed?: boolean
  errorTitle?: string
  errorMessage?: string
}

/** 会话列表 state */
export const useChatStore = defineStore('chat', () => {
  // ---- 会话 ----
  const sessions = ref<ChatSession[]>([])
  const currentSessionId = ref<number | null>(null)
  const loadingSessions = ref(false)

  function setSessions(list: ChatSession[]) {
    sessions.value = list
  }

  function selectSession(id: number | null) {
    currentSessionId.value = id
  }

  // ---- 消息流 ----
  const turns = ref<ChatTurn[]>([])
  const asking = ref(false)

  function pushUserTurn(question: string): string {
    const id = `u_${Date.now()}`
    turns.value.push({ id, role: 'user', question, state: 'completed' })
    return id
  }

  function pushAssistantTurn(question?: string): string {
    const id = `a_${Date.now()}`
    turns.value.push({
      id,
      role: 'assistant',
      question: question?.trim() || undefined,
      state: 'loading',
      streamingText: '',
    })
    return id
  }

  function updateTurn(id: string, patch: Partial<ChatTurn>) {
    const t = turns.value.find((x) => x.id === id)
    if (t) Object.assign(t, patch)
  }

  function clearTurns() {
    turns.value = []
  }

  return {
    sessions,
    currentSessionId,
    loadingSessions,
    setSessions,
    selectSession,
    turns,
    asking,
    pushUserTurn,
    pushAssistantTurn,
    updateTurn,
    clearTurns,
  }
})
