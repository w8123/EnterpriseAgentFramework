import { controlRequest } from './request'
import type {
  PersonalMemory,
  PersonalMemoryApi,
  PersonalMemoryEraseAllResult,
  PersonalMemoryGroup,
  PersonalMemoryLayerView,
  PersonalMemoryPage,
  PersonalMemoryRememberResult,
  PersonalMemoryTree,
} from '@/types/memory'

const ROOT = '/api/me/memories'

/** 我的 · 我的记忆, for the signed-in console user. */
export const myMemoryApi: PersonalMemoryApi = {
  tree: async () => (await controlRequest.get<PersonalMemoryTree>(`${ROOT}/tree`)).data,
  list: async params => (await controlRequest.get<PersonalMemoryPage>(ROOT, { params })).data,
  remember: async data => (await controlRequest.post<PersonalMemoryRememberResult>(ROOT, data)).data,
  update: async (id, data) => (await controlRequest.put<PersonalMemory>(`${ROOT}/${id}`, data)).data,
  forget: async id => (await controlRequest.delete(`${ROOT}/${id}`)).data,
  eraseAll: async data => (await controlRequest.post<PersonalMemoryEraseAllResult>(`${ROOT}/erase-all`, data)).data,
  createGroup: async data => (await controlRequest.post<PersonalMemoryGroup>(`${ROOT}/groups`, data)).data,
  updateGroup: async (id, data) => (await controlRequest.put<PersonalMemoryGroup>(`${ROOT}/groups/${id}`, data)).data,
  deleteGroup: async id => (await controlRequest.delete(`${ROOT}/groups/${id}`)).data,
  addProject: async projectCode =>
    (await controlRequest.post<PersonalMemoryLayerView>(`${ROOT}/projects`, { projectCode })).data,
  exportJson: async () => (await controlRequest.get(`${ROOT}/export`, { responseType: 'blob' })).data as Blob,
}

/**
 * The same calls for the employee personal page, which signs requests with its portal token and
 * receives `{ code, message, data }` envelopes.
 */
export function portalMemoryApi(getToken: () => Promise<string> | string, apiBase = '/api/employee-portal'): PersonalMemoryApi {
  async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await getToken()
    const response = await fetch(`${apiBase}/memories${path}`, {
      ...init,
      headers: {
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        Authorization: `Bearer ${token}`,
      },
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
      throw new Error(payload.message || '操作失败，请稍后重试')
    }
    return payload.data as T
  }
  const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })
  return {
    tree: () => call('/tree'),
    list: (params) => {
      const query = new URLSearchParams()
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
      })
      return call(`?${query}`)
    },
    remember: data => call('', json('POST', data)),
    update: (id, data) => call(`/${id}`, json('PUT', data)),
    forget: id => call(`/${id}`, { method: 'DELETE' }),
    eraseAll: data => call('/erase-all', json('POST', data)),
    createGroup: data => call('/groups', json('POST', data)),
    updateGroup: (id, data) => call(`/groups/${id}`, json('PUT', data)),
    deleteGroup: id => call(`/groups/${id}`, { method: 'DELETE' }),
    addProject: projectCode => call('/projects', json('POST', { projectCode })),
  }
}
