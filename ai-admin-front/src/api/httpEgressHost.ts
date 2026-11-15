import { controlRequest } from './request'

/** 内网地址白名单：平台管理员允许 ReachAI 调用的内网主机。 */
export interface HttpEgressHost {
  id: number
  host: string
  note?: string | null
  createdBy?: string | null
  createdAt?: string | null
}

export interface HttpEgressHostList {
  hosts: HttpEgressHost[]
  /** 服务器配置里已经整体放开内网 */
  allowPrivateNetworks: boolean
  /** 服务器配置里写死放行的主机 */
  environmentHosts: string[]
}

const ROOT = '/api/platform/http-egress-hosts'

export function listHttpEgressHosts() {
  return controlRequest.get<HttpEgressHostList>(ROOT, { errorFeedback: 'local' })
}

export function addHttpEgressHost(host: string, note?: string) {
  return controlRequest.post<HttpEgressHost>(ROOT, { host, note: note || null }, { errorFeedback: 'local' })
}

export function removeHttpEgressHost(id: number) {
  return controlRequest.delete(`${ROOT}/${id}`, { errorFeedback: 'local' })
}
