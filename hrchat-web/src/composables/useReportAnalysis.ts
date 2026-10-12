import { getReportAnalysisContext, startReportAnalysis } from '@/api/reports'
import type { ReportActionRef, ReportAnalysisContext, ReportAnalysisPrefill } from '@/api/reports'
import { TENANT_NO_KEY, USER_NO_KEY } from '@/api/http'
import { useAnalysisSession } from './useAnalysisSession'

export interface ReportAnalysisParams {
  reportId: number
  dataVersionId: number
  capabilityId: string
  /** A2：服务端动作引用；提供时按原视图/期间恢复来源，不传期间/组织（缺失时退回版本级来源）。 */
  actionRef?: ReportActionRef | null
}

/**
 * 报表来源分析入口：确认两期后创建任务，进度/取消/事件复用既有任务端点。
 * 会话键在取得合法来源（有效报表版本 + 能力）后才建立并冻结：初始 `dataVersion=0`/空 capability
 * 不产生恢复键，切换主图或收起面板不会新建或改写运行中任务。
 */
export function useReportAnalysis(params: () => ReportAnalysisParams) {
  const identity = [localStorage.getItem(TENANT_NO_KEY) || 't01', localStorage.getItem(USER_NO_KEY) || 'hr01']
  // 冻结本次会话的来源副本：prepare/start 共用同一来源，运行中 props 变化不改变任务参数；
  // 来源未就绪时不冻结，resetSession 时清除以便按新来源重新解析（A5）。
  let frozen: ReportAnalysisParams | null = null
  const frozenSource = (): ReportAnalysisParams => {
    if (frozen) return frozen
    const current = params()
    if (current.reportId && current.dataVersionId > 0 && current.capabilityId) frozen = current
    return current
  }
  return useAnalysisSession<ReportAnalysisPrefill, ReportAnalysisContext>({
    resolveKey: () => {
      const current = frozenSource()
      // 来源未就绪（初始 props 空值或版本未生成）时返回 null，禁止坐实空键。
      if (!current.reportId || current.dataVersionId <= 0 || !current.capabilityId) return null
      const ref = current.actionRef
      // A2：动作引用参与会话键，避免同一能力的不同视图/动作复用同一恢复键；
      // 无引用时保持旧的版本级键，避免破坏既有恢复数据。
      const parts: (string | number)[] = [...identity, current.reportId, current.dataVersionId, current.capabilityId]
      if (ref) parts.push(ref.componentId, ref.viewKey, ref.actionId)
      return `hrchat_report_analysis:${JSON.stringify(parts)}`
    },
    loadContext: () => {
      const current = frozenSource()
      const ref = current.actionRef
      // 无动作引用时保持三参调用（版本级来源），避免向端点发送空引用。
      return (ref
        ? getReportAnalysisContext(current.reportId, current.dataVersionId, current.capabilityId, ref)
        : getReportAnalysisContext(current.reportId, current.dataVersionId, current.capabilityId)
      ).then((res) => res.data)
    },
    startTask: (baseline, mode, key) => {
      const current = frozenSource()
      return startReportAnalysis(current.reportId, {
        dataVersionId: current.dataVersionId, baselinePeriod: baseline, mode,
        componentId: current.actionRef?.componentId,
        viewKey: current.actionRef?.viewKey,
        actionId: current.actionRef?.actionId,
      }, key).then((res) => res.data)
    },
    onReset: () => { frozen = null },
  })
}
