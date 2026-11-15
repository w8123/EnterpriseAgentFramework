/**
 * 批量导入业务接口：把后端解析出的候选接口，按「统一设置」整理成批量保存的请求，分批提交，可选导入后直接接纳。
 */
import { acceptHttpApi, getHttpApi, getHttpApiConnection, saveHttpApiConnection, saveManualHttpApiBatch, type HttpApiImportCandidate,
  type HttpApiImportPreview } from '@/api/httpApi'
import { createWorkflowCredential } from '@/api/workflowCredential'
import type { ManualAuthMode, ManualSideEffect } from './manualHttpApi'

export const BATCH_SIZE = 200

export type ImportAuthMode = 'DOCUMENT' | ManualAuthMode
export type ImportGroupMode = 'DOCUMENT' | 'CUSTOM' | 'NONE'

export interface BatchImportSettings {
  authMode: ImportAuthMode
  authHeaderName: string
  groupMode: ImportGroupMode
  groupName: string
  /** 用户在表格里改过的操作性质，按候选 key 记录 */
  sideEffects: Record<string, ManualSideEffect>
  acceptAfterImport: boolean
  /** 只读查询接口导入后直接接纳（默认开）：查询不改数据，接进来就能让助手用；写入类仍等人核对 */
  acceptReadOnly: boolean
}

export function defaultBatchSettings(): BatchImportSettings {
  return { authMode: 'DOCUMENT', authHeaderName: '', groupMode: 'DOCUMENT', groupName: '', sideEffects: {}, acceptAfterImport: false, acceptReadOnly: true }
}

export const FORMAT_LABELS: Record<HttpApiImportPreview['format'], string> = {
  OPENAPI3: 'OpenAPI 3', SWAGGER2: 'Swagger 2.0', POSTMAN: 'Postman 集合', HAR: '浏览器抓包（HAR）', DIFY: 'Dify 应用', N8N: 'n8n 工作流',
}

export const SIDE_EFFECT_LABELS: Record<ManualSideEffect, string> = {
  READ_ONLY: '只读查询', IDEMPOTENT_WRITE: '写入（可重复）', WRITE: '写入', IRREVERSIBLE: '不可撤销',
}

export const AUTH_LABELS: Record<string, string> = {
  UNKNOWN: '不确定', NONE: '不需要', BEARER: 'Bearer 令牌', API_KEY_HEADER: '请求头 API Key',
}

export function originOf(format: HttpApiImportPreview['format']): 'openapi' | 'postman' | 'har' | 'dify' | 'n8n' {
  if (format === 'DIFY') return 'dify'
  if (format === 'N8N') return 'n8n'
  return format === 'POSTMAN' ? 'postman' : format === 'HAR' ? 'har' : 'openapi'
}

/** 默认勾选：能导入、没有废弃的接口。 */
export function defaultSelection(candidates: HttpApiImportCandidate[]): string[] {
  return candidates.filter(item => !item.unsupported && !item.deprecated).map(item => item.key)
}

export function validateSettings(settings: BatchImportSettings): string | null {
  if (settings.authMode === 'API_KEY_HEADER' && !/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/.test(settings.authHeaderName.trim())) {
    return '请填写 API Key 所在的请求头名称'
  }
  if (settings.groupMode === 'CUSTOM' && !settings.groupName.trim()) return '请填写业务分组名称'
  if (settings.groupMode === 'CUSTOM' && settings.groupName.trim().length > 64) return '业务分组名称不能超过 64 个字'
  return null
}

/** 候选接口 + 统一设置 → 提交给 /manual-http-apis/batch 的一项。 */
export function buildBatchItem(candidate: HttpApiImportCandidate, settings: BatchImportSettings, origin: string) {
  const request = candidate.request
  const authentication = settings.authMode === 'DOCUMENT' ? request.authentication
    : settings.authMode === 'API_KEY_HEADER' ? { mode: settings.authMode, headerName: settings.authHeaderName.trim() }
      : { mode: settings.authMode }
  const businessGroups = settings.groupMode === 'NONE' ? []
    : settings.groupMode === 'CUSTOM' ? [settings.groupName.trim()] : (request.businessGroups || [])
  return {
    ...request,
    origin,
    sideEffect: settings.sideEffects[candidate.key] || request.sideEffect,
    authentication,
    businessGroups,
  }
}

export function chunk<T>(items: T[], size = BATCH_SIZE): T[][] {
  const result: T[][] = []
  for (let index = 0; index < items.length; index += size) result.push(items.slice(index, index + size))
  return result
}

export interface BatchImportOutcome {
  saved: number
  created: number
  updated: number
  accepted: number
  failures: Array<{ key: string; message: string }>
  /** 保存成功的接口（key + apiId），接纳成功的会标 accepted */
  savedApis: Array<{ key: string; apiId: number; accepted: boolean }>
}

export interface BatchImportProgress { done: number; total: number; stage: 'saving' | 'accepting' }

/** 分批保存选中的接口；再逐个接纳该接纳的接口（全部，或只读查询的），4 个并发。 */
export async function runBatchImport(projectId: number, candidates: HttpApiImportCandidate[], settings: BatchImportSettings,
  origin: string, onProgress?: (progress: BatchImportProgress) => void): Promise<BatchImportOutcome> {
  const outcome: BatchImportOutcome = { saved: 0, created: 0, updated: 0, accepted: 0, failures: [], savedApis: [] }
  const toAccept: Array<{ key: string; apiId: number }> = []
  let done = 0
  for (const group of chunk(candidates)) {
    const { data } = await saveManualHttpApiBatch(projectId, group.map(item => buildBatchItem(item, settings, origin)))
    for (const row of data.results || []) {
      const candidate = group[row.index]
      const key = candidate?.key || `${row.httpMethod} ${row.path}`
      if (row.ok && row.apiId) {
        outcome.saved++
        if (row.created) outcome.created++
        else outcome.updated++
        const sideEffect = candidate ? (settings.sideEffects[candidate.key] || candidate.request.sideEffect) : undefined
        if (settings.acceptAfterImport || (settings.acceptReadOnly && sideEffect === 'READ_ONLY')) toAccept.push({ key, apiId: row.apiId })
        outcome.savedApis.push({ key, apiId: row.apiId, accepted: false })
      } else {
        outcome.failures.push({ key, message: row.message || '保存失败' })
      }
    }
    done += group.length
    onProgress?.({ done, total: candidates.length, stage: 'saving' })
  }
  if (!toAccept.length) return outcome
  let next = 0
  let accepting = 0
  async function worker() {
    while (next < toAccept.length) {
      const item = toAccept[next++]
      try {
        const { data } = await getHttpApi(item.apiId)
        const saved = outcome.savedApis.find(entry => entry.apiId === item.apiId)
        if (data.summary.sourceStatus === 'ACCEPTED') { outcome.accepted++; if (saved) saved.accepted = true }
        else if (!data.summary.sourceConfirmed) outcome.failures.push({ key: item.key, message: `已导入，未接纳：${data.summary.sourceReason || '来源待确认'}` })
        else { await acceptHttpApi(item.apiId, data.summary.sourceSetRevision); outcome.accepted++; if (saved) saved.accepted = true }
      } catch (error) {
        const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        outcome.failures.push({ key: item.key, message: `已导入，接纳失败：${message || '请到详情页手动接纳'}` })
      }
      accepting++
      onProgress?.({ done: accepting, total: toAccept.length, stage: 'accepting' })
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, toAccept.length) }, worker))
  return outcome
}

/** 过滤表格：关键字（路径 / 名称）、分组、方法、来源主机、只看新接口。 */
export function filterCandidates(candidates: HttpApiImportCandidate[], filter: { keyword: string; group: string;
  method: string; host: string; onlyNew: boolean }) {
  const keyword = filter.keyword.trim().toLowerCase()
  return candidates.filter(item => {
    if (keyword && !`${item.request.path} ${item.request.summary || ''}`.toLowerCase().includes(keyword)) return false
    if (filter.group && (item.request.businessGroups?.[0] || '') !== filter.group) return false
    if (filter.method && item.request.httpMethod !== filter.method) return false
    if (filter.host && (item.host || '') !== filter.host) return false
    if (filter.onlyNew && item.existing) return false
    return true
  })
}

/**
 * Dify 每个应用有自己的 API 密钥：把密钥存成本项目的 Bearer 凭据，再给刚接纳的接口单独配置调用地址和这个凭据
 * （接口级覆盖，不影响项目里其他 API）。返回配置成功的个数和失败原因。
 */
export async function configureDifyConnections(input: { projectId: number; projectCode: string; title: string; apiKey: string;
  origin: string; apiIds: number[] }): Promise<{ configured: number; error: string | null }> {
  if (!input.apiIds.length) return { configured: 0, error: null }
  let credentialRef: string
  try {
    const { data } = await createWorkflowCredential({
      name: `Dify · ${input.title || '应用'}`.slice(0, 64),
      type: 'BEARER',
      projectId: input.projectId,
      projectCode: input.projectCode,
      scope: 'PROJECT',
      secret: { token: input.apiKey },
    })
    credentialRef = data.credentialRef
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    return { configured: 0, error: `保存 API 密钥失败：${message || '没有凭据管理权限？'}` }
  }
  let configured = 0
  let failure: string | null = null
  for (const apiId of input.apiIds) {
    try {
      const { data: current } = await getHttpApiConnection(apiId)
      await saveHttpApiConnection(apiId, { origin: input.origin, authMode: 'BEARER', credentialRef,
        expectedRevision: current.overrideRevision ?? null })
      configured++
    } catch (error) {
      const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      failure = `配置调用地址失败：${message || '请到接口详情页手动配置'}`
    }
  }
  return { configured, error: failure }
}
