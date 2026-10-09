import type { AnalysisPeriod } from '@/api/attribution'

function dateValue(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return NaN
  const parsed = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 10) === value ? parsed : NaN
}
export function shiftDate(value: string, days: number): string {
  const parsed = dateValue(value)
  return Number.isFinite(parsed) ? new Date(parsed + days * 86400000).toISOString().slice(0, 10) : ''
}
export function displayPeriod(period?: AnalysisPeriod | null): string {
  return period ? `${period.start} 至 ${shiftDate(period.end, -1)}` : '未指定'
}
export function baselineFromInputs(start: string, inclusiveEnd: string, current: AnalysisPeriod): AnalysisPeriod {
  const startMs = dateValue(start)
  const endMs = dateValue(inclusiveEnd)
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) throw new Error('请填写完整、有效的基期起止日期')
  const days = (endMs - startMs) / 86400000 + 1
  if (days < 1 || days > 366) throw new Error('基期结束日期不能早于开始日期，且最多为 366 天')
  const period = { start, end: shiftDate(inclusiveEnd, 1) }
  if (period.start === current.start && period.end === current.end) throw new Error('基期与本期不能完全相同')
  return period
}
