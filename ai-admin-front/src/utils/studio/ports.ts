import type {
  CanvasNode,
  CanvasNodeKind,
  InteractionNodeConfig,
  IntentClassifierNodeConfig,
  StudioPort,
  StudioFieldSchema,
} from '@/types/studio'
import {
  defaultClassifierConfig,
  defaultInteractionConfig,
  defaultOutputAlias,
  defaultUserInputConfig,
} from './defaults'
import { stringValue } from './values'

export function portValue(value: unknown): StudioPort[] | null {
  if (!Array.isArray(value)) return null
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      id: stringValue(item.id || item.name),
      name: stringValue(item.name || item.id),
      type: stringValue(item.type) as StudioPort['type'],
      required: item.required === true,
      schema: stringValue(item.schema),
      source: stringValue(item.source),
    }))
    .filter((item) => !!item.id)
}

export function defaultPorts(kind: CanvasNodeKind, direction: 'input' | 'output', alias?: string): StudioPort[] {
  if (kind === 'start') {
    return direction === 'output'
      ? [{ id: 'input', name: 'input', type: 'message', required: true }]
      : []
  }
  if (kind === 'end') {
    return direction === 'input'
      ? [{ id: 'answer', name: 'answer', type: 'message', required: false }]
      : []
  }
  if (kind === 'answer') {
    return direction === 'input'
      ? [{ id: 'input', name: 'input', type: 'message', required: false, source: '$lastOutput' }]
      : [{ id: 'answer', name: 'answer', type: 'message' }]
  }
  if (direction === 'input') {
    return [{ id: 'input', name: 'input', type: 'message', required: false, source: '$input' }]
  }
  const output = alias || defaultOutputAlias(kind) || 'output'
  if (kind === 'userInput' || kind === 'interaction' || kind === 'pageAction') {
    return [{ id: output, name: output, type: 'object' }]
  }
  if (kind === 'approval') {
    return [
      { id: 'approved', name: 'approved', type: 'boolean' },
      { id: 'rejected', name: 'rejected', type: 'boolean' },
      { id: 'timeout', name: 'timeout', type: 'boolean' },
    ]
  }
  if (kind === 'loop') {
    return [{ id: 'output', name: 'output', type: 'array' }]
  }
  if (kind === 'condition' || kind === 'classifier') {
    if (kind === 'classifier') {
      return classifierOutputPorts(defaultClassifierConfig())
    }
    return [
      { id: 'matched', name: 'matched', type: 'boolean' },
      { id: 'else', name: 'else', type: 'boolean' },
    ]
  }
  return [{ id: output, name: output, type: kind === 'parameter' ? 'object' : 'any' }]
}

export function syncDynamicPorts(data: CanvasNode['data']): CanvasNode['data'] {
  const outputs = dynamicOutputPorts(data)
  return outputs ? { ...data, outputs } : data
}

export function dynamicOutputPorts(data: CanvasNode['data']): StudioPort[] | null {
  if (data.kind === 'userInput') {
    const config = data.userInputConfig || defaultUserInputConfig()
    return userInputOutputPorts(config.fields || [], config.outputAlias || data.outputAlias || 'params')
  }
  if (data.kind === 'interaction') {
    const config = data.interactionConfig || defaultInteractionConfig()
    return interactionOutputPorts(config, config.outputAlias || data.outputAlias || 'interaction_output')
  }
  if (data.kind === 'classifier') {
    return classifierOutputPorts(data.classifierConfig || defaultClassifierConfig())
  }
  return null
}

export function userInputOutputPorts(fields: StudioFieldSchema[], outputAlias = 'params'): StudioPort[] {
  const alias = outputAlias || 'params'
  const ports: StudioPort[] = [{ id: alias, name: alias, type: 'object', required: false }]
  const seen = new Set<string>([alias])
  for (const field of fields || []) {
    const name = field.name?.trim()
    if (!name) continue
    const id = `${alias}.${name}`
    if (seen.has(id)) continue
    seen.add(id)
    ports.push({
      id,
      name: id,
      type: field.type || 'string',
      required: field.required === true,
      source: alias,
    })
  }
  return ports
}

export function interactionOutputPorts(config: InteractionNodeConfig, outputAlias = 'interaction_output'): StudioPort[] {
  const alias = outputAlias || config.outputAlias || 'interaction_output'
  const ports: StudioPort[] = [{ id: alias, name: alias, type: 'object', required: false }]
  const seen = new Set<string>([alias])
  if (config.interactionType === 'COLLECT_INPUT' || config.interactionType === 'USER_CHOICE'
      || config.interactionType === 'CONFIRM_ACTION' || config.interactionType === 'REVIEW_EDIT') {
    for (const field of config.fields || []) {
      const key = fieldKey(field)
      if (!key) continue
      const id = `${alias}.${key}`
      if (seen.has(id)) continue
      seen.add(id)
      ports.push({
        id,
        name: id,
        type: field.type || 'string',
        required: field.required === true,
        source: alias,
      })
    }
  }
  if (config.interactionType === 'PRESENT_OUTPUT' && !seen.has(`${alias}.acknowledged`)) {
    ports.push({ id: `${alias}.acknowledged`, name: `${alias}.acknowledged`, type: 'boolean', source: alias })
  }
  return ports
}

export function interactionFieldsForGraph(fields: StudioFieldSchema[]): StudioFieldSchema[] {
  return (fields || []).map((field) => {
    const key = fieldKey(field)
    return {
      ...field,
      key,
      name: key,
    }
  })
}

function fieldKey(field: StudioFieldSchema) {
  return (field.key || field.name || '').trim()
}

export function classifierOutputPorts(config: IntentClassifierNodeConfig): StudioPort[] {
  const ports: StudioPort[] = []
  const seen = new Set<string>()
  for (const item of config.classes || []) {
    const id = item.id?.trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    ports.push({
      id,
      name: item.label?.trim() || id,
      type: 'boolean',
      required: false,
    })
  }
  const defaultRoute = (config.defaultRoute || 'else').trim()
  if (defaultRoute && !seen.has(defaultRoute)) {
    ports.push({
      id: defaultRoute,
      name: defaultRoute === 'else' ? 'else' : defaultRoute,
      type: 'boolean',
      required: false,
    })
  }
  return ports.length ? ports : [{ id: 'else', name: 'else', type: 'boolean', required: false }]
}
