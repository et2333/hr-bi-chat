<template>
  <section class="attribution-panel" aria-label="离职人数变化分析">
    <a-button v-if="!opened && !task" size="small" data-testid="open-analysis" :loading="busy" @click="open">
      分析变化
    </a-button>

    <div v-if="opened || task" class="analysis-body">
      <h3>离职人数变化分析</h3>
      <p class="muted">按离职事件所属部门拆解数量变化，帮助确定后续核查范围。</p>
      <p v-if="error" role="alert" class="analysis-error">{{ error }}</p>

      <form v-if="context && !task" data-testid="analysis-form" @submit.prevent="confirm">
        <dl class="analysis-scope">
          <dt>指标与组织</dt><dd>{{ context.metricName }} · {{ context.scopeLabel }}</dd>
          <dt>本期（当前答案）</dt><dd data-testid="current-period">{{ displayPeriod(context.currentPeriod) }}</dd>
        </dl>
        <div class="period-inputs">
          <label>基期开始日期<input v-model="baselineStart" type="date" required data-testid="baseline-start" /></label>
          <label>基期结束日期<input v-model="baselineEnd" type="date" required data-testid="baseline-end" /></label>
        </div>
        <p class="muted">{{ context.suggestedBaselinePeriod ? '已预填上个自然月，可修改后确认。' : '请选择要比较的基期，确认后仅分析这两期。' }}</p>
        <p class="muted">AI 将对聚合证据进行解读和复核，可能补查每日数量；不能据此认定真实离职原因。</p>
        <p v-if="formError" role="alert" class="analysis-error">{{ formError }}</p>
        <a-space>
          <a-button type="primary" html-type="submit" :loading="busy" data-testid="confirm-analysis">确认两期，分析变化</a-button>
          <a-button :disabled="busy" @click="opened = false">收起</a-button>
        </a-space>
      </form>

      <template v-if="task">
        <dl class="analysis-scope">
          <dt>组织</dt><dd>{{ task.context.scopeLabel }}</dd>
          <dt>本期</dt><dd>{{ displayPeriod(task.context.currentPeriod) }}</dd>
          <dt>基期</dt><dd>{{ displayPeriod(task.context.baselinePeriod) }}</dd>
          <dt>状态</dt><dd role="status">{{ statusLabel }}</dd>
        </dl>
        <ol v-if="stages.length" class="analysis-stages" aria-label="分析进度" aria-live="polite">
          <li v-for="(stage, index) in stages" :key="index">{{ stage }}</li>
        </ol>
        <p v-if="task.status === 'PARTIAL'" class="analysis-warning">分析未全部完成，以下仅展示已经核对的数据，请结合局限与待核查项使用。</p>
        <p v-if="task.status === 'FAILED'" class="analysis-warning">本次分析未完成。原问数答案仍可查看，请重新问数或稍后重试。</p>
        <p v-if="task.status === 'CANCELLED'" class="muted">已取消分析。</p>
        <template v-if="result">
          <p v-if="result.summary" class="analysis-summary" data-testid="analysis-summary">
            本期 {{ result.summary.current_total }} 人，基期 {{ result.summary.baseline_total }} 人，变化 {{ signed(result.summary.delta) }} 人。
          </p>
          <p v-if="result.summary?.delta === 0" class="muted">总量未变化，仍可查看各部门的增加与减少；不计算以净变化为分母的贡献率。</p>
          <div v-if="result.contributions.length" class="contribution-scroll">
            <table class="contribution-table">
              <caption>部门数量变化（事件所属部门互斥分项）</caption>
              <thead><tr><th>部门</th><th>基期人数</th><th>本期人数</th><th>变化人数</th></tr></thead>
              <tbody><tr v-for="row in result.contributions" :key="row.org_id"><th scope="row">{{ row.org_name }}</th><td>{{ row.baseline_count }}</td><td>{{ row.current_count }}</td><td>{{ signed(row.delta) }}</td></tr></tbody>
            </table>
          </div>
          <p v-if="result.summary?.closure_verified" class="muted">已核对：各部门变化之和等于总量变化。</p>

          <ul v-if="claimTexts.length" class="analysis-claims">
            <li v-for="claim in claimTexts" :key="claim.id">{{ claim.text }} <span class="muted">[{{ claim.refs.join('、') }}]</span></li>
          </ul>
          <details v-if="result.evidence.length" class="analysis-evidence">
            <summary>查看证据来源（{{ result.evidence.length }} 项）</summary>
            <div v-for="item in result.evidence" :key="item.evidence_id" class="evidence-item">
              <p>{{ item.evidence_id }} · {{ item.query.detail === 'daily' ? '两期每日聚合' : '两期部门聚合' }} · 已核对</p>
              <p class="muted">指标版本：{{ item.metric_version }}；数据版本：{{ item.data_version }}</p>
              <ul v-if="item.daily"><li v-for="row in item.daily" :key="`${row.period}-${row.date}`">{{ row.period === 'current' ? '本期' : '基期' }} {{ row.date }}：{{ row.count }} 人</li></ul>
            </div>
          </details>
          <div class="analysis-limitations">
            <h4>局限与待核查项</h4>
            <ul><li v-for="item in result.data_quality" :key="item">{{ item }}</li><li v-for="item in result.unresolved" :key="item">{{ issueLabel(item) }}</li></ul>
            <p>{{ result.disclaimer || '辅助分析，仅供参考' }}</p>
          </div>
        </template>
        <a-space>
          <a-button v-if="task.status === 'RUNNING'" :loading="cancelling" @click="cancel">取消分析</a-button>
          <a-button v-else :loading="busy" @click="chooseAgain">重新选择基期</a-button>
        </a-space>
      </template>
      <a-button v-if="disconnected" :loading="busy" data-testid="restore-analysis" @click="restore">恢复查看</a-button>
      <a-button v-if="!context && !task && !disconnected" :loading="busy" @click="prepare">重新读取分析范围</a-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useAttribution } from '@/composables/useAttribution'
import { baselineFromInputs, displayPeriod, shiftDate } from '@/utils/analysisPeriod'
import { analysisClaimText, analysisIssueLabel } from '@/utils/analysisPresentation'

const props = defineProps<{ sourceAskId: string }>()
const { context, task, stages, busy, cancelling, disconnected, error, prepare, start, restore, cancel, chooseAgain } = useAttribution(props.sourceAskId)
const opened = ref(false)
const baselineStart = ref('')
const baselineEnd = ref('')
const formError = ref('')
const signed = (value: number) => value > 0 ? `+${value}` : String(value)
const issueLabel = analysisIssueLabel
const result = computed(() => ['COMPLETED', 'PARTIAL'].includes(task.value?.status ?? '') ? task.value?.result : null)
const statusLabel = computed(() => ({ RUNNING: '分析中', COMPLETED: '分析完成', PARTIAL: '部分完成', FAILED: '分析失败', CANCELLED: '已取消' })[task.value?.status ?? 'RUNNING'])
const claimTexts = computed(() => (result.value?.claims ?? []).map(claim => ({ id: claim.claim_id,
  text: analysisClaimText(claim, result.value!.facts), refs: claim.evidence_ids,
})).filter(claim => claim.text))
watch(context, next => {
  baselineStart.value = next?.suggestedBaselinePeriod?.start ?? ''
  baselineEnd.value = shiftDate(next?.suggestedBaselinePeriod?.end ?? '', -1)
  formError.value = ''
})
onMounted(restore)
async function open() { opened.value = true; await prepare() }
async function confirm() {
  if (!context.value) return
  try {
    const baseline = baselineFromInputs(baselineStart.value, baselineEnd.value, context.value.currentPeriod)
    formError.value = ''
    await start(baseline, 'dual')
  } catch (reason) { formError.value = reason instanceof Error ? reason.message : '请检查基期日期' }
}
</script>

<style scoped>
.attribution-panel { margin-top: 16px; }
.analysis-body { padding: 16px; background: #fafcff; border: 1px solid #d9e6f5; border-radius: 8px; }
h3 { margin: 0 0 8px; font-size: 16px; }
h4 { margin-bottom: 4px; }
.muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
.analysis-scope { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 13px; }
.analysis-scope dt { color: #657386; }
.analysis-scope dd { margin: 0; }
.period-inputs { display: flex; flex-wrap: wrap; gap: 12px; }
.period-inputs label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
input[type='date'] { padding: 5px 8px; border: 1px solid #b8c6d8; border-radius: 4px; background: white; }
.analysis-error { color: #b42318; }
.analysis-warning { color: #8a4b0d; }
.analysis-stages { padding-left: 20px; color: #657386; font-size: 13px; }
.analysis-summary { font-weight: 600; }
.contribution-scroll { overflow-x: auto; }
.contribution-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.contribution-table caption { text-align: left; margin-bottom: 6px; color: #657386; }
.contribution-table th, .contribution-table td { text-align: left; border-bottom: 1px solid #d9e6f5; padding: 8px; }
.analysis-claims { padding-left: 20px; margin-top: 16px; }
.analysis-evidence { margin: 16px 0; }
.analysis-evidence summary { cursor: pointer; }
.evidence-item { padding-left: 12px; }
.analysis-limitations { font-size: 13px; }
</style>
