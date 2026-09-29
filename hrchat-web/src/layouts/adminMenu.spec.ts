import { describe, expect, it } from 'vitest'
import { ADMIN_MENUS, hasPerm, menuPermsOf, visible } from './adminMenu'

describe('layouts/adminMenu', () => {
  it('ADMIN 拥有全部菜单（* 通配）', () => {
    const perms = menuPermsOf('ADMIN')
    expect(perms).toEqual(['*'])
    expect(ADMIN_MENUS.every((item) => visible(item, perms))).toBe(true)
  })

  it('TENANT_ADMIN 仅见 概览/用户管理/LLM 配置/审计日志', () => {
    const perms = menuPermsOf('TENANT_ADMIN')
    const titles = ADMIN_MENUS.filter((item) => visible(item, perms)).map((item) => item.title)
    expect(titles).toEqual(['概览', '用户管理', 'LLM 配置', '审计日志'])
  })

  it('DATA_ADMIN 仅见 概览/语义层/审计日志/数据与同步（无审批权）', () => {
    const perms = menuPermsOf('DATA_ADMIN')
    const titles = ADMIN_MENUS.filter((item) => visible(item, perms)).map((item) => item.title)
    expect(titles).toEqual(['概览', '语义层', '审计日志', '数据与同步'])
    // 语义层可见但无审批权
    expect(hasPerm(perms, 'admin:semantic')).toBe(true)
    expect(hasPerm(perms, 'admin:semantic:approve')).toBe(false)
  })

  it('其他角色无任何管理菜单', () => {
    const perms = menuPermsOf('HRBP')
    expect(perms).toEqual([])
    expect(ADMIN_MENUS.filter((item) => visible(item, perms))).toEqual([])
  })

  it('hasPerm 支持精确匹配与通配', () => {
    expect(hasPerm(['admin:view'], 'admin:view')).toBe(true)
    expect(hasPerm(['admin:view'], 'admin:llm:manage')).toBe(false)
    expect(hasPerm(['*'], 'admin:system:manage')).toBe(true)
    expect(hasPerm(['admin:*'], 'admin:audit:read')).toBe(true)
    expect(hasPerm(['admin:*'], 'chat:send')).toBe(false)
    expect(hasPerm([], 'admin:view')).toBe(false)
  })
})
