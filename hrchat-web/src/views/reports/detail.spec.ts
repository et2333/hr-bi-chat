import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import Detail from './detail.vue'

/** happy-dom 无 canvas：mock echarts，仅验证洞察卡片内容。 */
vi.mock('echarts', () => ({
  init: vi.fn(() => ({
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
    off: vi.fn(),
    on: vi.fn(),
  })),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '7' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

const { getMock, postMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  delMock: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  get: getMock,
  post: postMock,
  patch: vi.fn(),
  put: vi.fn(),
  del: delMock,
  delete: delMock,
  http: {},
  USER_NO_KEY: 'hrchat_user_no',
  TENANT_NO_KEY: 'hrchat_tenant_no',
  default: {},
}))

const ok = (data: unknown) => ({ code: 'SUCCESS', message: 'ok', traceId: 't1', data })

const REPORT = {
  id: 7,
  name: '在职人数报表',
  sourceType: 'CUSTOM',
  currentDataVersionId: 7,
  definitionRevision: 1,
  canRefresh: true,
  params: {},
  components: [{ componentId: 23, compType: 'CHART', chartType: 'BAR', def: {} }],
  refresh: 'DAILY',
  createdAt: '2026-09-28T10:00:00',
  subscriptions: [],
}

/** R5 富洞察：summary/points 来自 narrative，claims+facts 供证据展开。 */
function analysis(overrides: Record<string, unknown> = {}) {
  return {
    dataVersion: '7',
    viewKey: 'root',
    dimValue: null,
    chartStatus: 'READY',
    insightStatus: 'COMPLETE',
    taskId: 1,
    attempt: 1,
    chart: {
      chartType: 'BAR',
      categories: ['全部'],
      series: [{ name: '在职人数', data: [2] }],
      pieData: [],
    },
    insight: {
      reportName: '在职人数报表',
      metricName: '在职人数',
      summary: '在职人数出现明显变化',
      points: [{ type: 'finding', label: '在职人数出现明显变化' }],
      limitations: [],
      actions: [],
      schemaVersion: '2',
      claims: [{
        claim_id: 'c1',
        kind: 'finding',
        text: '在职人数出现明显变化',
        segments: [{ type: 'text', text: '在职人数出现明显变化' }],
        fact_ids: ['overall'],
      }],
      narrative: { summary_claim_id: 'c1', finding_claim_ids: [] },
      facts: [{
        fact_id: 'overall',
        fact_type: 'change',
        value: 1,
        unit: '人',
        period_ref: '2026-09 / 2026-08',
        scope_ref: '全公司',
        basis: 'absolute_change',
        snapshot_id: 'snap-7',
        computable: true,
      }],
    },
    source: {
      reportId: 7,
      componentId: 23,
      dataVersion: '7',
      viewKey: 'root',
      snapshotId: 'snap-7',
      scopeRef: 'scope-1',
    },
    canEnsure: false,
    canRetry: false,
    ...overrides,
  }
}

let analysisResponse = analysis()

async function mountDetail() {
  const wrapper = mount(Detail, {
    global: {
      plugins: [Antd],
      stubs: { ReportAnalysisPanel: true },
    },
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('views/reports/detail 洞察卡片（R5）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    analysisResponse = analysis()
    getMock.mockImplementation((url: string) => {
      if (url === '/reports/7') return Promise.resolve(ok(REPORT))
      if (url === '/reports/7/components/23/chart-analysis') return Promise.resolve(ok(analysisResponse))
      return Promise.resolve(ok(null))
    })
    postMock.mockResolvedValue(ok(null))
    delMock.mockResolvedValue(ok(null))
  })

  it('展示模型正文而非 Fact.label，并可按期间/范围/依据/快照展开证据', async () => {
    const wrapper = await mountDetail()
    const text = wrapper.text()

    expect(text).toContain('在职人数出现明显变化')
    expect(text).toContain('查看证据（1 条）')
    const evidence = wrapper.find('.insight-evidence')
    expect(evidence.exists()).toBe(true)
    expect(evidence.text()).toContain('期间 2026-09 / 2026-08')
    expect(evidence.text()).toContain('范围 全公司')
    expect(evidence.text()).toContain('依据 absolute_change')
    expect(evidence.text()).toContain('快照 snap-7')
  })

  it('打开推荐动作只回传服务端动作引用（A2）', async () => {
    analysisResponse = analysis({
      insight: {
        ...analysis().insight,
        actions: [{
          actionId: 'act-1',
          capabilityId: 'leave_count_department_contribution',
          question: '查看部门贡献',
          executable: true,
        }],
      },
    })
    const wrapper = await mountDetail()
    const action = wrapper.find('.insight-actions .action-clickable')
    expect(action.exists()).toBe(true)
    await action.trigger('click')

    const panel = wrapper.findComponent({ name: 'ReportAnalysisPanel' })
    expect(panel.props('componentId')).toBe(23)
    expect(panel.props('viewKey')).toBe('root')
    expect(panel.props('actionId')).toBe('act-1')
    expect(panel.props('dataVersionId')).toBe(7)
    wrapper.unmount()
  })

  it('降级时显示明确降级说明，不伪装成正常 AI 解读', async () => {
    analysisResponse = analysis({
      insightStatus: 'DEGRADED',
      insight: {
        reportName: '在职人数报表',
        metricName: '在职人数',
        summary: '本期在职人数为 2 人。',
        points: [],
        limitations: [],
        actions: [],
        degradationReason: 'draft_validation_failed',
        claims: [],
        narrative: null,
        facts: [],
      },
    })
    const wrapper = await mountDetail()
    const text = wrapper.text()

    expect(text).toContain('降级说明：draft_validation_failed')
    expect(wrapper.find('.insight-evidence').exists()).toBe(false)
  })
})
