import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd, { message } from 'ant-design-vue'
import * as userApi from '@/api/users'
import UserAdmin from './index.vue'
import type { UserView } from '@/api/users'

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

const USER_RECORD: UserView = {
  id: 1,
  empNo: 'emp001',
  displayName: '张三',
  email: 'zhangsan@example.com',
  orgNodeId: 2,
  orgName: '研发中心',
  status: 1,
  tenantId: 't01',
  mustChangePwd: 1,
  roles: ['HRBP'],
  lastLoginAt: null,
  createdAt: '2026-09-28T10:00:00',
}

const ROLES = {
  code: 'SUCCESS',
  message: 'ok',
  traceId: 't1',
  data: {
    records: [{ roleId: 1, roleCode: 'HRBP', roleName: 'HRBP', dataLevel: 1, functionPerms: [] }],
    total: 1,
    page: 1,
    size: 100,
  },
}

describe('api/users', () => {
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

  it('listUsers 透传分页/关键词参数并返回响应', async () => {
    const res = await userApi.listUsers(2, 10, '张三')
    expect(getMock).toHaveBeenCalledWith('/admin/users', { params: { page: 2, size: 10, keyword: '张三' } })
    expect(res.data.records).toEqual([])
  })

  it('create/patchStatus/assignRoles/resetPassword 走对应方法与路径', async () => {
    postMock.mockResolvedValueOnce({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: { userId: 9, initialPassword: 'p@ss1234' } })
    const body = { empNo: 'emp009', displayName: '李四', email: 'lisi@example.com', orgNodeId: 2, roleCodes: ['HRBP'] }
    const res = await userApi.createUser(body)
    expect(postMock).toHaveBeenCalledWith('/admin/users', body)
    expect(res.data.initialPassword).toBe('p@ss1234')

    await userApi.patchUserStatus(3, 0)
    expect(patchMock).toHaveBeenCalledWith('/admin/users/3', { status: 0 })

    await userApi.assignUserRoles(4, ['HRBP', 'ADMIN'])
    expect(postMock).toHaveBeenCalledWith('/admin/users/4/roles', { roleCodes: ['HRBP', 'ADMIN'] })

    postMock.mockResolvedValueOnce({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: { userId: 5, initialPassword: 'n3w@pass' } })
    const reset = await userApi.resetUserPassword(5)
    expect(postMock).toHaveBeenCalledWith('/admin/users/5:reset-password')
    expect(reset.data.initialPassword).toBe('n3w@pass')
  })
})

describe('views/admin/users', () => {
  let successSpy: MockInstance<Parameters<typeof message.success>, ReturnType<typeof message.success>>

  beforeEach(() => {
    vi.clearAllMocks()
    successSpy = vi.spyOn(message, 'success')
    getMock.mockImplementation((url: string) => {
      if (url === '/admin/authz/roles') {
        return Promise.resolve(ROLES)
      }
      return Promise.resolve({
        code: 'SUCCESS',
        message: 'ok',
        traceId: 't',
        data: { records: [USER_RECORD], total: 1, page: 1, size: 20 },
      })
    })
    postMock.mockResolvedValue({
      code: 'SUCCESS',
      message: 'ok',
      traceId: 't',
      data: { userId: 9, initialPassword: 'P@ssw0rd' },
    })
    patchMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't', data: null })
  })

  it('渲染用户表格列、数据行与工具条', async () => {
    const wrapper = mount(UserAdmin, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    // 工具条
    expect(text).toContain('新建用户')

    // 表格列
    expect(text).toContain('工号')
    expect(text).toContain('姓名')
    expect(text).toContain('邮箱')
    expect(text).toContain('组织')
    expect(text).toContain('角色')
    expect(text).toContain('状态')
    expect(text).toContain('租户')
    expect(text).toContain('是否需改密')
    expect(text).toContain('创建时间')

    // 数据行：工号/姓名/组织/状态 Tag/需改密 Tag/角色 Tag
    expect(text).toContain('emp001')
    expect(text).toContain('张三')
    expect(text).toContain('研发中心')
    expect(text).toContain('启用')
    expect(text).toContain('需改密')
    expect(text).toContain('HRBP')

    // 操作按钮
    expect(text).toContain('分配角色')
    expect(text).toContain('重置密码')
  })

  it('新建用户成功后展示一次初始密码提示', async () => {
    const wrapper = mount(UserAdmin, { global: { plugins: [Antd] } })
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text().includes('新建用户'))
    await createBtn!.trigger('click')
    await flushPromises()

    // Modal 内容渲染在 body（teleport），按占位符顺序填：工号/姓名/邮箱
    const inputs = document.body.querySelectorAll('.ant-modal input')
    const setInput = (el: Element, value: string) => {
      const input = el as HTMLInputElement
      input.value = value
      input.dispatchEvent(new Event('input'))
    }
    setInput(inputs[0], 'emp009')
    setInput(inputs[1], '李四')
    setInput(inputs[2], 'lisi@example.com')
    await flushPromises()

    // 点 Modal 确定
    const okBtn = document.body.querySelector('.ant-modal .ant-btn-primary') as HTMLButtonElement
    okBtn.click()
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/admin/users', expect.objectContaining({ empNo: 'emp009' }))
    expect(successSpy).toHaveBeenCalledWith(expect.stringContaining('初始密码：P@ssw0rd'))
    // 弹窗提示（只展示一次）
    expect(document.body.textContent).toContain('初始密码：P@ssw0rd')
  })
})
