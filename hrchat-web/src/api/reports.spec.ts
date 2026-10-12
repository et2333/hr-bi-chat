import { describe, expect, it, vi } from 'vitest'
import {
  createExport,
  getChartAnalysis,
  getChartData,
  getDimValues,
  getInsight,
  getTableData,
  postInsightTask,
  retryInsightTask,
} from './reports'
import { get, post } from './http'

vi.mock('./http', () => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}))

describe('api/reports', () => {
  it('统一图文端点透传下钻条件，回溯根级清除条件', () => {
    getChartAnalysis(7, 23, '研发中心')
    expect(get).toHaveBeenLastCalledWith('/reports/7/components/23/chart-analysis', {
      params: { dimValue: '研发中心' },
    })
    getChartAnalysis(7, 23)
    expect(get).toHaveBeenLastCalledWith('/reports/7/components/23/chart-analysis', { params: undefined })
  })
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

  it('postInsightTask 幂等确保洞察任务并透传来源身份', () => {
    const src = {
      reportId: 7,
      componentId: 23,
      dataVersion: '88',
      viewKey: 'dim=org|value=%E7%A0%94%E5%8F%91%E4%B8%AD%E5%BF%83',
      snapshotId: '555',
      scopeRef: 'scope-1',
    }
    postInsightTask(7, 23, src)
    expect(post).toHaveBeenLastCalledWith('/reports/7/components/23/insight-tasks', { source: src })
  })

  it('retryInsightTask 复用快照重试指定任务并携带预期 attempt', () => {
    const src = {
      reportId: 7,
      componentId: 23,
      dataVersion: '88',
      viewKey: 'root',
      snapshotId: '555',
      scopeRef: 'scope-1',
    }
    retryInsightTask(7, 23, 99, src, 1)
    expect(post).toHaveBeenLastCalledWith('/reports/7/components/23/insight-tasks/99/retry', {
      source: src,
      expectedAttempt: 1,
    })
  })

  it('createExport 透传 PDF 格式', () => {
    createExport(7, { format: 'PDF' })
    expect(post).toHaveBeenCalledWith('/reports/7/exports', { format: 'PDF' })
  })
})
