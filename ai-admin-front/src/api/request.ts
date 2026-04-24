import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/import'

function createInstance(baseURL: string): AxiosInstance {
  const instance = axios.create({
    baseURL,
    timeout: 60000,
    headers: { 'Content-Type': 'application/json' },
  })

  instance.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => config,
    (error) => Promise.reject(error),
  )

  instance.interceptors.response.use(
    (response: AxiosResponse<ApiResult>) => {
      const res = response.data
      if (res.code !== undefined && res.code !== 200) {
        ElMessage.error(res.message || '请求失败')
        return Promise.reject(new Error(res.message || '请求失败'))
      }
      return response
    },
    (error) => {
      const message =
        error.response?.data?.message || error.message || '网络异常，请稍后重试'
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
