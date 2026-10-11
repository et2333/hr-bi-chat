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
  if (claim.fact_id === 'structure:offset') return Number(fact.absolute_movement) === 0
    ? '各部门离职人数均未变化。'
    : `部门增加量合计 ${fact.increase} 人、减少量合计 ${fact.decrease} 人，其中 ${fact.offset} 人的增减相互抵消，净变化 ${signed(fact.net_delta)} 人。`
  if (claim.fact_id === 'structure:concentration') return Number(fact.absolute_movement) > 0
    ? `绝对变化最大的 ${fact.tied_department_count} 个部门合计变化 ${fact.tied_absolute_delta} 人，占各部门绝对变化合计 ${fact.absolute_movement} 人的 ${(100 * Number(fact.tied_absolute_delta) / Number(fact.absolute_movement)).toFixed(1)}%。这不是离职率或以净变化为分母的贡献率。`
    : '各部门均未变化，不计算变化份额。'
  if (claim.fact_id?.startsWith('department:')) return `部门统计贡献：${fact.org_name} 本期 ${fact.current_count} 人，基期 ${fact.baseline_count} 人，变化 ${signed(fact.delta)} 人。`
  if (claim.fact_id?.startsWith('daily_peak:')) return `每日数量：${claim.fact_id.endsWith('current') ? '本期' : '基期'}单日最高为 ${fact.date} 的 ${fact.count} 人。`
  return ''
}

/** Descriptive highlights from validated aggregates, separate from AI claims. */
export function analysisStructureTexts(result: AnalysisResult): string[] {
  const structure = result.structure
  if (!structure) return []
  const lines = [analysisClaimText({ claim_id: 'offset', kind: 'fact', fact_id: 'structure:offset', evidence_ids: ['ev_department'] }, result.facts)]
  for (const [ids, label] of [[structure.largest_increase_org_ids, '增加最多'], [structure.largest_decrease_org_ids, '减少最多']] as const) {
    const rows = result.contributions.filter(row => ids.includes(row.org_id))
    if (!rows.length) continue
    const names = rows.slice(0, 5).map(row => row.org_name).join('、') + (rows.length > 5 ? `等 ${rows.length} 个部门` : '')
    lines.push(`${label}：${names}${rows.length > 1 ? '（并列）' : ''}，${rows.length > 1 ? '各' : ''}变化 ${signed(rows[0].delta)} 人。`)
  }
  if (structure.absolute_movement > 0) lines.push(analysisClaimText({ claim_id: 'concentration', kind: 'fact',
    fact_id: 'structure:concentration', evidence_ids: ['ev_department'] }, result.facts))
  if (structure.baseline_zero) lines.push('基期人数为 0，仅展示人数变化，不计算增长率。')
  return lines.filter(Boolean)
}

/** Reconstruct recorded decisions; never infer that a requested query succeeded. */
export function analysisSupplementSteps(result: AnalysisResult): string[] {
  const decisions = result.supplement_decisions ?? []
  const daily = result.evidence.some(item => item.evidence_id === 'ev_daily' && item.status === 'verified')
  const assessment = result.supplement_assessment
  if (!decisions.length && !daily && !assessment) return []
  const allowed = decisions.find(item => item.allowed)
  const lines: string[] = []
  if (allowed) {
    lines.push('待核查：离职事件是否集中在少数日期；部门汇总不能回答具体时间分布。')
    lines.push('补查依据：已记录时间分布假设，读取已确认组织范围与两期的每日离职数量。')
  } else if (decisions.length) lines.push('补查未获执行：' + analysisIssueLabel(decisions[0].reason ?? 'supplement_not_justified'))
  else lines.push('历史记录包含每日证据或解读，但未记录完整的补查申请过程。')
  if (daily) {
    lines.push('取得证据：两期每日统计已通过范围、版本与总量核对。')
    lines.push(assessment?.conclusion === 'descriptive_only'
      ? '核查结果：已引用每日统计完成描述性核查；仍不能据此确认日期集中或真实离职原因。'
      : '尚未闭环：每日数据已取得，但解读未完成核验或仍认为证据不足。')
  } else if (allowed) lines.push('尚未闭环：已允许补查，但尚无通过核验的每日证据。')
  if (allowed && decisions.some(item => !item.allowed)) lines.push('后续补查已停止：' + analysisIssueLabel(decisions.find(item => !item.allowed)!.reason ?? 'supplement_limit_reached'))
  return lines
}

export function analysisIssueLabel(issue: string): string {
  const labels: Record<string, string> = {
    unsupported_claim: '部分结论缺少有效证据，已移除。',
    supplement_limit_reached: '已达到补查次数上限，仍有问题待核查。',
    supplement_not_justified: '补查请求未说明有效的待核查项，已停止额外查询；已核验的统计结果仍可参考。',
    supplement_already_satisfied: '所需证据已经提供，已停止重复查询。',
    supplement_not_assessed: '每日数据已取得，但解读未完整引用所需证据，补查尚未完成核验。',
    supplement_assessment_invalid: '补查解读引用了尚未核验的证据，已停止采用该解读。',
    supplement_evidence_insufficient: '补查后仍缺少足够证据，相关问题继续保留为待核查项。',
    review_evidence_insufficient: '复核认为现有证据不足。',
    no_supported_claims: '尚无得到充分证据支持的解读。',
    review_highlight_invalid: '复核补充的重点引用了不存在的事实，未采用该补充。',
    review_highlight_limit: '复核补充超出结论数量上限，已停止追加。',
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
