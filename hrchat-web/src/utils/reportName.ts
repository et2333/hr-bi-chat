import type { AnswerPayload } from '@/api/types'

/**
 * 从答案结构推导规范报表名（不用口语问句原文）。
 * 规则：指标名 + 分析类型；标量可附问句中的时间提示词（上月/本月/近三月…）。
 * 对齐原型「弹窗命名」的默认建议值。
 */
export function deriveReportName(payload: AnswerPayload, question?: string): string {
  const metric = (payload.caliber?.metric ?? '').trim() || '指标'
  const mode = detectAnswerMode(payload)
  const hint = timeHint(question)

  let name: string
  if (mode === 'trend') {
    const span = hint.includes('近一年') ? '近一年趋势' : '近三月趋势'
    name = `${metric}${span}`
  } else if (mode === 'org') {
    name = `${metric}组织对比`
  } else if (hint) {
    name = `${hint}${metric}`
  } else {
    name = `${metric}报表`
  }
  return name.slice(0, 64)
}

export type AnswerMode = 'trend' | 'org' | 'scalar'

export function detectAnswerMode(payload: AnswerPayload): AnswerMode {
  const chart = payload.chart?.type?.toUpperCase()
  const keys = new Set((payload.table?.columns ?? []).map((c) => c.key))
  if (chart === 'LINE' || keys.has('period')) return 'trend'
  if (chart === 'BAR' || keys.has('org_name') || keys.has('org')) return 'org'
  return 'scalar'
}

/** 仅抽取规范时间提示，不把整句口语问句当标题。 */
function timeHint(question?: string): string {
  const q = (question ?? '').trim()
  if (!q) return ''
  if (q.includes('近一年') || q.includes('近1年')) return '近一年'
  if (q.includes('近三月') || q.includes('近3月') || q.includes('近三个月')) return '近三月'
  if (q.includes('上月') || q.includes('上个月')) return '上月'
  if (q.includes('本月') || q.includes('这个月')) return '本月'
  if (q.includes('去年')) return '去年'
  return ''
}
