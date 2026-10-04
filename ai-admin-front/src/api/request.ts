import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/import'
import { getPlatformSessionId, PLATFORM_CSRF_HEADER } from '@/utils/platformAuth'
import { handlePlatformSessionFailure } from '@/auth/platformSession'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** Prevent login endpoint failures from being treated as an expired platform session. */
    platformAuthFailure?: 'handle' | 'ignore'
    /** Delegate error messages to the caller without disabling platform session handling. */
    errorFeedback?: 'global' | 'local'
  }
}

export function shouldHandlePlatformSessionFailure(error: any): boolean {
  const config = error?.config
  if (!config || config.platformAuthFailure === 'ignore') return false
  if (config.platformAuthFailure === 'handle') return true
  // Only Control's explicit marker proves this 401 belongs to the platform
  // session. A project credential, task token, or downstream service can also
  // reject a request with 401 and must not sign the console out.
  const authFailure = error?.response?.headers?.['x-reachai-auth-failure']
  return authFailure === 'PLATFORM_SESSION_INVALID'
}

function createInstance(baseURL: string, platformSession = false): AxiosInstance {
  const instance = axios.create({
    baseURL,
    timeout: 60000,
    headers: { 'Content-Type': 'application/json' },
  })

  instance.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const method = (config.method || 'get').toUpperCase()
      const unsafeMethod = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
      const sessionId = platformSession && unsafeMethod ? getPlatformSessionId() : ''
      if (sessionId) {
        config.headers.set(PLATFORM_CSRF_HEADER, sessionId)
      }
      return config
    },
    (error) => Promise.reject(error),
  )

  instance.interceptors.response.use(
    (response: AxiosResponse<unknown>) => {
      const res = response.data as Record<string, unknown> | null
      // ai-common ApiResult: success is usually code=200; some gateway responses use code=0.
      if (res !== null && typeof res === 'object' && 'code' in res && typeof res.code === 'number') {
        const code = res.code as number
        if (code !== 200 && code !== 0) {
          const msg = typeof res.message === 'string' ? res.message : '请求失败'
          if (response.config.errorFeedback !== 'local') ElMessage.error(msg)
          return Promise.reject(new Error(msg))
        }
        if ('data' in res && Object.prototype.hasOwnProperty.call(res, 'data')) {
          response.data = res.data as unknown
        }
      }
      return response as AxiosResponse<ApiResult>
    },
    (error) => {
      if (error?.config?.platformAuthFailure === 'ignore') {
        return Promise.reject(error)
      }
      const message =
        error.response?.data?.message || error.message || '网络异常，请稍后重试'
      const localFeedback = error.config?.errorFeedback === 'local'
      if (error.response?.status === 401 && shouldHandlePlatformSessionFailure(error)) {
        const redirected = handlePlatformSessionFailure()
        if (!redirected && !localFeedback) {
          ElMessage.error(message)
        }
        return Promise.reject(error)
      }
      if (!localFeedback) ElMessage.error(message)
      return Promise.reject(error)
    },
  )

  return instance
}

/** Knowledge / Retrieval deployment unit (current reachai-knowledge-service): /ai prefix via context path. */
const textRequest = createInstance(import.meta.env.VITE_API_BASE_URL || '/ai')

/** Platform Control public API/BFF (current reachai-control-service): /api prefix. */
export const controlRequest = createInstance('', true)

/** Model Gateway deployment unit (current reachai-model-service): /model prefix. */
export const modelRequest = createInstance('/model')

export default textRequest
