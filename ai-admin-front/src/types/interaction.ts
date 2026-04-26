/** 后端 UiRequestPayload（Phase 2.x 交互式表单） */
export interface UiFieldOptionPayload {
  value: string
  label: string
}

export interface UiFieldPayload {
  key: string
  label: string
  type: string
  required: boolean
  options?: UiFieldOptionPayload[]
}

export interface UiRequestPayload {
  type: string
  component: string
  interactionId: string
  traceId?: string
  skillName?: string
  title?: string
  ttlSeconds?: number
  fields?: UiFieldPayload[]
  prefilled?: Record<string, unknown>
  missing?: string[]
  summary?: Record<string, unknown>
  message?: string
}

export interface UiSubmitPayload {
  action: 'submit' | 'cancel' | 'modify' | string
  values?: Record<string, unknown>
}
