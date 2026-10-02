import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import * as llmApi from '@/api/llm'
import LlmAdmin from './index.vue'
import type { LlmModelView } from '@/api/llm'

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

const RECORD: LlmModelView = {
  id: 1,
  modelCode: 'llm-demo',
  modelName: '演示模型',
  vendor: 'mock',
  model: 'mock-1',
  status: 1,
  deployState: 'ACTIVE',
  healthStatus: 'UP',
  versionCount: 3,
  updatedAt: '2026-09-28T10:00:00',
}

describe('api/llm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue({
      code: 'SUCCESS',
      message: 'ok',
      traceId: 't1',
      data: { records: [], total: 0, page: 1, size: 20 },
    })
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: 1 })
    patchMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
    delMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
  })

  it('listLlmModels 透传分页/关键词参数并返回响应', async () => {
    const res = await llmApi.listLlmModels(2, 10, 'demo')
    expect(getMock).toHaveBeenCalledWith('/admin/llm/models', { params: { page: 2, size: 10, keyword: 'demo' } })
    expect(res.data.records).toEqual([])
  })

  it('create/patch/delete/deploy/rollback/versions/health/monitor 走对应方法与路径', async () => {
    const body = { modelCode: 'm1', modelName: 'M1', vendor: 'mock' }
    await llmApi.createLlmModel(body)
    expect(postMock).toHaveBeenCalledWith('/admin/llm/models', body)

    await llmApi.patchLlmModel(3, body)
    expect(patchMock).toHaveBeenCalledWith('/admin/llm/models/3', body)

    await llmApi.deleteLlmModel(4)
    expect(delMock).toHaveBeenCalledWith('/admin/llm/models/4')

    await llmApi.deployLlmModel(1)
    expect(postMock).toHaveBeenCalledWith('/admin/llm/models/1:deploy')

    await llmApi.rollbackLlmModel(1, { versionId: 2 })
    expect(postMock).toHaveBeenCalledWith('/admin/llm/models/1:rollback', { versionId: 2 })

    await llmApi.listLlmVersions(1)
    expect(getMock).toHaveBeenCalledWith('/admin/llm/models/1/versions')

    await llmApi.checkLlmHealth(1)
    expect(getMock).toHaveBeenCalledWith('/admin/llm/models/1/health')

    await llmApi.getLlmMonitor()
    expect(getMock).toHaveBeenCalledWith('/admin/llm/monitor')
  })
})

describe('views/admin/llm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/llm/monitor') {
        return Promise.resolve({
          code: 'SUCCESS',
          message: 'ok',
          traceId: 't',
          data: { total: 5, active: 3, failed: 1, degraded: 1, items: [] },
        })
      }
      return Promise.resolve({
        code: 'SUCCESS',
        message: 'ok',
        traceId: 't',
        data: { records: [RECORD], total: 1, page: 1, size: 20 },
      })
    })
  })

  it('渲染监控概览统计、模型表格列与操作按钮', async () => {
    const wrapper = mount(LlmAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    // 监控概览 4 项统计
    expect(text).toContain('监控概览')
    expect(text).toContain('总模型')
    expect(text).toContain('已部署')
    expect(text).toContain('失败')
    expect(text).toContain('降级')
    expect(text).toContain('5')
    expect(text).toContain('1')

    // 工具条
    expect(text).toContain('新建模型')

    // 表格列
    expect(text).toContain('模型编码')
    expect(text).toContain('部署状态')
    expect(text).toContain('健康')
    expect(text).toContain('版本数')
    expect(text).toContain('更新时间')

    // 数据行：编码/名称/状态 Tag/部署状态 Tag
    expect(text).toContain('llm-demo')
    expect(text).toContain('演示模型')
    expect(text).toContain('启用')
    expect(text).toContain('ACTIVE')

    // 操作按钮
    expect(text).toContain('编辑')
    expect(text).toContain('部署')
    expect(text).toContain('版本')
    expect(text).toContain('健康')
    expect(text).toContain('删除')
  })

  it('将 SIMULATED 状态展示为模拟生效', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/llm/monitor') {
        return Promise.resolve({
          code: 'SUCCESS', message: 'ok', traceId: 't',
          data: { total: 1, active: 0, failed: 0, degraded: 0, items: [] },
        })
      }
      return Promise.resolve({
        code: 'SUCCESS', message: 'ok', traceId: 't',
        data: { records: [{ ...RECORD, deployState: 'SIMULATED' }], total: 1, page: 1, size: 20 },
      })
    })

    const wrapper = mount(LlmAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    expect(wrapper.text()).toContain('模拟生效')
  })
})
