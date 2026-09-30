import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'

/** localStorage 中存储当前身份工号的 key */
export const USER_NO_KEY = 'hrchat_user_no'
/** localStorage 中存储当前租户编码的 key（多租户隔离演示） */
export const TENANT_NO_KEY = 'hrchat_tenant_no'
/** 平台管理员跨租户访问原因，仅保留在当前浏览器会话。 */
export const TENANT_SWITCH_REASON_KEY = 'hrchat_tenant_switch_reason'

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// 请求拦截器：携带当前身份（X-User-No）与当前租户（X-Tenant-No）。本地演示模式按工号/租户切换。
http.interceptors.request.use((config) => {
  const userNo = localStorage.getItem(USER_NO_KEY) || 'hr01'
  const tenantNo = localStorage.getItem(TENANT_NO_KEY) || 't01'
  config.headers['X-User-No'] = userNo
  config.headers['X-Tenant-No'] = tenantNo
  const switchReason = sessionStorage.getItem(TENANT_SWITCH_REASON_KEY)
  if (switchReason) config.headers['X-Tenant-Switch-Reason'] = switchReason
  return config
})

// 响应拦截器：业务 code 不为 SUCCESS 时 reject
http.interceptors.response.use(
  (response) => {
    const data = response.data as ApiResponse<unknown> | null
    if (data && typeof data === 'object' && data.code !== undefined && data.code !== 'SUCCESS') {
      const message = data.message ?? '业务处理失败'
      console.warn('[hrchat] 业务错误:', data.code, message)
      return Promise.reject(new Error(message))
    }
    return response
  },
  (error) => {
    const respData = error?.response?.data
    const text =
      respData && typeof respData === 'object' && typeof respData.message === 'string'
        ? respData.message
        : error?.message ?? '请求失败'
    console.warn('[hrchat] 请求异常:', text)
    return Promise.reject(new Error(text))
  },
)

export function get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.get(url, config).then((res) => res.data as T)
}

export function post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config).then((res) => res.data as T)
}

export function put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config).then((res) => res.data as T)
}

export function patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.patch(url, data, config).then((res) => res.data as T)
}

async function del<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.delete(url, config).then((res) => res.data as T)
}

export { del as delete }
export { del }
export { http }

export default http
