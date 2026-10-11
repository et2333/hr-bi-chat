import { getReportAnalysisContext, startReportAnalysis } from '@/api/reports'
import type { ReportAnalysisContext, ReportAnalysisPrefill } from '@/api/reports'
import { TENANT_NO_KEY, USER_NO_KEY } from '@/api/http'
import { useAnalysisSession } from './useAnalysisSession'

export interface ReportAnalysisParams {
  reportId: number
  dataVersionId: number
  capabilityId: string
}

/**
 * 报表来源分析入口：确认两期后创建任务，进度/取消/事件复用既有任务端点。
 * 收敛入口按会话（租户/用户/报表版本/能力）隔离任务 ID，切换主图不会新建或改写任务。
 */
export function useReportAnalysis(params: () => ReportAnalysisParams) {
  const identity = [localStorage.getItem(TENANT_NO_KEY) || 't01', localStorage.getItem(USER_NO_KEY) || 'hr01']
  const initial = params()
  const storageKey = `hrchat_report_analysis:${JSON.stringify([
    ...identity, initial.reportId, initial.dataVersionId, initial.capabilityId,
  ])}`
  return useAnalysisSession<ReportAnalysisPrefill, ReportAnalysisContext>({
    storageKey,
    loadContext: () => {
      const current = params()
      return getReportAnalysisContext(current.reportId, current.dataVersionId, current.capabilityId)
        .then((res) => res.data)
    },
    startTask: (baseline, mode, key) => {
      const current = params()
      return startReportAnalysis(current.reportId, {
        dataVersionId: current.dataVersionId, baselinePeriod: baseline, mode,
      }, key).then((res) => res.data)
    },
  })
}
