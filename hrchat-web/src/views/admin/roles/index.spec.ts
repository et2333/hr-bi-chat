import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import AdminRoles from './index.vue'

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  get: getMock,
  post: postMock,
  patch: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  delete: vi.fn(),
  http: {},
  USER_NO_KEY: 'hrchat_user_no',
  TENANT_NO_KEY: 'hrchat_tenant_no',
  default: {},
}))

const ROLES = {
  code: 'SUCCESS',
  message: 'ok',
  traceId: 't1',
  data: {
    records: [
      { roleId: 1, roleCode: 'ADMIN', roleName: '系统管理员', dataLevel: 1, functionPerms: ['admin:view', 'admin:llm:manage'] },
      { roleId: 2, roleCode: 'HRBP', roleName: 'HRBP', dataLevel: 2, functionPerms: [] },
    ],
    total: 2,
    page: 1,
    size: 20,
  },
}

describe('views/admin/roles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/authz/roles') return Promise.resolve(ROLES)
      return Promise.resolve({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
    })
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
  })

  it('渲染角色表格列与数据行', async () => {
    const wrapper = mount(AdminRoles, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('新建角色')
    expect(text).toContain('角色 ID')
    expect(text).toContain('编码')
    expect(text).toContain('名称')
    expect(text).toContain('数据级别')
    expect(text).toContain('ADMIN')
    expect(text).toContain('系统管理员')
    expect(text).toContain('HRBP')
    // 排障卡片
    expect(text).toContain('用户生效权限（排障）')
  })

  it('新建角色提交 createRole 请求体', async () => {
    const wrapper = mount(AdminRoles, { global: { plugins: [Antd] } })
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text().includes('新建角色'))
    await createBtn!.trigger('click')
    await flushPromises()

    const inputs = document.body.querySelectorAll('.ant-modal input')
    const setInput = (el: Element, value: string) => {
      const input = el as HTMLInputElement
      input.value = value
      input.dispatchEvent(new Event('input'))
    }
    setInput(inputs[0], 'DATA_ANALYST')
    setInput(inputs[1], '数据分析员')
    setInput(inputs[2], '2')
    await flushPromises()

    const okBtn = document.body.querySelector('.ant-modal .ant-btn-primary') as HTMLButtonElement
    okBtn.click()
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith(
      '/admin/authz/roles',
      expect.objectContaining({ roleCode: 'DATA_ANALYST', roleName: '数据分析员', dataLevel: 2 }),
    )
  })
})
