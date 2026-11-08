import { describe, expect, it } from 'vitest'
import { buildWorkflowFile, parseWorkflowFile, stripEnvironmentBindings } from './workflowExchange'

const graph = {
  schemaVersion: 2,
  entryNodeId: 'llm',
  exitNodeIds: ['tool'],
  nodes: [
    { id: 'llm', type: 'LLM', config: { modelInstanceId: 'm-9', llmConfig: { modelInstanceId: 'm-9' } } },
    { id: 'tool', type: 'TOOL', ref: { kind: 'TOOL', qualifiedName: 'erp.createCustomer' }, config: {} },
    { id: 'kb', type: 'KNOWLEDGE_RETRIEVAL', config: { knowledgeBaseCodes: ['faq'] } },
  ],
  edges: [],
}

describe('workflow file exchange', () => {
  it('exports with a dependency list and imports it back', () => {
    const file = buildWorkflowFile({ name: '建客户', keySlug: 'create', graphSpecJson: JSON.stringify(graph), canvasJson: '{"schemaVersion":1}' })
    expect(file.dependencies.tools).toEqual(['erp.createCustomer'])
    expect(file.dependencies.knowledgeBases).toEqual(['faq'])
    const parsed = parseWorkflowFile(JSON.stringify({ ...file, dependencies: { tools: [], knowledgeBases: [], subWorkflows: [], models: [] } }))
    expect(parsed.workflow.name).toBe('建客户')
    expect(parsed.dependencies.tools).toEqual(['erp.createCustomer'])
  })

  it('rejects files that are not ReachAI workflows', () => {
    expect(() => parseWorkflowFile('not json')).toThrow('JSON')
    expect(() => parseWorkflowFile('{"format":"other"}')).toThrow('ReachAI')
    expect(() => parseWorkflowFile(JSON.stringify({ format: 'reachai-workflow', version: 99, graphSpec: graph }))).toThrow('版本')
    expect(() => parseWorkflowFile(JSON.stringify({ format: 'reachai-workflow', version: 1, graphSpec: { nodes: [] } }))).toThrow('GraphSpec')
  })

  it('drops model choices from another server', () => {
    const stripped = stripEnvironmentBindings(graph) as typeof graph
    expect(stripped.nodes[0].config).toEqual({ llmConfig: {} })
    expect(graph.nodes[0].config.modelInstanceId).toBe('m-9')
  })
})
