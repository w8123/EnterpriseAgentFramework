import type { ToolNodeConfig } from '@/types/studio'

const TOOL_RUNTIME_CONFIG_FIELDS = new Set([
  'configVersion', 'inputMapping', 'args', 'outputAlias', 'needsConfiguration',
  'placeholderReason', 'marketRef', 'description', 'mappingNote',
  'maxRequestTimeMs', 'credentialRef', 'toolConfig', 'ref', 'toolName',
  'qualifiedName', 'projectCode',
  'httpApiAssetId',
])

const TOOL_NESTED_CONFIG_FIELDS = new Set([
  'ref', 'toolName', 'qualifiedName', 'projectCode', 'credentialRef',
  'maxRequestTimeMs', 'inputMapping', 'args', 'mappingNote', 'argumentSource',
  'runtimeConfigExtras', 'nestedConfigExtras', 'assetReference',
])

function toolExtraFields(value: Record<string, unknown>, knownFields: Set<string>) {
  const extras: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value)) {
    if (knownFields.has(key)) continue
    extras[key] = raw
  }
  return extras
}

/** Preserves unowned Runtime fields for legacy Tool/API refs without giving them a new UI owner. */
export function toolRuntimeConfigExtras(config: Record<string, unknown>) {
  return toolExtraFields(config, TOOL_RUNTIME_CONFIG_FIELDS)
}

export function toolNestedConfigExtras(config: Record<string, unknown>) {
  return toolExtraFields(config, TOOL_NESTED_CONFIG_FIELDS)
}

export function graphToolConfig(tool: ToolNodeConfig): Record<string, unknown> {
  return {
    ...(tool.nestedConfigExtras || {}),
    ref: tool.ref,
    qualifiedName: tool.qualifiedName || null,
    projectCode: tool.projectCode || null,
    credentialRef: tool.credentialRef,
    maxRequestTimeMs: tool.maxRequestTimeMs || 180000,
    inputMapping: tool.inputMapping || {},
    mappingNote: tool.mappingNote,
  }
}
