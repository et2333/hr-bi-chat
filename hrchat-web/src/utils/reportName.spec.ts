import { describe, expect, it } from 'vitest'
import { deriveReportName, detectAnswerMode } from './reportName'
import type { AnswerPayload } from '@/api/types'

function payload(partial: Partial<AnswerPayload>): AnswerPayload {
  return {
    askId: 'ask_1',
    answerId: 'ans_1',
    status: 'ASK_COMPLETED',
    intent: 'QUERY',
    degraded: false,
    conclusion: { type: 'NUMBER_CARD', value: 18, unit: '人', compare: null },
    table: { columns: [], rows: [], total: 0, page: 1, size: 20 },
    chart: null,
    caliber: { metric: '在职人数', metricCode: 'headcount', definition: '', dataUpdatedAt: '' },
    followups: [],
    elapsedMs: 1,
    ...partial,
  }
}

describe('deriveReportName', () => {
  it('趋势 → 指标+近三月趋势', () => {
    const p = payload({
      chart: { type: 'LINE', recommended: true, config: {} },
      table: {
        columns: [
          { key: 'period', name: '月份', type: 'string', masked: false },
          { key: 'headcount', name: '在职人数', type: 'number', masked: false },
        ],
        rows: [],
        total: 0,
        page: 1,
        size: 20,
      },
    })
    expect(detectAnswerMode(p)).toBe('trend')
    expect(deriveReportName(p, '查看在职人数近三月趋势')).toBe('在职人数近三月趋势')
  })

  it('组织对比 → 指标+组织对比', () => {
    const p = payload({
      chart: { type: 'BAR', recommended: true, config: {} },
      table: {
        columns: [
          { key: 'org_name', name: '组织', type: 'string', masked: false },
          { key: 'headcount', name: '在职人数', type: 'number', masked: false },
        ],
        rows: [],
        total: 0,
        page: 1,
        size: 20,
      },
    })
    expect(deriveReportName(p, '按组织对比在职人数')).toBe('在职人数组织对比')
  })

  it('标量 + 上月提示 → 上月+指标', () => {
    const p = payload({
      caliber: {
        metric: '离职率',
        metricCode: 'turnover_rate',
        definition: '',
        dataUpdatedAt: '',
      },
      chart: { type: 'NUMBER_CARD', recommended: true, config: {} },
    })
    expect(deriveReportName(p, '「上月离职率')).toBe('上月离职率')
  })

  it('标量无时间提示 → 指标报表', () => {
    const p = payload({
      chart: { type: 'NUMBER_CARD', recommended: true, config: {} },
    })
    expect(deriveReportName(p, '研发中心在职人数')).toBe('在职人数报表')
  })

  it('不用口语问句原文作标题', () => {
    const p = payload({
      chart: { type: 'BAR', recommended: true, config: {} },
      table: {
        columns: [{ key: 'org_name', name: '组织', type: 'string', masked: false }],
        rows: [],
        total: 0,
        page: 1,
        size: 20,
      },
    })
    const name = deriveReportName(p, '帮我看看各部门在职大概多少啊？')
    expect(name).toBe('在职人数组织对比')
    expect(name).not.toContain('帮我')
  })
})
