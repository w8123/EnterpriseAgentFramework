import type { HttpApiConnection, HttpApiDetail } from '@/types/httpApi'
import type { WorkflowWorkingCopyInput } from '@/types/workflow'
import type { ToolNodeConfig } from '@/types/studio'
import { applyHttpApiSelection, httpApiInputTargets, httpApiOutputFields, httpApiOutputPorts,
  httpApiReadinessReason } from '@/views/workflow/httpApiWorkflow'

export interface ApiMarketWorkflowDraftOptions {
  name: string
  keySlug: string
  projectId: number
  projectCode: string
  environment: string
}

/** Author only from the server-owned accepted API. Catalog URL/marketRef is never executable proof. */
export function buildApiMarketWorkflowDraft(detail: HttpApiDetail, connection: HttpApiConnection,
  options: ApiMarketWorkflowDraftOptions): WorkflowWorkingCopyInput {
  const reason = httpApiReadinessReason(detail.summary, detail, connection, options)
  if (reason) throw new Error(reason)
  if (!detail.summary.sourceKinds.includes('API_MARKET_OPERATION')) throw new Error('该 API 不属于固定市场来源')
  const targets = httpApiInputTargets(detail.acceptedContract)
  const counts = new Map<string, number>()
  targets.forEach(target => counts.set(target.name, (counts.get(target.name) || 0) + 1))
  const inputName = (target: (typeof targets)[number]) => counts.get(target.name) === 1
    ? target.name : `${target.location.toLowerCase()}_${target.name}`
  if (targets.some(target => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(inputName(target)))) {
    throw new Error('当前参数名称不支持自动草稿映射，请在 API 选择器中显式映射')
  }
  const config: ToolNodeConfig = { inputMapping: {} }
  applyHttpApiSelection(config, detail)
  config.inputMapping = Object.fromEntries(targets.map(target => [target.key, `params.${inputName(target)}`]))
  const fields = httpApiOutputFields(detail.acceptedContract)
  const output = fields.includes('state') ? 'nodeOutput.api-node.state' : 'nodeOutput.api-node'
  const inputSchema = {
    type: 'object',
    properties: Object.fromEntries(targets.map(target => [inputName(target), { type: target.type }])),
    required: targets.filter(target => target.required).map(inputName),
    additionalProperties: false,
  }
  return {
    name: options.name.trim(), keySlug: options.keySlug.trim(), projectId: options.projectId,
    projectCode: options.projectCode, workflowKind: 'GENERAL', executionEngine: 'GRAPH_SPEC',
    definitionAuthority: 'USER', creationChannel: 'STUDIO', status: 'DRAFT',
    description: `通过已接纳项目 API ${detail.summary.httpMethod} ${detail.summary.routeTemplate}`,
    graphSpec: {
      schemaVersion: 2,
      nodes: [
        { id: 'api-node', type: 'TOOL', name: `${detail.summary.httpMethod} ${detail.summary.routeTemplate}`,
          ref: { kind: 'TOOL', name: detail.summary.qualifiedName, qualifiedName: detail.summary.qualifiedName,
            projectCode: detail.summary.projectCode },
          inputs: targets.map(target => ({ id: target.key, name: target.name, type: 'any', required: target.required,
            source: `params.${inputName(target)}` })),
          outputs: httpApiOutputPorts('api_output', detail.acceptedContract), config: { ...config, outputAlias: 'api_output', configVersion: 2 } },
        { id: 'api-variable', type: 'VARIABLE_ASSIGN', name: 'API 结果变量',
          config: { assignments: { api_result: output }, outputAlias: 'variable_output' } },
      ],
      edges: [{ id: 'api-to-variable', from: 'api-node', to: 'api-variable', condition: 'always' }],
      entryNodeId: 'api-node', exitNodeIds: ['api-variable'],
    },
    canvasJson: JSON.stringify({ schemaVersion: 1, layoutVersion: 1,
      nodes: [
        { id: 'start', position: { x: 48, y: 180 } },
        { id: 'api-node', position: { x: 330, y: 180 } },
        { id: 'api-variable', position: { x: 680, y: 180 } },
        { id: 'end', position: { x: 1030, y: 180 } },
      ],
      edges: [{ id: 'graph-entry-api-node' }, { id: 'api-to-variable' }, { id: 'graph-exit-api-variable' }],
    }),
    inputSchemaJson: JSON.stringify(inputSchema), outputSchemaJson: JSON.stringify({ type: 'object' }),
  }
}
