export type InteractionComponent = 'FORM' | 'TABLE' | 'DETAIL' | 'CARD' | 'REPORT' | 'CHOICE' | 'CONFIRM' | string

export interface UiFieldOptionPayload {
  value: string | number | boolean
  label: string
}

export interface UiFieldPayload {
  key: string
  name?: string
  label: string
  type: string
  required?: boolean
  targetPath?: string
  options?: UiFieldOptionPayload[]
  placeholder?: string
  source?: Record<string, unknown>
}

export interface UiRequestPayload {
  type: string
  component: InteractionComponent
  interactionId?: string
  traceId?: string
  title?: string
  ttlSeconds?: number
  fields?: UiFieldPayload[]
  prefilled?: Record<string, unknown>
  missing?: string[]
  summary?: Record<string, unknown>
  data?: unknown
  schema?: Record<string, unknown>
  actions?: Array<Record<string, unknown>>
  datasources?: Record<string, unknown>
  behavior?: Record<string, unknown>
  extension?: Record<string, unknown>
  message?: string
}

export interface UiSubmitPayload {
  action: 'submit' | 'cancel' | 'modify' | 'choose' | 'confirm' | string
  values?: Record<string, unknown>
}
