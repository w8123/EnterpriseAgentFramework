import type {
  UiRequestV1,
  UiFieldPayload,
  UiActionPayload,
  UiPresentationMode,
  UiPresentationPayload,
} from './conversationTypes'
import { createId } from './conversationTypes'

const COMPONENT_ALIASES: Record<string, string> = {
  form: 'form',
  text_question: 'text_question',
  textquestion: 'text_question',
  select: 'select',
  choice: 'choice',
  multi_select: 'multi_select',
  multiselect: 'multi_select',
  confirm: 'confirm',
  table: 'table',
  detail: 'detail',
  card: 'card',
  report: 'report',
  summary_card: 'summary_card',
  summarycard: 'summary_card',
  list_card: 'list_card',
  listcard: 'list_card',
  output_card: 'output_card',
  outputcard: 'output_card',
  page_action: 'page_action',
  pageaction: 'page_action',
  custom: 'custom',
}

const READONLY_COMPONENTS = new Set([
  'table',
  'detail',
  'card',
  'report',
  'summary_card',
  'list_card',
  'output_card',
])

const PRESENTATION_MODES = new Set<UiPresentationMode>([
  'card_only',
  'text_and_card',
  'text_only',
])

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function asString(value: unknown): string | undefined {
  if (value == null) return undefined
  const text = String(value).trim()
  return text ? text : undefined
}

function normalizeComponent(raw: unknown): string {
  const text = asString(raw)?.toLowerCase().replace(/[\s-]+/g, '_') || 'custom'
  return COMPONENT_ALIASES[text] || text
}

function normalizeFields(raw: unknown): UiFieldPayload[] | undefined {
  if (!Array.isArray(raw)) return undefined
  const fields: UiFieldPayload[] = []
  for (const item of raw) {
    const record = asRecord(item)
    if (!record) continue
    const key = asString(record.key) || asString(record.name) || asString(record.id)
    if (!key) continue
    const options = Array.isArray(record.options)
      ? record.options
          .map((opt) => {
            const o = asRecord(opt)
            if (!o) return null
            return {
              value: (o.value ?? o.id ?? '') as string | number | boolean,
              label: String(o.label ?? o.name ?? o.value ?? ''),
            }
          })
          .filter((o): o is NonNullable<typeof o> => !!o)
      : undefined
    fields.push({
      key,
      name: asString(record.name),
      label: String(record.label ?? record.name ?? key),
      type: String(record.type ?? 'string'),
      required: record.required === true,
      targetPath: asString(record.targetPath),
      options,
      placeholder: asString(record.placeholder),
      source: asRecord(record.source) || undefined,
    })
  }
  return fields.length ? fields : undefined
}

function normalizeActions(raw: unknown): UiActionPayload[] | undefined {
  if (!Array.isArray(raw)) return undefined
  const actions: UiActionPayload[] = []
  for (const item of raw) {
    const record = asRecord(item)
    if (!record) continue
    actions.push({ ...record })
  }
  return actions.length ? actions : undefined
}

function normalizePresentation(raw: unknown): UiPresentationPayload | undefined {
  const record = asRecord(raw)
  if (!record) return undefined
  const mode = asString(record.mode)?.toLowerCase().replace(/[\s-]+/g, '_') as UiPresentationMode | undefined
  if (!mode || !PRESENTATION_MODES.has(mode)) return undefined
  return { ...record, mode }
}

function stableReadonlyId(component: string, title?: string, message?: string): string {
  const basis = `${component}|${title || ''}|${message || ''}`.slice(0, 80)
  let hash = 0
  for (let i = 0; i < basis.length; i += 1) {
    hash = ((hash << 5) - hash + basis.charCodeAt(i)) | 0
  }
  return `local:readonly:${component}:${Math.abs(hash).toString(36)}`
}

/**
 * 将后端 / 历史 UiRequest 归一化为 UiRequestV1。
 * - component 统一小写 canonical
 * - 缺 schemaVersion 时按 1.0
 * - 只读卡片可生成稳定本地 interactionId
 * - 绝不解析或执行 HTML
 */
export function normalizeUiRequest(raw: unknown): UiRequestV1 | null {
  const record = asRecord(raw)
  if (!record) return null

  const component = normalizeComponent(record.component ?? record.type)
  const title = asString(record.title)
  const message = asString(record.message)
  let interactionId = asString(record.interactionId)

  if (!interactionId) {
    if (READONLY_COMPONENTS.has(component)) {
      interactionId = stableReadonlyId(component, title, message)
    } else {
      interactionId = createId('interaction')
    }
  }

  const extension = asRecord(record.extension) || undefined
  const schema = asRecord(record.schema)
    || asRecord(record.renderSchema)
    || asRecord(extension?.renderSchema)
    || undefined

  return {
    schemaVersion: '1.0',
    interactionId,
    traceId: asString(record.traceId),
    type: asString(record.type),
    component,
    title,
    message,
    ttlSeconds: typeof record.ttlSeconds === 'number' ? record.ttlSeconds : undefined,
    expiresAt: asString(record.expiresAt),
    fields: normalizeFields(record.fields),
    prefilled: asRecord(record.prefilled) || undefined,
    missing: Array.isArray(record.missing) ? record.missing.map(String) : undefined,
    summary: asRecord(record.summary) || undefined,
    data: record.data,
    schema,
    actions: normalizeActions(record.actions),
    presentation: normalizePresentation(record.presentation ?? extension?.presentation),
    datasources: asRecord(record.datasources) || undefined,
    behavior: asRecord(record.behavior) || undefined,
    extension,
  }
}

export function isReadonlyUiComponent(component: string): boolean {
  return READONLY_COMPONENTS.has(normalizeComponent(component))
}

export function uiPresentationMode(raw: unknown): UiPresentationMode {
  return normalizeUiRequest(raw)?.presentation?.mode || 'text_and_card'
}

export function isCardOnlyUiRequest(raw: unknown): boolean {
  return uiPresentationMode(raw) === 'card_only'
}

export function isTextOnlyUiRequest(raw: unknown): boolean {
  return uiPresentationMode(raw) === 'text_only'
}

/**
 * 只读输出卡片默认不阻塞下一轮对话；表单、选择、确认等交互默认阻塞。
 * behavior.blocking 是服务端的显式覆盖，优先级最高。
 */
export function isBlockingUiRequest(raw: unknown): boolean {
  const request = normalizeUiRequest(raw)
  if (!request) return false
  const explicit = request.behavior?.blocking
  if (typeof explicit === 'boolean') return explicit
  return !isReadonlyUiComponent(request.component)
}

export const WORKFLOW_INITIAL_INPUT_INTERACTION_ID = 'local:workflow-initial-input'

export function workflowInitialInputInteractionId(): string {
  return WORKFLOW_INITIAL_INPUT_INTERACTION_ID
}

export { COMPONENT_ALIASES, READONLY_COMPONENTS }
