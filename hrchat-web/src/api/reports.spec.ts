import { describe, expect, it, vi } from 'vitest'
import { createExport, getChartData, getDimValues, getInsight, getTableData } from './reports'
import { get, post } from './http'

vi.mock('./http', () => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}))

describe('api/reports', () => {
  it('getChartData 请求 chart-data 端点（无下钻不带 dimValue）', () => {
    getChartData(7, 23)
    expect(get).toHaveBeenCalledWith('/reports/7/components/23/chart-data', { params: undefined })
  })

  it('getChartData 下钻时透传 dimValue', () => {
    getChartData(7, 23, '研发中心')
    expect(get).toHaveBeenCalledWith('/reports/7/components/23/chart-data', {
      params: { dimValue: '研发中心' },
    })
  })

  it('getTableData 请求 data 端点并携带筛选', () => {
    getTableData(7, 24, { page: 2, size: 10, sortField: 'headcount', sortOrder: 'desc', dimValue: ['研发一部'] })
    expect(get).toHaveBeenCalledWith('/reports/7/components/24/data', {
      params: {
        page: 2,
        size: 10,
        sortField: 'headcount',
        sortOrder: 'desc',
        dimValue: ['研发一部'],
      },
    })
  })

  it('getDimValues 请求 dim-values 端点', () => {
    getDimValues(7, 24)
    expect(get).toHaveBeenCalledWith('/reports/7/components/24/dim-values')
  })

  it('getInsight 请求 insight 端点', () => {
    getInsight(7, 23)
    expect(get).toHaveBeenCalledWith('/reports/7/components/23/insight')
  })

  it('createExport 透传 PDF 格式', () => {
    createExport(7, { format: 'PDF' })
    expect(post).toHaveBeenCalledWith('/reports/7/exports', { format: 'PDF' })
  })
})
