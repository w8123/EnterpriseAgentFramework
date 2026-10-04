import type { StudioPort, ToolNodeConfig } from '@/types/studio'
import type { ToolInfo, ToolParameter } from '@/types/tool'
import { logicalToolParameters } from '@/utils/toolParameterTree'

export interface BusinessMethodInputTarget {
  name: string
  type: string
  description: string
  required: boolean
  children: ToolParameter[]
}

export interface BusinessMethodOutputField {
  path: string
  type: string
  description: string
}

export interface BusinessMethodMappingReconciliation {
  mapping: Record<string, unknown>
  preserved: number
  added: number
  removed: number
}

export interface BusinessMethodOutputCandidate {
  value: string
  label: string
  path?: string
}

const OUTPUT_LOCATIONS = new Set(['OUTPUT', 'RETURN', 'RESPONSE'])
export const BUSINESS_METHOD_SCALAR_TRIAL_OUTPUT = '只读试运行返回值（标量）'
const SCALAR_RETURN_TYPES = new Set([
  'string', 'char', 'character', 'boolean', 'bool', 'byte', 'short', 'int',
  'integer', 'long', 'number', 'float', 'double', 'java.math.bigdecimal',
])

/** The draft gateway wraps a supported scalar in data; published execution keeps the root value. */
export function businessMethodHasScalarTrialReturn(responseType: string | null | undefined) {
  return SCALAR_RETURN_TYPES.has(String(responseType || '').replace('java.lang.', '').toLowerCase())
}
const BUSINESS_METHOD_CATALOG_PROJECTION_FIELDS = new Set([
  'assetType', 'id', 'sourceQualifiedName', 'contractHash', 'currentContractHash',
  'acceptedContractHash', 'sourceContractHash', 'frontendContractHash',
  'frontendHash', 'definitionId', 'projectionId', 'toolDefinitionId',
])

function outputLocation(location: unknown) {
  return OUTPUT_LOCATIONS.has(String(location || '').trim().toUpperCase())
}

function hasOwn(value: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function normalizedPath(parentPath: string, name: string) {
  const trimmed = String(name || '').trim()
  if (!parentPath || !trimmed || trimmed === parentPath || trimmed.startsWith(`${parentPath}.`)) return trimmed
  return `${parentPath}.${trimmed}`
}

/**
 * Flattens source declarations while retaining their path and effective
 * location. It is a display/mapping helper only and never mutates source
 * metadata or the accepted source contract.
 */
function declarationsForLocation(
  parameters: readonly ToolParameter[] | null | undefined,
  accept: (location: string) => boolean,
): ToolParameter[] {
  const declarations: ToolParameter[] = []
  const visit = (parameter: ToolParameter, parentPath = '', inheritedLocation = '') => {
    const name = normalizedPath(parentPath, parameter.name)
    if (!name) return
    const location = String(parameter.location || inheritedLocation || '').trim().toUpperCase()
    if (accept(location)) {
      declarations.push({ ...parameter, name, location: location || null, children: [] })
    }
    for (const child of parameter.children || []) visit(child, name, location)
  }
  for (const parameter of parameters || []) visit(parameter)
  return declarations
}

function inputDeclarations(parameters: readonly ToolParameter[] | null | undefined) {
  return declarationsForLocation(parameters, (location) => !OUTPUT_LOCATIONS.has(location))
}

function outputDeclarations(parameters: readonly ToolParameter[] | null | undefined) {
  return declarationsForLocation(parameters, (location) => OUTPUT_LOCATIONS.has(location))
}

/** Builds top-level mapping targets; dotted DTO fields stay under their root. */
export function businessMethodInputTargets(
  parameters: readonly ToolParameter[] | null | undefined,
): BusinessMethodInputTarget[] {
  return logicalToolParameters(inputDeclarations(parameters)).map((parameter) => ({
    name: parameter.name,
    type: parameter.type || '未声明类型',
    description: parameter.description || '',
    required: parameter.required === true,
    children: parameter.children || [],
  }))
}

function collectOutputFields(parameter: ToolParameter, parentPath: string, fields: BusinessMethodOutputField[], seen: Set<string>) {
  const path = normalizedPath(parentPath, parameter.name)
  if (!path) return
  if (!seen.has(path)) {
    seen.add(path)
    fields.push({ path, type: parameter.type || 'any', description: parameter.description || '' })
  }
  for (const child of parameter.children || []) collectOutputFields(child, path, fields, seen)
}

/** Returns only source-declared return fields; responseType alone creates none. */
export function businessMethodOutputFields(
  parameters: readonly ToolParameter[] | null | undefined,
): BusinessMethodOutputField[] {
  const fields: BusinessMethodOutputField[] = []
  const seen = new Set<string>()
  for (const root of logicalToolParameters(outputDeclarations(parameters))) {
    collectOutputFields(root, '', fields, seen)
  }
  return fields
}

export function businessMethodOutputPorts(
  outputAlias: string,
  parameters: readonly ToolParameter[] | null | undefined,
  responseType?: string | null,
): StudioPort[] {
  const alias = outputAlias.trim() || 'tool_output'
  const ports: StudioPort[] = [{ id: alias, name: alias, type: 'any', required: false }]
  for (const field of businessMethodOutputFields(parameters)) {
    ports.push({
      id: `${alias}.${field.path}`,
      name: `${alias}.${field.path}`,
      type: 'any',
      required: false,
      source: field.description || '来源声明返回字段',
    })
  }
  if (businessMethodHasScalarTrialReturn(responseType) && !ports.some((port) => port.id === `${alias}.data`)) {
    ports.push({
      id: `${alias}.data`, name: `${alias}.data`, type: 'any', required: false,
      source: BUSINESS_METHOD_SCALAR_TRIAL_OUTPUT,
    })
  }
  return ports
}

export function businessMethodOutputCandidates(
  nodeId: string,
  outputAlias: string,
  parameters: readonly ToolParameter[] | null | undefined,
  responseType?: string | null,
): BusinessMethodOutputCandidate[] {
  const alias = outputAlias.trim() || 'tool_output'
  const variableAlias = alias.startsWith('var.') ? alias : `var.${alias}`
  const variableDisplayAlias = variableAlias.slice('var.'.length)
  const candidates: BusinessMethodOutputCandidate[] = [
    { value: `nodeOutput.${nodeId}`, label: '节点输出 · 根对象' },
    { value: variableAlias, label: `业务别名 · ${variableDisplayAlias}` },
  ]
  for (const field of businessMethodOutputFields(parameters)) {
    candidates.push(
      { value: `nodeOutput.${nodeId}.${field.path}`, label: `节点输出 · ${field.path}`, path: field.path },
      { value: `${variableAlias}.${field.path}`, label: `业务别名 · ${variableDisplayAlias}.${field.path}`, path: field.path },
    )
  }
  if (businessMethodHasScalarTrialReturn(responseType) && !candidates.some((item) => item.value === `nodeOutput.${nodeId}.data`)) {
    candidates.push({ value: `nodeOutput.${nodeId}.data`, label: '只读试运行 · 标量返回值', path: 'data' })
  }
  return candidates
}

export function isSelectableBusinessMethod(method: ToolInfo | null | undefined, projectId: number | null | undefined) {
  return Boolean(
    method
    && method.assetType === 'BUSINESS_METHOD'
    && method.enabled === true
    && method.sourceAvailability === 'READY'
    && typeof method.qualifiedName === 'string'
    && method.qualifiedName.trim()
    && typeof projectId === 'number'
    && method.projectId === projectId,
  )
}

/** Preserves same-name author mappings, removes obsolete keys, and fills only missing roots. */
export function reconcileBusinessMethodInputMapping(
  existing: Record<string, unknown> | null | undefined,
  targets: readonly BusinessMethodInputTarget[],
): BusinessMethodMappingReconciliation {
  const current = existing || {}
  const validKeys = new Set(targets.map((target) => target.name))
  const mapping: Record<string, unknown> = {}
  let preserved = 0
  let added = 0
  for (const target of targets) {
    if (hasOwn(current, target.name)) {
      mapping[target.name] = current[target.name]
      preserved += 1
    } else {
      mapping[target.name] = `params.${target.name}`
      added += 1
    }
  }
  let removed = 0
  for (const key of Object.keys(current)) {
    if (!validKeys.has(key)) removed += 1
  }
  return { mapping, preserved, added, removed }
}

/**
 * A business-method selection uses only the stable Runtime reference. Catalog
 * projection fields are deliberately removed at this explicit write boundary;
 * generic Tool/API round trips retain their unknown fields untouched.
 */
export function stripBusinessMethodCatalogProjection(config: ToolNodeConfig) {
  const mutableConfig = config as ToolNodeConfig & Record<string, unknown>
  for (const field of BUSINESS_METHOD_CATALOG_PROJECTION_FIELDS) delete mutableConfig[field]
  config.runtimeConfigExtras = Object.fromEntries(
    Object.entries(config.runtimeConfigExtras || {})
      .filter(([field]) => !BUSINESS_METHOD_CATALOG_PROJECTION_FIELDS.has(field)),
  )
  config.nestedConfigExtras = Object.fromEntries(
    Object.entries(config.nestedConfigExtras || {})
      .filter(([field]) => !BUSINESS_METHOD_CATALOG_PROJECTION_FIELDS.has(field)),
  )
}

export function applyBusinessMethodSelection(
  config: ToolNodeConfig,
  method: ToolInfo,
  fallbackProjectCode: string | null | undefined,
) {
  const targets = businessMethodInputTargets(method.parameters)
  const reconciliation = reconcileBusinessMethodInputMapping(config.inputMapping, targets)
  stripBusinessMethodCatalogProjection(config)
  config.ref = method.name
  config.qualifiedName = method.qualifiedName || null
  config.projectCode = method.projectCode || fallbackProjectCode || null
  config.maxRequestTimeMs ||= 180000
  config.inputMapping = targets.length ? reconciliation.mapping : {}
  config.argumentSource = targets.length ? 'inputMapping' : 'args'
  return { targets, reconciliation }
}

function mappingReferenceRoot(value: string) {
  const segments = value.trim().replace(/^\$/, '').split('.')
  if ((segments[0] === 'nodeOutput' || segments[0] === 'var') && segments[1]) {
    return `${segments[0]}.${segments[1]}`
  }
  return (segments[0] === 'nodeOutput' || segments[0] === 'var') ? '' : null
}

/** Declared fields are picker hints; mapping validity is scoped to a surviving root. */
export function businessMethodMappingIssue(
  _target: string,
  rawValue: unknown,
  required: boolean,
  availableValues: Iterable<string>,
) {
  const value = typeof rawValue === 'string' ? rawValue.trim() : rawValue
  if (required && (value === undefined || value === null || value === '')) return '必填参数尚未映射。'
  if (typeof value !== 'string' || !value) return ''
  const root = mappingReferenceRoot(value)
  if (root !== null && !root) return '所选节点输出或业务别名已不存在，请重新选择来源。'
  if (root !== null && !new Set(availableValues).has(root)) return '所选节点输出或业务别名已不存在，请重新选择来源。'
  return ''
}

export function missingBusinessMethodMappings(
  targets: readonly BusinessMethodInputTarget[],
  mapping: Record<string, unknown> | null | undefined,
) {
  const current = mapping || {}
  return targets
    .filter((target) => target.required)
    .filter((target) => {
      const value = current[target.name]
      return value === undefined || value === null || (typeof value === 'string' && !value.trim())
    })
    .map((target) => target.name)
}

export function businessMethodSideEffectLabel(sideEffect: string | null | undefined) {
  return ({
    NONE: '无副作用',
    READ: '只读',
    READ_ONLY: '只读',
    IDEMPOTENT_WRITE: '幂等写入',
    WRITE: '写入',
    IRREVERSIBLE: '不可逆',
  } as Record<string, string>)[String(sideEffect || '').trim().toUpperCase()] || '副作用未声明'
}
