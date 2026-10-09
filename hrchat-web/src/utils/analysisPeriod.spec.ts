import { describe, expect, it } from 'vitest'
import { baselineFromInputs, displayPeriod, shiftDate } from './analysisPeriod'

describe('analysis periods', () => {
  const current = { start: '2026-09-01', end: '2026-10-01' }
  it('shows inclusive dates and converts the confirmed last day to an exclusive API bound', () => {
    expect(displayPeriod(current)).toBe('2026-09-01 至 2026-09-30')
    expect(baselineFromInputs('2026-08-01', '2026-08-31', current)).toEqual({ start: '2026-08-01', end: '2026-09-01' })
    expect(shiftDate('2024-03-01', -1)).toBe('2024-02-29')
  })
  it.each([['', '2026-08-31'], ['2026-02-30', '2026-08-31'], ['2026-08-31', '2026-08-01'],
    ['2026-09-01', '2026-09-30'], ['2020-01-01', '2026-08-31']])('rejects invalid or identical comparison %s to %s', (start, end) => {
    expect(() => baselineFromInputs(start, end, current)).toThrow()
  })
})
