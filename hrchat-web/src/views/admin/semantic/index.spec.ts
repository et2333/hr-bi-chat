import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import * as semanticApi from '@/api/semantic'
import SemanticAdmin from './index.vue'
import type {
  ApprovalTodoItem,
  MetricDetail,
  MetricSummary,
  MetricVersionInfo,
} from '@/api/semantic'

const { getMock, postMock, patchMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  patchMock: vi.fn(),
  delMock: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  get: getMock,
  post: postMock,
  patch: patchMock,
  put: vi.fn(),
  del: delMock,
  delete: delMock,
  http: {},
  USER_NO_KEY: 'hrchat_user_no',
  TENANT_NO_KEY: 'hrchat_tenant_no',
  default: {},
}))

// 角色可在各用例间切换（ADMIN 含审批权限，DATA_ADMIN 不含）
const roleState = vi.hoisted(() => ({ role: 'ADMIN', empNo: 'adm01' }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    currentUser: { empNo: roleState.empNo, name: '用户', role: roleState.role },
  }),
}))

const pageOf = (records: unknown[]) => ({
  code: 'SUCCESS',
  message: 'ok',
  traceId: 't',
  data: { records, total: records.length, page: 1, size: 20 },
})

const METRIC: MetricSummary = {
  id: 1,
  code: 'headcount',
  name: '在职人数',
  domain: 'staff',
  status: 1,
  effectiveVersion: 1,
  pendingVersion: 2,
  updatedAt: '2026-09-28T10:00:00',
}

const DETAIL: MetricDetail = {
  id: 1,
  code: 'headcount',
  name: '在职人数',
  domain: 'staff',
  formulaExpr: 'SELECT COUNT(*) FROM dim_employee',
  calcScope: '口径：期末在职人数',
  defaultPeriod: 'MONTH',
  goodDirection: 1,
  status: 1,
  effectiveVersion: 1,
  pendingVersion: 2,
  permLevel: 1,
  availableDimensions: ['org'],
  updatedBy: 'dat01',
  updatedAt: '2026-09-28T10:00:00',
}

const VERSIONS: MetricVersionInfo[] = [
  {
    versionNo: 2,
    formulaExpr: 'SELECT COUNT(*) FROM dim_employee WHERE x=1',
    calcScope: '口径：期末在职人数（新）',
    status: 0,
    submittedBy: 'dat01',
    approvedBy: null,
    effectiveAt: null,
    changeNote: '口径/公式变更提交审批',
  },
  {
    versionNo: 1,
    formulaExpr: 'SELECT COUNT(*) FROM dim_employee',
    calcScope: '口径：期末在职人数',
    status: 1,
    submittedBy: 'dat01',
    approvedBy: 'adm01',
    effectiveAt: '2026-09-01T00:00:00',
    changeNote: '初始发布',
  },
]

const TODO: ApprovalTodoItem = {
  approvalId: 11,
  metricId: 1,
  metricCode: 'headcount',
  metricName: '在职人数',
  versionNo: 2,
  submittedBy: 'dat01',
  submittedAt: '2026-09-28T10:00:00',
  calcScope: '口径：期末在职人数（新）',
  formulaExpr: 'SELECT COUNT(*) FROM dim_employee WHERE x=1',
}

const LINEAGE = [
  {
    reportId: 1,
    reportName: '人力编制报表',
    componentId: 10,
    compType: 1,
    chartType: 'BAR',
  },
]

describe('api/semantic', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: {} })
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: 1 })
    patchMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: null })
    delMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: null })
  })

  it('指标接口走对应方法与路径', async () => {
    await semanticApi.listMetrics({ domain: 'staff', status: 1, keyword: '人', page: 2, size: 10 })
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/metrics', {
      params: { domain: 'staff', status: 1, keyword: '人', page: 2, size: 10 },
    })

    const createBody = {
      name: 'N', code: 'c', domain: 'staff', definition: 'd', formula: 'f',
      defaultGrain: 'MONTH', availableDimensions: [], sensitive: false,
    }
    await semanticApi.createMetric(createBody)
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/metrics', createBody)

    await semanticApi.getMetric(3)
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/metrics/3')

    await semanticApi.patchMetric(3, { name: 'N2' })
    expect(patchMock).toHaveBeenCalledWith('/admin/semantic/metrics/3', { name: 'N2' })

    await semanticApi.listMetricVersions(3)
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/metrics/3/versions')

    await semanticApi.submitMetricApproval(3)
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/metrics/3:submit-approval', {})

    await semanticApi.approveMetric(11)
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/approvals/11:approve', {})

    await semanticApi.rejectMetric(11, '原因')
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/approvals/11:reject', { comment: '原因' })

    await semanticApi.deleteMetric(7)
    expect(delMock).toHaveBeenCalledWith('/admin/semantic/metrics/7')
    await semanticApi.setMetricStatus(7, 0)
    expect(patchMock).toHaveBeenCalledWith('/admin/semantic/metrics/7/status', { status: 0 })
    await semanticApi.getMetricLineage(7)
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/metrics/7/lineage')
    await semanticApi.listApprovalTodos()
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/approvals/todo')
  })

  it('维度/同义词接口走对应方法与路径', async () => {
    await semanticApi.listDimensions({ keyword: '组', page: 1, size: 20 })
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/dimensions', {
      params: { keyword: '组', page: 1, size: 20 },
    })

    const dimBody = { name: 'N', code: 'c', dimType: 2, enumValues: [] }
    await semanticApi.createDimension(dimBody)
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/dimensions', dimBody)

    await semanticApi.patchDimension(5, dimBody)
    expect(patchMock).toHaveBeenCalledWith('/admin/semantic/dimensions/5', dimBody)

    await semanticApi.getDimension(5)
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/dimensions/5')

    await semanticApi.listSynonyms({ keyword: '流失', page: 1, size: 20 })
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/synonyms', {
      params: { keyword: '流失', page: 1, size: 20 },
    })

    const synBody = { group: '流失率', terms: ['流失'], target: 'metric:turnover_rate' }
    await semanticApi.createSynonym(synBody)
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/synonyms', synBody)

    await semanticApi.deleteSynonym(9)
    expect(delMock).toHaveBeenCalledWith('/admin/semantic/synonyms/9')

    await semanticApi.deleteDimension(6)
    expect(delMock).toHaveBeenCalledWith('/admin/semantic/dimensions/6')
    await semanticApi.getDimensionLineage(6)
    expect(getMock).toHaveBeenCalledWith('/admin/semantic/dimensions/6/lineage')
  })
})

describe('views/admin/semantic', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roleState.role = 'ADMIN'
    roleState.empNo = 'adm01'
    postMock.mockImplementation((url: string) => {
      if (url.includes(':submit-approval')) {
        return Promise.resolve({
          code: 'SUCCESS', message: 'ok', traceId: 't',
          data: { approvalId: 11, status: 'PENDING' },
        })
      }
      return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't', data: 1 })
    })
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/semantic/metrics/1/versions') {
        return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't', data: VERSIONS })
      }
      if (url === '/admin/semantic/metrics/1') {
        return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't', data: DETAIL })
      }
      if (url === '/admin/semantic/metrics/1/lineage') {
        return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't', data: LINEAGE })
      }
      if (url === '/admin/semantic/approvals/todo') {
        return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't', data: [TODO] })
      }
      if (url === '/admin/semantic/metrics') return Promise.resolve(pageOf([METRIC]))
      if (url === '/admin/semantic/dimensions') {
        return Promise.resolve(pageOf([{ id: 1, code: 'org', name: '组织', dimType: 1 }]))
      }
      if (url === '/admin/semantic/synonyms') {
        return Promise.resolve(pageOf([{ id: 1, termGroup: '人数', targetType: 1, targetId: 1, hitCount: 0 }]))
      }
      return Promise.resolve(pageOf([]))
    })
  })

  it('默认渲染指标管理：筛选行、表格列与数据行', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('指标管理')
    expect(text).toContain('新建指标')
    expect(text).toContain('指标编码')
    expect(text).toContain('主题域')
    expect(text).toContain('生效版本')
    expect(text).toContain('待审批')
    expect(text).toContain('headcount')
    expect(text).toContain('在职人数')
    expect(text).toContain('v2 待审批')
  })

  it('点详情打开 Drawer：展示口径/公式/版本时间线，提交审批后可审批通过', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '') === '详情')!.trigger('click')
    await flushPromises()

    // Drawer 内容经 Teleport 挂载到 document.body
    const bodyText = () => document.body.textContent ?? ''
    expect(bodyText()).toContain('口径：期末在职人数')
    expect(bodyText()).toContain('SELECT COUNT(*) FROM dim_employee')
    expect(bodyText()).toContain('版本历史')
    expect(bodyText()).toContain('初始发布')

    // 提交审批 → 返回 approvalId
    // antd 对两个汉字的按钮自动插空格（“驳 回”），归一化空白后比较
    const findBodyButton = (text: string) =>
      Array.from(document.querySelectorAll('button')).find(
        (b) => (b.textContent ?? '').replace(/\s/g, '') === text.replace(/\s/g, ''),
      )
    const submitBtn = findBodyButton('提交审批')
    expect(submitBtn).toBeTruthy()
    await submitBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(postMock).toHaveBeenCalledWith('/admin/semantic/metrics/1:submit-approval', {})

    // ADMIN 身份出现审批通过/驳回按钮
    expect(bodyText()).toContain('审批通过')
    expect(findBodyButton('驳回')).toBeTruthy()
  })

  it('切换到维度管理与同义词管理 Tab', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    const clickTab = async (name: string) => {
      const tab = wrapper.findAll('.ant-tabs-tab').find((el) => el.text().includes(name))
      expect(tab).toBeTruthy()
      await tab!.trigger('click')
      await flushPromises()
    }

    await clickTab('维度管理')
    expect(wrapper.text()).toContain('新建维度')
    expect(wrapper.text()).toContain('结构维度')

    await clickTab('同义词管理')
    expect(wrapper.text()).toContain('新增同义词组')
    expect(wrapper.text()).toContain('归一目标')
    expect(wrapper.text()).toContain('人数')
  })

  it('新建维度 Drawer 暴露通用物理映射配置（来源表/主键/取值/父键/外键/时效）', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    const tab = wrapper.findAll('.ant-tabs-tab').find((el) => el.text().includes('维度管理'))
    await tab!.trigger('click')
    await flushPromises()
    const createBtn = wrapper.findAll('button').find((b) => b.text().includes('新建维度'))
    expect(createBtn).toBeTruthy()
    await createBtn!.trigger('click')
    await flushPromises()

    const bodyText = () => document.body.textContent ?? ''
    expect(bodyText()).toContain('来源表')
    expect(bodyText()).toContain('主键列')
    expect(bodyText()).toContain('取值列')
    expect(bodyText()).toContain('层级父键列')
    expect(bodyText()).toContain('事实表外键列')
    expect(bodyText()).toContain('时效标记列')
  })

  it('指标操作列含停用/删除按钮', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    const buttons = wrapper.findAll('button').map((b) => b.text().replace(/\s/g, ''))
    expect(buttons).toContain('停用')
    expect(buttons).toContain('删除')
  })

  it('Drawer 展示引用血缘，版本≥2 时可打开版本对比', async () => {
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '') === '详情')!.trigger('click')
    await flushPromises()

    const bodyText = () => document.body.textContent ?? ''
    expect(bodyText()).toContain('引用关系')
    expect(bodyText()).toContain('人力编制报表')

    const diffBtn = Array.from(document.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').replace(/\s/g, '') === '版本对比',
    )
    expect(diffBtn).toBeTruthy()
    await diffBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(bodyText()).toContain('口径变化')
    expect(bodyText()).toContain('公式变化')
  })

  it('ADMIN 可见审批待办 Tab，面板渲染待办并可审批通过', async () => {
    const wrapper = mount(SemanticAdmin, {
      global: { plugins: [Antd] },
      attachTo: document.body,
    })
    await flushPromises()

    const tab = wrapper.findAll('.ant-tabs-tab').find((el) => el.text().includes('审批待办'))
    expect(tab).toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()

    const bodyText = () => document.body.textContent ?? ''
    expect(bodyText()).toContain('dat01')
    expect(bodyText()).toContain('口径：期末在职人数（新）')

    // 通过按钮 → popconfirm 确认
    const passBtn = Array.from(document.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').replace(/\s/g, '') === '通过',
    )
    expect(passBtn).toBeTruthy()
    await passBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    // popconfirm 在无全局 locale 的测试环境下按钮为 OK/Cancel
    const okBtn = Array.from(document.querySelectorAll('.ant-popconfirm button'))
      .find((b) => (b.textContent ?? '').replace(/\s/g, '') === 'OK')
    expect(okBtn).toBeTruthy()
    await okBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/admin/semantic/approvals/11:approve', {})
  })

  it('DATA_ADMIN 不渲染审批待办 Tab', async () => {
    roleState.role = 'DATA_ADMIN'
    roleState.empNo = 'dat01'
    const wrapper = mount(SemanticAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    const tabs = wrapper.findAll('.ant-tabs-tab').map((el) => el.text())
    expect(tabs.some((t) => t.includes('审批待办'))).toBe(false)
  })
})
