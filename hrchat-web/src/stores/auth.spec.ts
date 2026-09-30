import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }))
vi.mock('@/api/http', () => ({
  get: getMock, USER_NO_KEY: 'user', TENANT_NO_KEY: 'tenant', TENANT_SWITCH_REASON_KEY: 'reason',
}))

describe('server-sourced permissions', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    getMock.mockReset()
    setActivePinia(createPinia())
  })

  it('uses server grants, accepts revocation and fails closed', async () => {
    const auth = useAuthStore()
    getMock.mockResolvedValueOnce({ data: { roles: ['CUSTOM'], functionPerms: ['admin:audit:read'] } })
    await auth.refreshPermissions()
    expect(auth.functionPerms).toEqual(['admin:audit:read'])
    getMock.mockResolvedValueOnce({ data: { roles: ['CUSTOM'], functionPerms: [] } })
    await auth.refreshPermissions()
    expect(auth.functionPerms).toEqual([])
    auth.functionPerms = ['admin:*']
    getMock.mockRejectedValueOnce(new Error('offline'))
    await expect(auth.refreshPermissions()).rejects.toThrow('offline')
    expect(auth.functionPerms).toEqual([])
  })

  it('ignores an old identity response after switching users', async () => {
    const auth = useAuthStore()
    let resolve!: (value: unknown) => void
    getMock.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const pending = auth.refreshPermissions()
    auth.switchUser('test09')
    resolve({ data: { roles: ['ADMIN'], functionPerms: ['admin:*'] } })
    await pending
    expect(auth.functionPerms).toEqual([])
    expect(auth.roles).toEqual([])
  })

  it('ignores an older refresh arriving after the latest result', async () => {
    const auth = useAuthStore()
    let resolve!: (value: unknown) => void
    getMock.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const pending = auth.refreshPermissions()
    getMock.mockResolvedValueOnce({ data: { roles: [], functionPerms: [] } })
    await auth.refreshPermissions()
    resolve({ data: { roles: ['ADMIN'], functionPerms: ['admin:*'] } })
    await pending
    expect(auth.functionPerms).toEqual([])
  })
})
