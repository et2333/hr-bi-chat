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
              :can-analyze="hasPerm(auth.functionPerms, 'chat:attribution')"
              :saving-report="savingAskId === t.payload?.askId"
              @retry="retry(t)"
              @clarify-submit="(answers) => submitClarify(t, answers)"
              @view-sql="showSql(t)"
              @load-more-table="loadMoreTable(t)"
              @followup="(q) => sendFollowup(t, q)"
              @feedback="(r) => sendFeedback(t, r)"
              @save-report="saveAsReport(t)"
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
        <a-tag v-if="isCaliberOnlySql(sqlView.sql)" color="orange" class="sql-mode-tag">口径定义</a-tag>
        <pre class="sql-code">{{ sqlView.sql }}</pre>
      </div>
      <a-skeleton v-else active :paragraph="{ rows: 4 }" />
    </a-modal>

    <!-- 存为报表：建议名可改（原型：弹窗命名） -->
    <a-modal
      v-model:open="saveReportOpen"
      title="存为报表"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="!!savingAskId"
      destroy-on-close
      @ok="confirmSaveReport"
    >
      <a-form layout="vertical">
        <a-form-item label="报表名称" required>
          <a-input
            v-model:value="saveReportName"
            :maxlength="64"
            show-count
            placeholder="请输入报表名称"
            @pressEnter="confirmSaveReport"
          />
        </a-form-item>
        <p class="save-report-hint">已按指标与分析类型自动命名，可按需修改后再保存。</p>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import AnswerCard from '@/components/AnswerCard.vue'
import StateEmpty from '@/components/StateEmpty.vue'
import { chatApi, reportApi } from '@/api'
import type { ComponentSpec } from '@/api/reports'
import type { AnswerPayload, ClarifyQuestions, SqlView, TableData } from '@/api/types'
import { useChatStore, type ChatTurn } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { hasPerm } from '@/layouts/adminMenu'
import { deriveReportName } from '@/utils/reportName'

const chat = useChatStore()
const auth = useAuthStore()
const router = useRouter()
const input = ref('')
const messageArea = ref<HTMLElement>()
const sqlVisible = ref(false)
const sqlView = ref<SqlView | null>(null)
const savingAskId = ref<string | null>(null)
const saveReportOpen = ref(false)
const saveReportName = ref('')
const saveReportTurn = ref<ChatTurn | null>(null)

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
      // 返回对话页时尽量留在原会话，避免总是跳到列表第一条
      const keepId = chat.currentSessionId
      const keep = keepId != null && res.data.records.some((s) => s.id === keepId)
      chat.selectSession(keep ? keepId! : res.data.records[0].id)
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
    const assistantId = `ha_${turn.turnId}`
    // 先占位；再按 askId 拉取完整 ANSWER_DONE（内存 askStore），失败则用摘要拼最小完成态
    chat.turns.push({
      id: assistantId,
      role: 'assistant',
      question: turn.question,
      state: 'completed',
      streamingText: '',
      payload: briefPayload(turn),
    })
    if (turn.askId) {
      void hydrateHistoryAnswer(assistantId, turn.askId, turn)
    }
  }
  scrollToBottom()
}

/** 历史列表无完整 payload 时的最小完成态，避免只显示「该轮无结论摘要」 */
function briefPayload(turn: { askId?: string; conclusionBrief?: string; status?: string }): AnswerPayload {
  const brief = (turn.conclusionBrief ?? '').trim()
  return {
    askId: turn.askId ?? '',
    answerId: '',
    status: turn.status || 'COMPLETED',
    intent: 'QUERY',
    degraded: false,
    conclusion: {
      type: 'TEXT',
      value: brief || '（历史结论摘要未持久化；可重新提问查看完整结果）',
    },
    table: null,
    chart: null,
    caliber: null,
    followups: [],
  }
}

async function hydrateHistoryAnswer(
  turnId: string,
  askId: string,
  turn: { conclusionBrief?: string; status?: string; askId?: string },
) {
  try {
    const res = await chatApi.getAsk(askId)
    if (res.data) {
      chat.updateTurn(turnId, {
        state: 'completed',
        payload: normalizeAnswerPayload(res.data as unknown as Record<string, unknown>),
        streamingText: '',
      })
      return
    }
  } catch {
    // askStore 重启后会丢：保留 briefPayload
  }
  chat.updateTurn(turnId, { payload: briefPayload({ ...turn, askId }) })
}

// ---------------- 问句发送与 SSE 消费 ----------------

async function send(question?: string) {
  const q = (question ?? input.value).trim()
  if (!q || chat.asking) return
  const sessionId = chat.currentSessionId
  if (sessionId == null) return

  // 先清输入框；等 DOM 刷完再禁用，避免 a-textarea 在 disabled 切换时把旧值写回
  input.value = ''
  await nextTick()
  input.value = ''
  chat.pushUserTurn(q)
  // 助手轮次带上问句，存报表时用问句区分「对比 / 趋势 / 标量」
  const turnId = chat.pushAssistantTurn(q)
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

/** 打开命名弹窗：默认名由指标+分析类型推导，不用口语问句原文 */
function saveAsReport(t: ChatTurn) {
  const payload = t.payload
  if (!payload?.askId) {
    message.warning('当前回答缺少 askId，无法存为报表')
    return
  }
  if (savingAskId.value) return
  saveReportTurn.value = t
  saveReportName.value = deriveReportName(payload, resolveTurnQuestion(t))
  saveReportOpen.value = true
}

function resolveTurnQuestion(t: ChatTurn): string {
  const q = (t.question ?? '').trim()
  if (q) return q
  const idx = chat.turns.findIndex((x) => x.id === t.id)
  for (let i = idx - 1; i >= 0; i--) {
    if (chat.turns[i].role === 'user' && chat.turns[i].question) {
      return chat.turns[i].question!.trim()
    }
  }
  return ''
}

/** 确认命名后创建报表并跳转详情 */
async function confirmSaveReport() {
  const t = saveReportTurn.value
  const payload = t?.payload
  const askId = payload?.askId
  const name = saveReportName.value.trim()
  if (!t || !payload || !askId) {
    message.warning('无法保存：缺少答案上下文')
    return
  }
  if (!name) {
    message.warning('请填写报表名称')
    return Promise.reject(new Error('empty name'))
  }
  if (savingAskId.value) return
  savingAskId.value = askId
  try {
    const components = buildReportComponents(payload)
    const res = await reportApi.createReport({
      sourceType: 'ASK',
      sourceId: askId,
      name: name.slice(0, 64),
      components,
    })
    saveReportOpen.value = false
    saveReportTurn.value = null
    message.success('已存为报表')
    await router.push(`/reports/${res.data}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '存为报表失败')
  } finally {
    savingAskId.value = null
  }
}

/** 存报表 def.metric 必须用语义 code；展示名进 title */
function resolveMetricCode(payload: AnswerPayload): string {
  const code = payload.caliber?.metricCode?.trim()
  if (code) return code
  const skip = new Set(['org_name', 'period', 'dim_value', 'org', 'emp_no', 'emp_name', 'job_level'])
  const col = payload.table?.columns?.find((c) => c.key && !skip.has(c.key))
  return col?.key?.trim() || ''
}

/** 答案无 chart 或类型丢失时，按表结构推断 BAR/LINE，避免存报表只剩表格 */
function inferChartType(payload: AnswerPayload): 'BAR' | 'LINE' | 'PIE' | null {
  const t = payload.chart?.type?.toUpperCase()
  if (t === 'BAR' || t === 'LINE' || t === 'PIE') return t
  const keys = new Set((payload.table?.columns ?? []).map((c) => c.key))
  if (keys.has('period')) return 'LINE'
  if (keys.has('org_name') || keys.has('org')) return 'BAR'
  return null
}

function buildReportComponents(payload: AnswerPayload): ComponentSpec[] {
  const comps: ComponentSpec[] = []
  const title = payload.caliber?.metric ?? '指标'
  const metricCode = resolveMetricCode(payload)
  const definition = payload.caliber?.definition ?? ''
  if (!metricCode) {
    throw new Error('缺少指标编码，无法存为报表（请重试问数后再存）')
  }
  if (payload.conclusion) {
    comps.push({
      compType: 'METRIC_CARD',
      def: {
        metric: metricCode,
        title,
        unit: payload.conclusion.unit ?? '',
        definition,
      },
    })
  }
  // 有可视化图型则落 CHART；纯标量也默认落 BAR（按组织），保证报表中心可切换柱/线/饼
  const chartType = inferChartType(payload) ?? 'BAR'
  const isTrend = chartType === 'LINE'
  const dimensions = isTrend ? ['time'] : ['org']
  const chartTitle = isTrend ? `${title}近三月趋势` : title
  comps.push({
    compType: 'CHART',
    chartType,
    def: {
      metric: metricCode,
      title: chartTitle,
      // dimensions：后端正式字段；dims：种子/历史兼容
      dimensions,
      dims: dimensions,
      ...(isTrend ? { months: 3 } : {}),
    },
  })
  // 有无明细都落 TABLE 定义，详情页按 dimensions 实时查数
  comps.push({
    compType: 'TABLE',
    def: {
      metric: metricCode,
      title: isTrend ? '月度明细' : '数据明细',
      dimensions,
      dims: dimensions,
      ...(isTrend ? { months: 3 } : {}),
    },
  })
  return comps
}

/** 短句追问 chips 拼上口径指标名，避免「查看明细」无法解析 */
function sendFollowup(t: ChatTurn, question: string) {
  const q = (question ?? '').trim()
  if (!q) return
  const metric = t.payload?.caliber?.metric?.trim()
  if (!metric) {
    void send(q)
    return
  }
  const short = ['查看明细', '按组织对比', '查看近三月趋势', '查看近一年趋势']
  if (short.includes(q)) {
    if (q === '查看明细') void send(`查看${metric}明细`)
    else if (q === '按组织对比') void send(`按组织对比${metric}`)
    else if (q.includes('近一年')) void send(`查看${metric}近一年趋势`)
    else void send(`查看${metric}近三月趋势`)
    return
  }
  void send(q)
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
  const base = { ...(payload as unknown as AnswerPayload), askId }
  const rawCaliber = payload.caliber
  if (!rawCaliber || typeof rawCaliber !== 'object') return base
  const c = rawCaliber as Record<string, unknown>
  return {
    ...base,
    caliber: {
      metric: String(c.metric ?? ''),
      metricCode:
        c.metricCode != null
          ? String(c.metricCode)
          : c.metric_code != null
            ? String(c.metric_code)
            : undefined,
      definition: String(c.definition ?? ''),
      organization: c.organization == null ? undefined : String(c.organization),
      queryMode: c.queryMode == null && c.query_mode == null ? undefined : String(c.queryMode ?? c.query_mode),
      timeRange:
        c.timeRange != null
          ? String(c.timeRange)
          : c.time_range != null
            ? String(c.time_range)
            : undefined,
      dataUpdatedAt: String(c.dataUpdatedAt ?? c.data_updated_at ?? ''),
    },
  }
}

/** SSE 澄清载荷可能是 snake_case（Local INTERRUPT），统一为前端 camelCase */
function normalizeClarify(payload: Record<string, unknown>): ClarifyQuestions {
  const askId = resolveAskId(payload)
  const interruptType = String(payload.interruptType ?? payload.interrupt_type ?? 'CLARIFY')
  const rawQuestions = Array.isArray(payload.questions) ? payload.questions : []
  const questions = rawQuestions.map((q) => {
    const qm = (q ?? {}) as Record<string, unknown>
    const rawOpts = Array.isArray(qm.options) ? qm.options : []
    const options = rawOpts.map((o) => {
      const om = (o ?? {}) as Record<string, unknown>
      return {
        optionId: String(om.optionId ?? om.option_id ?? ''),
        label: String(om.label ?? ''),
      }
    })
    return {
      questionId: String(qm.questionId ?? qm.question_id ?? ''),
      question: String(qm.question ?? ''),
      multiple: Boolean(qm.multiple),
      options,
    }
  })
  return { interruptType, askId, questions }
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

/** remote 场景可能只返回口径公式，UI 标明「口径定义」 */
function isCaliberOnlySql(sql: string | undefined): boolean {
  const s = (sql ?? '').trim()
  if (!s) return true
  const upper = s.toUpperCase()
  return !upper.includes('SELECT') && !upper.includes('WITH ')
}

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

.save-report-hint {
  margin: 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  line-height: 1.5;
}

.sql-caliber {
  margin-bottom: 12px;
}

.sql-mode-tag {
  margin-bottom: 8px;
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
