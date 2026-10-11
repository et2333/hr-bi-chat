import { describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@/api/attribution'
import { analysisStructureTexts, analysisSupplementSteps } from './analysisPresentation'

const result = (): AnalysisResult => ({ status: 'COMPLETED', mode: 'dual',
  summary: { current_total: 4, baseline_total: 4, delta: 0, closure_verified: true },
  contributions: [
    { org_id: 3, org_name: '研发一部', baseline_count: 1, current_count: 3, delta: 2 },
    { org_id: 4, org_name: '研发二部', baseline_count: 3, current_count: 1, delta: -2 },
  ],
  structure: { increase: 2, decrease: 2, offset: 2, absolute_movement: 4, net_delta: 0,
    largest_increase_org_ids: [3], largest_decrease_org_ids: [4], largest_absolute_org_ids: [3, 4], baseline_zero: false },
  facts: { 'structure:offset': { increase: 2, decrease: 2, offset: 2, absolute_movement: 4, net_delta: 0 },
    'structure:concentration': { tied_department_count: 2, tied_absolute_delta: 4, absolute_movement: 4 } },
  claims: [], evidence: [], data_quality: [], unresolved: [], disclaimer: '', usage: {},
})

describe('analysis insight presentation', () => {
  it('explains offsetting changes and clearly names the absolute denominator', () => {
    const text = analysisStructureTexts(result()).join('\n')
    expect(text).toContain('2 人的增减相互抵消')
    expect(text).toContain('增加最多：研发一部')
    expect(text).toContain('减少最多：研发二部')
    expect(text).toContain('绝对变化合计 4 人的 100.0%')
    expect(text).toContain('不是离职率')
    expect(text).not.toContain('Infinity')
  })
  it('handles no movement and a zero baseline without division by zero', () => {
    const value = result()
    value.structure = { ...value.structure!, baseline_zero: true, absolute_movement: 0,
      largest_increase_org_ids: [], largest_decrease_org_ids: [], largest_absolute_org_ids: [] }
    value.facts['structure:offset'] = { absolute_movement: 0 }
    const text = analysisStructureTexts(value).join('\n')
    expect(text).toContain('各部门离职人数均未变化')
    expect(text).toContain('不计算增长率')
    expect(text).not.toContain('%')
    expect(text).not.toContain('NaN')
  })
  it('does not equate permission to supplement with receiving verified evidence', () => {
    const value = result()
    value.supplement_decisions = [{ role: 'Reviewer', allowed: true, reason: null,
      need: { claim_id: 'timing', reason: 'verify_timing_concentration', required_fact_ids: ['daily_peak:current'] } }]
    expect(analysisSupplementSteps(value).join('\n')).toContain('尚无通过核验的每日证据')
    value.evidence = [{ evidence_id: 'ev_daily', status: 'verified', metric_version: '1', data_version: '1', query: { detail: 'daily' } }]
    expect(analysisSupplementSteps(value).join('\n')).toContain('解读未完成核验')
    value.supplement_assessment = { claim_id: 'timing', evidence_id: 'ev_daily', fact_ids: ['daily_peak:current'], conclusion: 'descriptive_only' }
    const text = analysisSupplementSteps(value).join('\n')
    expect(text).toContain('完成描述性核查')
    expect(text).toContain('仍不能据此确认日期集中或真实离职原因')
  })
  it('shows denied requests honestly and remains compatible with old results', () => {
    const value = result()
    expect(analysisSupplementSteps(value)).toEqual([])
    value.supplement_decisions = [{ role: 'Reviewer', allowed: false, reason: 'supplement_not_justified', need: null }]
    const text = analysisSupplementSteps(value).join('\n')
    expect(text).toContain('补查未获执行')
    expect(text).not.toContain('取得证据')
    delete value.structure
    expect(analysisStructureTexts(value)).toEqual([])
  })
})
