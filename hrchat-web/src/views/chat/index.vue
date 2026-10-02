<template>
  <div class="chat-layout">
    <!-- 左侧会话列表 -->
    <div class="session-panel">
      <div class="session-head">
        <span>会话</span>
        <a-button type="primary" size="small" @click="createNewSession">
          <template #icon><PlusOutlined /></template>
        </a-button>
      </div>
      <a-list
        :loading="chat.loadingSessions"
        :data-source="chat.sessions"
        class="session-list"
      >
        <template #renderItem="{ item }">
          <a-list-item
            class="session-item"
            :class="{ active: item.id === chat.currentSessionId }"
            @click="switchSession(item.id)"
          >
            <a-list-item-meta>
              <template #title>
                <span class="session-title">{{ item.title }}</span>
              </template>
              <template #description>
                <span class="session-time">{{ formatTime(item.lastActiveAt) }}</span>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>
    </div>

    <!-- 右侧对话区 -->
    <div class="chat-panel">
      <div ref="messageArea" class="message-area">
        <div v-if="chat.turns.length === 0" class="chat-empty">
          <StateEmpty state="empty" title="开始问数" description="例如：研发中心在职人数、上月离职率是多少？" />
        </div>

        <div v-for="t in chat.turns" :key="t.id" class="turn-row" :class="t.role">
          <!-- 用户消息 -->
          <div v-if="t.role === 'user'" class="user-bubble">{{ t.question }}</div>
          <!-- 助手消息 -->
          <div v-else class="assistant-wrap">
            <AnswerCard
              :state="t.state"
              :payload="t.payload"
              :clarify="t.clarify"
              :clarifying="chat.asking"
              :streaming-text="t.streamingText"
              :hint="t.hint"
              :error-title="t.errorTitle"
              :error-message="t.errorMessage"
              :can-view-sql="true"
              @retry="retry(t)"
              @clarify-submit="(answers) => submitClarify(t, answers)"
              @view-sql="showSql(t)"
              @load-more-table="loadMoreTable(t)"
              @followup="(q) => send(q)"
              @feedback="(r) => sendFeedback(t, r)"
              @typing-done="onTypingDone(t.id)"
            />
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <a-textarea
          v-model:value="input"
          :auto-size="{ minRows: 1, maxRows: 4 }"
          placeholder="输入您的问题，Enter 发送，Shift+Enter 换行"
          :disabled="chat.asking"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="input-actions">
          <span class="input-hint">支持「研发中心在职人数」「上月离职率」等自然语言问句</span>
          <a-button type="primary" :loading="chat.asking" :disabled="!input.trim()" @click="send()">
            发送
          </a-button>
        </div>
      </div>
    </div>

    <!-- SQL 弹窗 -->
    <a-modal v-model:open="sqlVisible" title="查询逻辑（SQL 与口径）" :footer="null" width="720px">
      <div v-if="sqlView">
        <a-alert type="info" show-icon :message="`口径：${sqlView.caliber.metric}`" :description="sqlView.caliber.definition" class="sql-caliber" />
        <pre class="sql-code">{{ sqlView.sql }}</pre>
      </div>
      <a-skeleton v-else active :paragraph="{ rows: 4 }" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import AnswerCard from '@/components/AnswerCard.vue'
import StateEmpty from '@/components/StateEmpty.vue'
import { chatApi } from '@/api'
import type { AnswerPayload, ClarifyQuestions, SqlView, TableData } from '@/api/types'
import { useChatStore, type ChatTurn } from '@/stores/chat'

const chat = useChatStore()
const input = ref('')
const messageArea = ref<HTMLElement>()
const sqlVisible = ref(false)
const sqlView = ref<SqlView | null>(null)

onMounted(loadSessions)

async function loadSessions() {
  chat.loadingSessions = true
  try {
    const res = await chatApi.listSessions()
    chat.setSessions(res.data.records)
    if (res.data.records.length === 0) {
      const created = await chatApi.createSession('新的会话')
      chat.setSessions([created.data])
      chat.selectSession(created.data.id)
    } else {
      chat.selectSession(res.data.records[0].id)
    }
    await loadTurns()
  } catch {
    message.error('加载会话列表失败')
  } finally {
    chat.loadingSessions = false
  }
}

async function createNewSession() {
  const created = await chatApi.createSession(`会话 ${chat.sessions.length + 1}`)
  chat.setSessions([created.data, ...chat.sessions])
  chat.selectSession(created.data.id)
  chat.clearTurns()
}

async function switchSession(id: number) {
  chat.selectSession(id)
  await loadTurns()
}

async function loadTurns() {
  chat.clearTurns()
  const sessionId = chat.currentSessionId
  if (sessionId == null) return
  const res = await chatApi.listTurns(sessionId)
  for (const turn of res.data) {
    chat.turns.push({
      id: `h_${turn.turnId}`,
      role: 'user',
      question: turn.question,
      state: 'completed',
    })
    chat.turns.push({
      id: `ha_${turn.turnId}`,
      role: 'assistant',
      state: 'completed',
      streamingText: turn.conclusionBrief || '（该轮无结论摘要）',
    })
  }
  scrollToBottom()
}

// ---------------- 问句发送与 SSE 消费 ----------------

async function send(question?: string) {
  const q = (question ?? input.value).trim()
  if (!q || chat.asking) return
  const sessionId = chat.currentSessionId
  if (sessionId == null) return

  // 先清输入框，避免 v-model 与按钮传参竞态导致问句残留
  input.value = ''
  chat.pushUserTurn(q)
  const turnId = chat.pushAssistantTurn()
  chat.asking = true

  const handleFrame = (data: Record<string, unknown>) => {
    const event = String(data.event ?? '')
    const payload = (data.payload ?? {}) as Record<string, unknown>
    switch (event) {
      case 'MESSAGE_DELTA':
        applyDelta(turnId, payload)
        break
      case 'INTERRUPT':
        chat.updateTurn(turnId, {
          state: 'clarifying',
          clarify: normalizeClarify(payload),
        })
        break
      case 'ANSWER_DONE':
        // 先保存 payload；摘要仍在逐字打字时等 typingDone 再切完成态
        chat.updateTurn(turnId, { payload: normalizeAnswerPayload(payload) })
        tryFinalize(turnId)
        break
      case 'ERROR':
        handleError(turnId, payload)
        break
      default:
        break
    }
    scrollToBottom()
  }

  const stream = chatApi.askStream(sessionId, { question: q }, handleFrame)
  await stream.done
  const turn = chat.turns.find((t) => t.id === turnId)
  if (turn && turn.state === 'loading') {
    chat.updateTurn(turnId, { state: 'failed', errorMessage: '未收到有效响应' })
  }
  chat.asking = false
  scrollToBottom()
}

/** 进度提示（“正在解析…”“正在生成智能解读…”）与答案正文分离，避免拼进结论 */
function isProgressHint(text: string) {
  return !!text && text.startsWith('正在') && text.includes('…')
}

/** 消费 MESSAGE_DELTA：进度提示入 hint，实际内容追加到正文（打字机逐字呈现） */
function applyDelta(turnId: string, payload: Record<string, unknown>) {
  const text = String(payload.delta ?? '')
  const turn = chat.turns.find((t) => t.id === turnId)
  if (isProgressHint(text)) {
    chat.updateTurn(turnId, { state: 'streaming', hint: text })
  } else {
    chat.updateTurn(turnId, {
      state: 'streaming',
      streamingText: (turn?.streamingText ?? '') + text,
      typed: false,
    })
  }
}

/** AnswerCard 打字机已打完当前正文 */
function onTypingDone(turnId: string) {
  chat.updateTurn(turnId, { typed: true })
  tryFinalize(turnId)
}

/** payload（ANSWER_DONE）已到且打字机收尾后，才切换完成态，保证摘要逐字可见 */
function tryFinalize(turnId: string) {
  const turn = chat.turns.find((t) => t.id === turnId)
  if (!turn || !turn.payload) return
  const text = turn.streamingText ?? ''
  if (turn.typed || !text) {
    chat.updateTurn(turnId, { state: 'completed', streamingText: '', hint: '' })
  }
}

function handleError(turnId: string, payload: Record<string, unknown>) {
  const code = String(payload.code ?? '')
  if (code.includes('2003')) {
    chat.updateTurn(turnId, { state: 'forbidden' })
  } else {
    chat.updateTurn(turnId, {
      state: 'failed',
      errorTitle: payload.code ? String(payload.code) : '请求失败',
      errorMessage: String(payload.message ?? '服务异常，请重试'),
    })
  }
}

function retry(t: ChatTurn) {
  if (t.question) void send(t.question)
}

/** 兼容 Python snake_case 与 Java camelCase 的 askId */
function resolveAskId(payload: Record<string, unknown> | AnswerPayload | null | undefined): string {
  if (!payload) return ''
  const raw = payload as Record<string, unknown>
  const askId = raw.askId ?? raw.ask_id
  return askId == null ? '' : String(askId)
}

function normalizeAnswerPayload(payload: Record<string, unknown>): AnswerPayload {
  const askId = resolveAskId(payload)
  return { ...(payload as unknown as AnswerPayload), askId }
}

function normalizeClarify(payload: Record<string, unknown>): ClarifyQuestions {
  const askId = resolveAskId(payload)
  return { ...(payload as unknown as ClarifyQuestions), askId }
}

async function submitClarify(t: ChatTurn, answers: Array<{ questionId: string; optionIds: string[] }>) {
  const sessionId = chat.currentSessionId
  const askId = t.clarify?.askId
  if (sessionId == null || !askId) return
  chat.updateTurn(t.id, { state: 'loading', clarify: null })
  chat.asking = true
  const handleFrame = (data: Record<string, unknown>) => {
    const event = String(data.event ?? '')
    const payload = (data.payload ?? {}) as Record<string, unknown>
    if (event === 'MESSAGE_DELTA') {
      applyDelta(t.id, payload)
    } else if (event === 'ANSWER_DONE') {
      chat.updateTurn(t.id, { payload: normalizeAnswerPayload(payload) })
      tryFinalize(t.id)
    } else if (event === 'ERROR') {
      handleError(t.id, payload)
    }
  }
  const stream = chatApi.clarifyStream(sessionId, askId, answers, handleFrame)
  await stream.done
  chat.asking = false
}

// ---------------- 辅助操作 ----------------

async function showSql(t: ChatTurn) {
  sqlView.value = null
  sqlVisible.value = true
  const askId = resolveAskId(t.payload as unknown as Record<string, unknown>)
  if (!askId) {
    message.error('缺少 askId，无法查看 SQL')
    sqlVisible.value = false
    return
  }
  try {
    const res = await chatApi.getSql(askId)
    sqlView.value = res.data
  } catch (e) {
    message.error(e instanceof Error ? e.message : '查看 SQL 失败')
    sqlVisible.value = false
  }
}

async function loadMoreTable(t: ChatTurn) {
  const askId = resolveAskId(t.payload as unknown as Record<string, unknown>)
  if (!askId || !t.payload?.table) return
  const nextPage = t.payload.table.page + 1
  const res = await chatApi.getTable(askId, nextPage, t.payload.table.size)
  const table = res.data as TableData
  if (t.payload) {
    t.payload.table = table
  }
}

function sendFeedback(t: ChatTurn, rating: 'UP' | 'DOWN') {
  const askId = resolveAskId(t.payload as unknown as Record<string, unknown>)
  if (!askId) return
  chatApi
    .feedback(askId, { rating, reason: rating === 'DOWN' ? 'OTHER' : undefined })
    .catch(() => message.warning('反馈提交失败'))
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function scrollToBottom() {
  await nextTick()
  if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: calc(100vh - 64px - 48px);
  gap: 16px;
}

.session-panel {
  width: 280px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  padding: 12px;
  overflow-y: auto;
}

.session-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 8px;
}

.session-list {
  background: #fff;
}

.session-item {
  cursor: pointer;
  border-radius: 6px;
  padding: 8px 12px !important;
}

.session-item.active {
  background: #e6f4ff;
}

.session-title {
  font-size: 14px;
}

.session-time {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.chat-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.message-area {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.chat-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.turn-row {
  margin-bottom: 12px;
}

.turn-row.user {
  display: flex;
  justify-content: flex-end;
}

.user-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  background: #1677ff;
  color: #fff;
  font-size: 14px;
  word-break: break-word;
}

.assistant-wrap {
  max-width: 92%;
}

.input-area {
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.input-hint {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.sql-caliber {
  margin-bottom: 12px;
}

.sql-code {
  margin: 0;
  padding: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
