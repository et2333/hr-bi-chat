<template>
  <form data-testid="analysis-form" @submit.prevent="confirm">
    <dl class="analysis-scope">
      <dt>指标与组织</dt><dd>{{ scope.metricName }} · {{ scope.scopeLabel }}</dd>
      <dt>{{ currentLabel }}</dt><dd data-testid="current-period">{{ displayPeriod(scope.currentPeriod) }}</dd>
    </dl>
    <div class="period-inputs">
      <label>基期开始日期<input v-model="baselineStart" type="date" required data-testid="baseline-start" /></label>
      <label>基期结束日期<input v-model="baselineEnd" type="date" required data-testid="baseline-end" /></label>
    </div>
    <p class="muted">{{ scope.suggestedBaselinePeriod ? '已预填上个自然月，可修改后确认。' : '请选择要比较的基期，确认后仅分析这两期。' }}</p>
    <p class="muted">AI 将对聚合证据进行解读和复核，可能补查每日数量；不能据此认定真实离职原因。</p>
    <p v-if="reportMeta && !reportMeta.supported" class="muted">该推荐动作暂不支持自动分析，请人工核查。</p>
    <p v-else-if="reportMeta && reportMeta.missingParams.length" class="muted">
      还需确认：{{ reportMeta.missingParams.map(missingParamLabel).join('、') }}。
    </p>
    <p v-if="formError" role="alert" class="analysis-error">{{ formError }}</p>
    <a-space>
      <a-button
        type="primary"
        html-type="submit"
        :loading="busy"
        :disabled="reportMeta ? !reportMeta.supported : false"
        data-testid="confirm-analysis"
      >确认两期，分析变化</a-button>
      <a-button :disabled="busy" @click="emit('collapse')">收起</a-button>
    </a-space>
  </form>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { AnalysisPeriod, AnalysisScope } from '@/api/attribution'
import { baselineFromInputs, displayPeriod, shiftDate } from '@/utils/analysisPeriod'

/** 报表来源附带的推荐动作能力信息；问答来源不传，保持既有文案与行为。 */
export interface ReportActionMeta {
  supported: boolean
  executable: boolean
  missingParams: string[]
}

const props = withDefaults(defineProps<{
  scope: AnalysisScope
  busy: boolean
  currentLabel?: string
  reportMeta?: ReportActionMeta | null
}>(), { currentLabel: '本期（当前答案）', reportMeta: null })

const emit = defineEmits<{ confirm: [baseline: AnalysisPeriod]; collapse: [] }>()
const baselineStart = ref('')
const baselineEnd = ref('')
const formError = ref('')
const labels: Record<string, string> = { baseline_period: '基期', current_period: '本期', metric_code: '指标' }
const missingParamLabel = (name: string) => labels[name] ?? name

watch(() => props.scope, (next) => {
  baselineStart.value = next?.suggestedBaselinePeriod?.start ?? ''
  baselineEnd.value = shiftDate(next?.suggestedBaselinePeriod?.end ?? '', -1)
  formError.value = ''
}, { immediate: true })

function confirm() {
  try {
    const baseline = baselineFromInputs(baselineStart.value, baselineEnd.value, props.scope.currentPeriod)
    formError.value = ''
    emit('confirm', baseline)
  } catch (reason) {
    formError.value = reason instanceof Error ? reason.message : '请检查基期日期'
  }
}
</script>

<style scoped>
.analysis-scope { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 13px; }
.analysis-scope dt { color: #657386; }
.analysis-scope dd { margin: 0; }
.period-inputs { display: flex; flex-wrap: wrap; gap: 12px; }
.period-inputs label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
input[type='date'] { padding: 5px 8px; border: 1px solid #b8c6d8; border-radius: 4px; background: white; }
.muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
.analysis-error { color: #b42318; }
</style>
