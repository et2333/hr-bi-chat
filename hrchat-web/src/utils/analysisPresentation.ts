import type { AnalysisResult } from '@/api/attribution'

const signed = (value: string | number) => Number(value) > 0 ? `+${value}` : String(value)
/** Render only verified typed facts. Model prose is never treated as a numeric source. */
export function analysisClaimText(claim: AnalysisResult['claims'][number], facts: AnalysisResult['facts']): string {
  if (claim.kind === 'unverified_hypothesis') return claim.hypothesis === 'timing_concentration'
    ? '待核查假设：离职事件可能集中在少数日期，尚不能据此确定原因。'
    : '待核查：真实离职原因需要结合业务记录确认。'
  if (claim.kind === 'next_check') return claim.next_check === 'daily_counts'
    ? '建议核查：查看两期每日离职数量，确认是否存在日期集中。'
    : '建议核查：结合 HR 业务记录进一步确认原因。'
  const fact = claim.fact_id ? facts[claim.fact_id] : null
  if (!fact) return ''
  if (claim.fact_id === 'overall') return `统计事实：本期 ${fact.current} 人，基期 ${fact.baseline} 人，变化 ${signed(fact.delta)} 人。`
  if (claim.fact_id?.startsWith('department:')) return `部门统计贡献：${fact.org_name} 本期 ${fact.current_count} 人，基期 ${fact.baseline_count} 人，变化 ${signed(fact.delta)} 人。`
  if (claim.fact_id?.startsWith('daily_peak:')) return `每日数量：${claim.fact_id.endsWith('current') ? '本期' : '基期'}单日最高为 ${fact.date} 的 ${fact.count} 人。`
  return ''
}

export function analysisIssueLabel(issue: string): string {
  const labels: Record<string, string> = {
    unsupported_claim: '部分结论缺少有效证据，已移除。',
    supplement_limit_reached: '已达到补查次数上限，仍有问题待核查。',
    supplement_not_justified: '补查请求未说明有效的待核查项，已停止额外查询；已核验的统计结果仍可参考。',
    supplement_already_satisfied: '所需证据已经提供，已停止重复查询。',
    review_evidence_insufficient: '复核认为现有证据不足。',
    no_supported_claims: '尚无得到充分证据支持的解读。',
    task_deadline_exceeded: '分析达到时间上限。',
    model_call_limit: '已达到模型调用次数上限。',
    mcp_attempt_limit: '已达到数据查询次数上限。',
    tool_access_or_execution_failed: '数据读取失败或当前权限不允许读取。',
    analysis_execution_failed: '分析过程未完成。',
    model_connection_failed: '无法连接模型服务，请检查网络连接后重试。',
    model_request_failed: '模型请求未成功，请检查模型配置或服务状态。',
    model_budget_exceeded: '已达到模型调用次数上限。',
    mcp_budget_exceeded: '已达到数据查询次数上限。',
    cancelled: '分析已取消。',
  }
  return labels[issue] ?? '部分证据尚未通过完整性或一致性校验，请重新问数后核查。'
}
