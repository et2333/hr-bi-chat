import { startAnalysis, getAnalysisContext } from '@/api/attribution'
import type { AnalysisContext, AnalysisTask } from '@/api/attribution'
import { TENANT_NO_KEY, USER_NO_KEY } from '@/api/http'
import { useAnalysisSession } from './useAnalysisSession'

/** 问答来源分析入口：与报表来源共用同一套任务生命周期与会话恢复语义。 */
export function useAttribution(sourceAskId: string) {
  const identity = [localStorage.getItem(TENANT_NO_KEY) || 't01', localStorage.getItem(USER_NO_KEY) || 'hr01']
  return useAnalysisSession<AnalysisContext, AnalysisContext>({
    resolveKey: () => `hrchat_analysis:${JSON.stringify([...identity, sourceAskId])}`,
    loadContext: () => getAnalysisContext(sourceAskId).then((res) => res.data),
    startTask: (baseline, mode, key) => startAnalysis(sourceAskId, baseline, key, mode)
      .then((res) => res.data as AnalysisTask<AnalysisContext>),
  })
}
