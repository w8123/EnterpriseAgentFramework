import type { Component } from 'vue'
import type { UiRequestV1 } from '../core/conversationTypes'

export type BuiltinRendererKey =
  | 'form'
  | 'text_question'
  | 'select'
  | 'choice'
  | 'multi_select'
  | 'confirm'
  | 'table'
  | 'detail'
  | 'card'
  | 'report'
  | 'summary_card'
  | 'list_card'
  | 'output_card'
  | 'page_action'
  | 'custom'

export interface InteractionRendererContext {
  request: UiRequestV1
  state: string
  disabled?: boolean
  onSubmit: (action: string, values: Record<string, unknown>) => void
  onCancel?: () => void
}

export type InteractionRendererFactory = (ctx: InteractionRendererContext) => Component | null

const customRenderers = new Map<string, InteractionRendererFactory>()

const COMPONENT_TO_KIND: Record<string, BuiltinRendererKey> = {
  form: 'form',
  text_question: 'form',
  select: 'select',
  choice: 'select',
  multi_select: 'select',
  confirm: 'confirm',
  table: 'table',
  detail: 'detail',
  card: 'output_card',
  report: 'output_card',
  summary_card: 'summary_card',
  list_card: 'list_card',
  output_card: 'output_card',
  page_action: 'page_action',
  custom: 'custom',
}

export function resolveRendererKind(component: string): BuiltinRendererKey {
  const key = String(component || 'custom').toLowerCase()
  return COMPONENT_TO_KIND[key] || 'custom'
}

export function registerCustomInteractionRenderer(rendererKey: string, factory: InteractionRendererFactory) {
  if (!rendererKey) return
  customRenderers.set(rendererKey, factory)
}

export function unregisterCustomInteractionRenderer(rendererKey: string) {
  customRenderers.delete(rendererKey)
}

export function getCustomInteractionRenderer(rendererKey: string | undefined): InteractionRendererFactory | undefined {
  if (!rendererKey) return undefined
  return customRenderers.get(rendererKey)
}

export function listRegisteredCustomRendererKeys(): string[] {
  return [...customRenderers.keys()]
}
