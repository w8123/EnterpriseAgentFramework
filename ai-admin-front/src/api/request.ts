import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/import'
import { clearPlatformToken, getPlatformToken } from '@/utils/platformAuth'

function createInstance(baseURL: string): AxiosInstance {
  const instance = axios.create({
    baseURL,
    timeout: 60000,
    headers: { 'Content-Type': 'application/json' },
  })

  instance.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const token = getPlatformToken()
      if (token) {
        config.headers.Authorization = `Bearer ${token}`
      }
      return config
    },
    (error) => Promise.reject(error),
  )

  instance.interceptors.response.use(
    (response: AxiosResponse<unknown>) => {
      const res = response.data as Record<string, unknown> | null
      // ai-common ApiResult：成功多为 code=200；部分网关使用 code=0 表示成功
      if (res !== null && typeof res === 'object' && 'code' in res && typeof res.code === 'number') {
        const code = res.code as number
        if (code !== 200 && code !== 0) {
          const msg = typeof res.message === 'string' ? res.message : '请求失败'
          ElMessage.error(msg)
          return Promise.reject(new Error(msg))
        }
        if ('data' in res && Object.prototype.hasOwnProperty.call(res, 'data')) {
          response.data = res.data as unknown
        }
      }
      return response as AxiosResponse<ApiResult>
    },
    (error) => {
      const message =
        error.response?.data?.message || error.message || '网络异常，请稍后重试'
      if (error.response?.status === 401 && typeof window !== 'undefined') {
        clearPlatformToken()
        const current = window.location.pathname + window.location.search
        if (!window.location.pathname.startsWith('/login')) {
          window.location.href = `/login?redirect=${encodeURIComponent(current)}`
        }
      }
      ElMessage.error(message)
      return Promise.reject(error)
    },
  )

  return instance
}

/** ai-skills-service (RAG / 知识库) — /ai prefix via context-path */
const textRequest = createInstance(import.meta.env.VITE_API_BASE_URL || '/ai')

/** ai-agent-service (Agent / Chat / Tool) — /api prefix */
export const agentRequest = createInstance('')

/** ai-model-service (模型网关) — /model prefix */
export const modelRequest = createInstance('/model')

export default textRequest
