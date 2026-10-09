<template>
  <a-card class="answer-card" :bordered="false">
    <!-- 1. 流式摘要（骨架屏 → 进度提示 → 逐字打字机） -->
    <a-skeleton v-if="state === 'streaming' && !typedText && !hint" active :paragraph="{ rows: 2 }" />
    <div v-else-if="(state === 'streaming' && (typedText || hint)) || streamingText" class="streaming-text">
      <p v-if="state === 'streaming'">
        <span v-if="typedText">{{ typedText }}</span><span v-if="typing" class="type-cursor" />
        <span v-else-if="hint" class="streaming-hint">{{ hint }}</span>
      </p>
      <p v-else>{{ streamingText }}</p>
    </div>

    <!-- 2. 降级提示条 -->
    <DegradedTip v-if="degradedTip" :message="degradedTip" />

    <!-- 3. 澄清卡 -->
    <ClarifyCard
      v-if="state === 'clarifying' && clarify"
      :clarify="clarify"
      :submitting="clarifying"
      @submit="emit('clarifySubmit', $event)"
    />

    <!-- 4. 错误/越权/超时态 -->
    <StateEmpty
      v-if="state === 'forbidden'"
      state="forbidden"
      action-text="返回"
      @action="emit('retry')"
    />
    <StateEmpty
      v-if="state === 'failed'"
      state="failed"
      :title="errorTitle"
      :description="errorMessage"
      action-text="重试"
      @action="emit('retry')"
    />
    <StateEmpty
      v-if="state === 'timeout'"
      state="timeout"
      action-text="查看状态"
      @action="emit('retry')"
    />

    <!-- 5. 完成态：三段式 -->
    <template v-if="state === 'completed' && payload">
      <!-- 结论层 -->
      <MetricCard v-if="payload.conclusion" :conclusion="payload.conclusion" :caliber="payload.caliber" />

      <!-- 图表层：仅真实图表类型（LINE/BAR/PIE）展示；NUMBER_CARD/TEXT 不显示图表区 -->
      <div v-if="visualChart" class="chart-area">
        <div class="section-label">图表</div>
        <EChart :option="chartOption" :height="280" />
      </div>

      <!-- 数据层 -->
      <div v-if="payload.table && payload.table.columns.length" class="table-area">
        <div class="table-head">
          <span class="section-label">数据明细</span>
          <a-space v-if="canViewSql">
            <a-button size="small" type="link" @click="emit('viewSql')">查看 SQL</a-button>
          </a-space>
        </div>
        <a-table
          :columns="tableColumns"
          :data-source="payload.table.rows"
          :pagination="false"
          size="small"
          :scroll="{ x: 'max-content' }"
        />
        <div v-if="payload.table.total > payload.table.size" class="table-more">
          <a-button type="link" size="small" @click="emit('loadMoreTable')">
            共 {{ payload.table.total }} 条，加载更多
          </a-button>
        </div>
      </div>

      <!-- 口径溯源 -->
      <div v-if="payload.caliber" class="caliber-box">
        <span v-if="payload.caliber.organization || payload.caliber.timeRange" class="adopted-conditions">
          本次查询：{{ [payload.caliber.metric, payload.caliber.organization, payload.caliber.timeRange, payload.caliber.queryMode].filter(Boolean).join(' · ') }}
        </span>
        <span class="caliber-label">口径溯源</span>
        <span class="caliber-text">{{ payload.caliber.definition }}</span>
      </div>

      <!-- 追问推荐 -->
      <div v-if="payload.followups && payload.followups.length" class="followups">
        <span class="section-label">追问</span>
        <a-space wrap>
          <a-tag v-for="f in payload.followups" :key="f" class="followup-tag" @click="emit('followup', f)">
            {{ f }}
          </a-tag>
        </a-space>
      </div>

      <AttributionPanel
        v-if="canAnalyze && payload.askId && payload.caliber?.metricCode === 'leave_count'"
        :key="payload.askId"
        :source-ask-id="payload.askId"
      />

      <!-- 反馈 + 存为报表 -->
      <div class="feedback-bar">
        <a-space>
          <a-button size="small" shape="circle" @click="rate('UP')">
            <LikeOutlined :theme="rated === 'UP' ? 'filled' : 'outlined'" />
          </a-button>
          <a-button size="small" shape="circle" @click="rate('DOWN')">
            <DislikeOutlined :theme="rated === 'DOWN' ? 'filled' : 'outlined'" />
          </a-button>
          <a-button
            size="small"
            type="link"
            data-testid="save-report"
            :loading="savingReport"
            :disabled="!saveReportEnabled"
            @click="emit('saveReport')"
          >
            存为报表
          </a-button>
          <span v-if="payload.elapsedMs != null" class="elapsed">耗时 {{ payload.elapsedMs }}ms</span>
        </a-space>
      </div>
    </template>
  </a-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { DislikeOutlined, LikeOutlined } from '@ant-design/icons-vue'
import type { AnswerPayload, ClarifyQuestions, Column } from '@/api/types'
import { useTypewriter } from '@/composables/useTypewriter'
import ClarifyCard from './ClarifyCard.vue'
import DegradedTip from './DegradedTip.vue'
import EChart from './EChart.vue'
import MetricCard from './MetricCard.vue'
import StateEmpty from './StateEmpty.vue'
import AttributionPanel from './AttributionPanel.vue'

const props = defineProps<{
  /** loading / streaming / clarifying / completed / failed / forbidden / timeout */
  state: string
  payload?: AnswerPayload | null
  clarify?: ClarifyQuestions | null
  clarifying?: boolean
  streamingText?: string
  /** 流式阶段的瞬时进度提示（正在解析/正在生成…） */
  hint?: string
  errorTitle?: string
  errorMessage?: string
  canViewSql?: boolean
  canAnalyze?: boolean
  savingReport?: boolean
}>()

const emit = defineEmits<{
  retry: []
  clarifySubmit: [answers: Array<{ questionId: string; optionIds: string[] }>]
  viewSql: []
  loadMoreTable: []
  followup: [question: string]
  feedback: [rating: 'UP' | 'DOWN']
  saveReport: []
  /** 打字机已打完当前正文（外层据此延后 ANSWER_DONE 的完成态切换） */
  typingDone: []
}>()

const rated = ref<'UP' | 'DOWN' | null>(null)
/** 有 askId 才可存报表（Boolean prop 缺省会被编译为 false，故不用可选开关 prop） */
const saveReportEnabled = computed(() => !!props.payload?.askId)
const degradedTip = computed(() => (props.payload?.degraded ? props.payload.degradedTip ?? 'AI 服务暂不可用，已使用模板直查为您生成结果' : ''))

/** 仅流式中的答案正文参与打字机；历史已完成消息直接展示 */
const liveText = computed(() => (props.state === 'streaming' ? (props.streamingText ?? '') : ''))
const typedText = useTypewriter(liveText)
const typing = computed(() => typedText.value.length < liveText.value.length)

/** 打字机追上正文（一句打完）时通知外层：ANSWER_DONE 后仍能播完逐字动画 */
watch([typedText, liveText], () => {
  if (liveText.value && typedText.value === liveText.value) {
    emit('typingDone')
  }
})

/** 只有真正的图表类型才展示图表区（NUMBER_CARD/TEXT 不占空白图表位） */
const VISUAL_CHART_TYPES = new Set(['LINE', 'BAR', 'PIE'])
const visualChart = computed(() => !!props.payload?.chart && VISUAL_CHART_TYPES.has(props.payload.chart.type))
const chartOption = computed<Record<string, unknown>>(() => props.payload?.chart?.config ?? {})

const tableColumns = computed(() => {
  const cols = props.payload?.table?.columns ?? []
  return cols.map((c: Column) => ({
    title: c.name,
    dataIndex: c.key,
    key: c.key,
    ellipsis: true,
  }))
})

function rate(rating: 'UP' | 'DOWN') {
  rated.value = rating
  emit('feedback', rating)
}
</script>

<style scoped>
.answer-card {
  margin-top: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.streaming-text {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.85);
  white-space: pre-wrap;
}

.streaming-hint {
  color: rgba(0, 0, 0, 0.45);
}

.type-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: #1677ff;
  animation: type-cursor-blink 0.9s steps(1) infinite;
}

@keyframes type-cursor-blink {
  50% {
    opacity: 0;
  }
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.65);
}

.chart-area,
.table-area {
  margin-top: 16px;
}

.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.caliber-box {
  margin-top: 16px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #f5f5f5;
  font-size: 12px;
}

.caliber-label {
  font-weight: 600;
  margin-right: 8px;
}

.adopted-conditions {
  display: block;
  margin-bottom: 6px;
}

.caliber-text {
  color: rgba(0, 0, 0, 0.65);
}

.followups {
  margin-top: 16px;
}

.followup-tag {
  cursor: pointer;
  border-color: #1677ff;
  color: #1677ff;
}

.feedback-bar {
  margin-top: 16px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.elapsed {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.35);
}
</style>
