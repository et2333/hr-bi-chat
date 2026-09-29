import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import AdminSettings from './index.vue'

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

const SETTINGS = {
  code: 'SUCCESS',
  message: 'ok',
  traceId: 't1',
  data: [
    { settingKey: 'llm.default.profile', settingValue: 'standard', description: '系统默认 LLM 档位', updatedAt: '2026-09-28T10:00:00' },
    { settingKey: 'audit.retention.days', settingValue: '180', description: '审计日志保留天数', updatedAt: '2026-09-28T09:00:00' },
  ],
}

describe('views/admin/settings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue(SETTINGS)
    postMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
    delMock.mockResolvedValue({ code: 'SUCCESS', message: 'ok', traceId: 't1', data: null })
  })

  it('渲染设置表格、默认 LLM 档位徽标与删除按钮', async () => {
    const wrapper = mount(AdminSettings, { global: { plugins: [Antd] } })
    await flushPromises()
    const text = wrapper.text()

    expect(getMock).toHaveBeenCalledWith('/admin/settings')
    expect(text).toContain('系统默认 LLM 配置')
    expect(text).toContain('llm.default.profile')
    expect(text).toContain('standard')
    expect(text).toContain('配置键')
    expect(text).toContain('audit.retention.days')
    expect(text).toContain('删除')
  })

  it('编辑默认 LLM 档位后调用 upsertSetting', async () => {
    const wrapper = mount(AdminSettings, { global: { plugins: [Antd] } })
    await flushPromises()

    const editBtn = wrapper.findAll('button').find((b) => b.text().includes('编辑'))
    await editBtn!.trigger('click')
    await flushPromises()

    const inputs = document.body.querySelectorAll('.ant-modal input')
    const setInput = (el: Element, value: string) => {
      const input = el as HTMLInputElement
      input.value = value
      input.dispatchEvent(new Event('input'))
    }
    setInput(inputs[1], 'premium')
    await flushPromises()

    const okBtn = document.body.querySelector('.ant-modal .ant-btn-primary') as HTMLButtonElement
    okBtn.click()
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/admin/settings', {
      key: 'llm.default.profile',
      value: 'premium',
      description: '系统默认 LLM 档位',
    })
  })

  it('行内删除调用 DELETE /admin/settings/{key}', async () => {
    const wrapper = mount(AdminSettings, { global: { plugins: [Antd] } })
    await flushPromises()

    // 第二个「删除」按钮对应第二行 audit.retention.days
    const delBtns = wrapper.findAll('button').filter((b) => b.text().includes('删除'))
    await delBtns[1].trigger('click')
    await flushPromises()

    // 触发 popconfirm 确认
    const confirmBtn = document.body.querySelector('.ant-popconfirm .ant-btn-primary') as HTMLButtonElement
    confirmBtn.click()
    await flushPromises()

    expect(delMock).toHaveBeenCalledWith('/admin/settings/audit.retention.days')
  })
})
