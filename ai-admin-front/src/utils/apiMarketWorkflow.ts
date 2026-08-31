import type {
  ApiMarketEntryDetail,
  ApiMarketOperation,
  ApiMarketVersion,
} from '@/types/apiMarket'
import type { WorkflowWorkingCopyInput } from '@/types/workflow'
import type { WorkflowGraphNode } from '@/types/agent'

interface RequestField {
  name: string
  type: string
  required: boolean
  description: string
  location: 'path' | 'query' | 'header' | 'body'
  defaultValue?: unknown
}

export interface ApiMarketWorkflowDraftOptions {
  name: string
  keySlug: string
  projectId: number
  projectCode: string
  integrationId: number
}

export function buildApiMarketWorkflowDraft(
  detail: ApiMarketEntryDetail,
  version: ApiMarketVersion,
  operation: ApiMarketOperation,
  options: ApiMarketWorkflowDraftOptions,
): WorkflowWorkingCopyInput {
  const fields = requestFields(operation)
  const inputNodeId = 'api_input'
  const httpNodeId = `api_${safeIdentifier(operation.operationKey || operation.operationId || 'request')}`
  const httpConfig = invocationConfig(detail, version, operation, fields)
  const nodes: WorkflowGraphNode[] = []

  if (fields.length) {
    const userInputConfig = {
      outputAlias: 'params',
      fields: fields.map(field => ({
        name: field.name,
        type: field.type,
        required: field.required,
        description: field.description,
        source: field.name,
        defaultValue: field.defaultValue,
      })),
    }
    nodes.push({
      id: inputNodeId,
      type: 'USER_INPUT',
      name: 'API 请求参数',
      description: '收集调用外部 API 所需的参数，写入 params 命名空间。',
      inputSchema: inputSchema(fields),
      config: {
        ...userInputConfig,
        userInputConfig,
      },
    })
  }

  nodes.push({
    id: httpNodeId,
    type: 'HTTP_REQUEST',
    name: operation.title || detail.entry.title,
    description: operation.description || detail.entry.summary,
    config: {
      ...httpConfig,
      httpConfig,
      marketRef: {
        entryKey: detail.entry.entryKey,
        versionKey: version.versionKey,
        operationKey: operation.operationKey,
        operationId: operation.operationId,
        integrationId: options.integrationId,
        credentialRequired: operation.authRequired,
        specHash: version.specHash,
        sourceKey: detail.entry.source?.sourceKey,
      },
      needsConfiguration: operation.authRequired,
      placeholderReason: operation.authRequired ? '请在 Workflow Studio 中选择项目凭据' : undefined,
    },
  })

  const entryNodeId = fields.length ? inputNodeId : httpNodeId
  const graphSpec = {
    schemaVersion: 2 as const,
    inputSchema: inputSchema(fields),
    nodes,
    edges: fields.length
      ? [{ id: `${inputNodeId}_${httpNodeId}`, from: inputNodeId, to: httpNodeId }]
      : [],
    entryNodeId,
    exitNodeIds: [httpNodeId],
  }

  return {
    name: options.name.trim(),
    keySlug: options.keySlug.trim(),
    projectId: options.projectId,
    projectCode: options.projectCode,
    workflowKind: 'GENERAL',
    executionEngine: 'GRAPH_SPEC',
    definitionAuthority: 'USER',
    creationChannel: 'STUDIO',
    description: `通过 API 市场接入 ${detail.entry.title} · ${operation.title}`,
    status: 'DRAFT',
    graphSpec,
    canvasJson: JSON.stringify({
      schemaVersion: 1,
      layoutVersion: 1,
      nodes: fields.length
        ? [
            { id: inputNodeId, position: { x: 120, y: 180 } },
            { id: httpNodeId, position: { x: 500, y: 180 } },
          ]
        : [{ id: httpNodeId, position: { x: 280, y: 180 } }],
      edges: fields.length ? [{ id: `${inputNodeId}_${httpNodeId}` }] : [],
    }),
    inputSchemaJson: JSON.stringify(inputSchema(fields)),
  }
}

function requestFields(operation: ApiMarketOperation): RequestField[] {
  const schema = isObject(operation.requestSchema) ? operation.requestSchema : {}
  const properties = isObject(schema.properties) ? schema.properties : {}
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  const examples = operation.exampleParams || {}
  const names = new Set([...Object.keys(properties), ...Object.keys(examples)])
  return [...names]
    .filter(name => /^[A-Za-z_][A-Za-z0-9_]*$/.test(name))
    .map((name) => {
      const property = isObject(properties[name]) ? properties[name] : {}
      return {
        name,
        type: inputType(property.type ?? typeof examples[name]),
        required: required.has(name) || property.required === true,
        description: String(property.description || property.title || name),
        location: parameterLocation(name, property.location, operation),
        defaultValue: property.default ?? examples[name],
      }
    })
}

function invocationConfig(
  detail: ApiMarketEntryDetail,
  version: ApiMarketVersion,
  operation: ApiMarketOperation,
  fields: RequestField[],
) {
  let path = operation.path || ''
  const queryParams: Record<string, string> = {}
  const headers: Record<string, string> = {}
  const bodyValues: Record<string, string> = {}
  for (const field of fields) {
    const template = `{{params.${field.name}}}`
    if (field.location === 'path') {
      path = path.split(`{${field.name}}`).join(template)
    } else if (field.location === 'header') {
      headers[field.name] = template
    } else if (field.location === 'body') {
      bodyValues[field.name] = template
    } else {
      queryParams[field.name] = template
    }
  }
  const method = (operation.httpMethod || 'GET').toUpperCase()
  const bodyType = Object.keys(bodyValues).length ? 'json' : 'none'
  return {
    method,
    url: joinUrl(version.baseUrl, path),
    queryParams,
    headers,
    bodyType,
    body: bodyType === 'json' ? JSON.stringify(bodyValues) : '',
    timeoutMs: 30000,
    credentialRef: '',
    responseType: 'json',
    provider: detail.entry.provider?.name || '',
  }
}

function inputSchema(fields: RequestField[]) {
  const properties = Object.fromEntries(fields.map(field => [field.name, {
    type: jsonSchemaType(field.type),
    description: field.description,
    ...(field.defaultValue === undefined ? {} : { default: field.defaultValue }),
  }]))
  return {
    type: 'object',
    properties,
    required: fields.filter(field => field.required).map(field => field.name),
    additionalProperties: false,
  }
}

function parameterLocation(
  name: string,
  configured: unknown,
  operation: ApiMarketOperation,
): RequestField['location'] {
  const value = String(configured || '').toLowerCase()
  if (value === 'path' || value === 'query' || value === 'header' || value === 'body') return value
  if ((operation.path || '').includes(`{${name}}`)) return 'path'
  return ['GET', 'HEAD', 'DELETE', 'OPTIONS'].includes((operation.httpMethod || '').toUpperCase())
    ? 'query'
    : 'body'
}

function inputType(value: unknown) {
  const type = String(value || 'string').toLowerCase()
  if (['string', 'number', 'integer', 'boolean', 'object', 'array', 'file'].includes(type)) return type
  return 'string'
}

function jsonSchemaType(value: string) {
  return value === 'file' ? 'string' : value
}

function safeIdentifier(value: string) {
  const normalized = value.toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+|_+$/g, '')
  return normalized || 'request'
}

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

function isObject(value: unknown): value is Record<string, any> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
