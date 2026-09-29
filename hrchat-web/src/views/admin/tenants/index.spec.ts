import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd, { message } from 'ant-design-vue'
import * as tenantApi from '@/api/tenants'
import TenantAdmin from './index.vue'
import type { TenantView } from '@/api/tenants'

const { getMock, postMock, patchMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  patchMock: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  get: getMock,
  post: postMock,
  patch: patchMock,
  put: vi.fn(),
  del: vi.fn(),
  delete: vi.fn(),
  http: {},
  USER_NO_KEY: 'hrchat_user_no',
  TENANT_NO_KEY: 'hrchat_tenant_no',
  default: {},
}))

const TENANT_RECORD: TenantView = {
  id: 1,
  tenantCode: 't01',
  tenantName: '演示租户',
  status: 1,
  userQuota: 500,
  reportQuota: 200,
  subscriptionQuota: 50,
  apiDailyQuota: 10000,
  createdAt: '2026-09-28T10:00:00',
}

describe('api/tenants', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue({
      code: 'SUCCESS',
      message: 'ok',
      traceId: 't1',
      data: { records: [], total: 0, page: 1, size: 20 },
    })
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
    patchMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
  })

  it('listTenants 透传分页/关键词参数并返回响应', async () => {
    const res = await tenantApi.listTenants(2, 10, 't01')
    expect(getMock).toHaveBeenCalledWith('/admin/tenants', { params: { page: 2, size: 10, keyword: 't01' } })
    expect(res.data.records).toEqual([])
  })

  it('create/patch/disable/enable/usage 走对应方法与路径', async () => {
    const body = { tenantCode: 't03', tenantName: '演示租户C', userQuota: 100, reportQuota: 20, subscriptionQuota: 10, apiDailyQuota: 1000 }
    const res = await tenantApi.createTenant(body)
    expect(postMock).toHaveBeenCalledWith('/admin/tenants', body)
    expect(res.data).toBeNull()

    const patchBody = { tenantName: '演示租户C2', userQuota: 200, reportQuota: 40, subscriptionQuota: 20, apiDailyQuota: 2000 }
    await tenantApi.patchTenant(3, patchBody)
    expect(patchMock).toHaveBeenCalledWith('/admin/tenants/3', patchBody)

    await tenantApi.disableTenant(3)
    expect(postMock).toHaveBeenCalledWith('/admin/tenants/3:disable')

    await tenantApi.enableTenant(3)
    expect(postMock).toHaveBeenCalledWith('/admin/tenants/3:enable')

    await tenantApi.getTenantUsage(3)
    expect(getMock).toHaveBeenCalledWith('/admin/tenants/3/usage')
  })
})

describe('views/admin/tenants', () => {
  let successSpy: MockInstance<Parameters<typeof message.success>, ReturnType<typeof message.success>>

  beforeEach(() => {
    vi.clearAllMocks()
    successSpy = vi.spyOn(message, 'success')
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/tenants/1/usage') {
        return Promise.resolve({
          code: 'SUCCESS',
          message: 'ok',
          traceId: 't',
          data: {
            tenantCode: 't01',
            userUsed: 10,
            userQuota: 500,
            reportUsed: 5,
            reportQuota: 200,
            subscriptionUsed: 2,
            subscriptionQuota: 50,
            apiDailyUsed: 100,
            apiDailyQuota: 10000,
          },
        })
      }
      return Promise.resolve({
        code: 'SUCCESS',
        message: 'ok',
        traceId: 't',
        data: { records: [TENANT_RECORD], total: 1, page: 1, size: 20 },
      })
    })
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: null })
    patchMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: null })
  })

  it('渲染租户表格列、数据行与工具条', async () => {
    const wrapper = mount(TenantAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    // 工具条
    expect(text).toContain('新建租户')

    // 表格列
    expect(text).toContain('租户编码')
    expect(text).toContain('名称')
    expect(text).toContain('状态')
    expect(text).toContain('用户配额')
    expect(text).toContain('报表配额')
    expect(text).toContain('订阅配额')
    expect(text).toContain('API 日配额')
    expect(text).toContain('创建时间')

    // 数据行：编码/名称/状态 Tag
    expect(text).toContain('t01')
    expect(text).toContain('演示租户')
    expect(text).toContain('启用')

    // 操作按钮
    expect(text).toContain('编辑')
    expect(text).toContain('停用')
    expect(text).toContain('用量')
    expect(text).toContain('切换到此租户')
  })

  it('切换到此租户写入 localStorage 并提示', async () => {
    localStorage.clear()
    const wrapper = mount(TenantAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    const switchBtn = wrapper.findAll('button').find((b) => b.text().includes('切换到此租户'))
    await switchBtn!.trigger('click')
    await flushPromises()

    // 确认 popconfirm
    const confirmBtn = document.body.querySelector('.ant-popover .ant-btn-primary') as HTMLButtonElement
    confirmBtn.click()
    await flushPromises()

    expect(localStorage.getItem('hrchat_tenant_no')).toBe('t01')
    expect(successSpy).toHaveBeenCalledWith(expect.stringContaining('已切换到租户「演示租户」'))
  })
})
