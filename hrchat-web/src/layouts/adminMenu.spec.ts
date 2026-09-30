import { describe, expect, it } from 'vitest'
import { ADMIN_MENUS, hasPerm, visible } from './adminMenu'

describe('server-provided permissions', () => {
  it('accepts admin namespace wildcard', () => {
    expect(ADMIN_MENUS.every(item => visible(item, ['admin:*']))).toBe(true)
  })
  it('shows menus for arbitrary custom roles using only effective permissions', () => {
    const menus = ADMIN_MENUS.filter(item => visible(item, ['admin:user:manage', 'chat:ask']))
    expect(menus.map(item => item.key)).toEqual(['users'])
  })
  it('empty grants hide all management menus', () => {
    expect(ADMIN_MENUS.filter(item => visible(item, []))).toEqual([])
  })
  it('matches the complete namespace boundary', () => {
    expect(hasPerm(['admin:*'], 'admin:audit:read')).toBe(true)
    expect(hasPerm(['admin:*'], 'administrator:read')).toBe(false)
    expect(hasPerm(['admin:*'], 'chat:ask')).toBe(false)
    expect(hasPerm(['admin:view'], 'admin:llm:view')).toBe(false)
    expect(hasPerm(['admin:view'], 'admin:view')).toBe(true)
    expect(hasPerm([], 'admin:view')).toBe(false)
  })
})
