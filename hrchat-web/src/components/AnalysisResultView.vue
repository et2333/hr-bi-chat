<template>
  <div class="analysis-result">
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
      <section v-if="structureTexts.length" class="structure-insights" data-testid="structure-insights" aria-label="统计变化重点">
        <h4>统计变化重点</h4>
        <ul><li v-for="text in structureTexts" :key="text">{{ text }}</li></ul>
        <p class="muted">根据已核验的部门数据计算；用于确定核查重点，不代表异常或真实离职原因。</p>
      </section>
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
      <section v-if="supplementSteps.length" data-testid="supplement-journey" aria-label="补查过程">
        <h4>补查过程</h4>
        <ol><li v-for="text in supplementSteps" :key="text">{{ text }}</li></ol>
      </section>
      <div v-if="supplementTexts.length" data-testid="supplement-facts">
        <h4>补查得到的事实</h4>
        <ul><li v-for="item in supplementTexts" :key="item.id">{{ item.text }} <span class="muted">[{{ result.supplement_assessment?.evidence_id }}]</span></li></ul>
        <p class="muted">这些每日统计用于辅助核查，尚不能据此确认日期集中或真实离职原因。</p>
      </div>
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
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisTaskView } from '@/api/attribution'
import { displayPeriod } from '@/utils/analysisPeriod'
import { analysisClaimText, analysisIssueLabel, analysisStructureTexts, analysisSupplementSteps } from '@/utils/analysisPresentation'

const props = defineProps<{ task: AnalysisTaskView; stages: string[] }>()
const signed = (value: number) => value > 0 ? `+${value}` : String(value)
const issueLabel = analysisIssueLabel
const result = computed(() => ['COMPLETED', 'PARTIAL'].includes(props.task.status) ? props.task.result : null)
const statusLabel = computed(() => ({ RUNNING: '分析中', COMPLETED: '分析完成', PARTIAL: '部分完成', FAILED: '分析失败', CANCELLED: '已取消' })[props.task.status])
const structureTexts = computed(() => result.value ? analysisStructureTexts(result.value) : [])
const supplementSteps = computed(() => result.value ? analysisSupplementSteps(result.value) : [])
const claimTexts = computed(() => (result.value?.claims ?? []).map(claim => ({ id: claim.claim_id,
  text: analysisClaimText(claim, result.value!.facts), refs: claim.evidence_ids,
})).filter(claim => claim.text))
const supplementTexts = computed(() => (result.value?.supplement_assessment?.fact_ids ?? []).map(id => ({ id,
  text: analysisClaimText({ claim_id: id, kind: 'fact', fact_id: id, evidence_ids: ['ev_daily'] }, result.value!.facts),
})).filter(item => item.text))
</script>

<style scoped>
.analysis-scope { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 13px; }
.analysis-scope dt { color: #657386; }
.analysis-scope dd { margin: 0; }
.muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
h4 { margin-bottom: 4px; }
.analysis-warning { color: #8a4b0d; }
.analysis-stages { padding-left: 20px; color: #657386; font-size: 13px; }
.analysis-summary { font-weight: 600; }
.structure-insights { margin: 12px 0; padding: 4px 12px; background: #f0f5ff; border-radius: 6px; }
.contribution-scroll { overflow-x: auto; }
.contribution-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.contribution-table caption { text-align: left; color: #657386; font-size: 12px; padding-bottom: 4px; }
.contribution-table th,
.contribution-table td { border: 1px solid #e5e9f0; padding: 4px 8px; text-align: right; }
.contribution-table th[scope='row'] { text-align: left; font-weight: 400; }
.analysis-claims { padding-left: 16px; font-size: 13px; }
.analysis-evidence { margin-top: 8px; font-size: 13px; }
.evidence-item { margin-top: 8px; }
.evidence-item p { margin: 0; }
.analysis-limitations { margin-top: 8px; font-size: 13px; }
.analysis-limitations ul { padding-left: 16px; }
</style>
