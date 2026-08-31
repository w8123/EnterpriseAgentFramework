import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import { createDefaultNodeData, definitionToCanvas } from '@/utils/studio'
import { useWorkflowStudioGraphAnalysis } from './useWorkflowStudioGraphAnalysis'

function node(id: string, kind: CanvasNode['data']['kind']): CanvasNode {
  return {
    id,
    type: kind,
    position: { x: 0, y: 0 },
    data: createDefaultNodeData(kind, id),
  }
}

function edge(id: string, source: string, target: string, condition: string): CanvasEdge {
  return { id, source, target, condition }
}

describe('knowledge REQUIRED evidence policy lint', () => {
  it('keeps missing legacy policy OPTIONAL while new nodes default to REQUIRED', () => {
    const legacy = definitionToCanvas({
      graphSpec: {
        schemaVersion: 2,
        entryNodeId: 'knowledge',
        exitNodeIds: ['answer'],
        nodes: [
          {
            id: 'knowledge',
            type: 'KNOWLEDGE_RETRIEVAL',
            config: { knowledgeBaseCodes: ['kb_demo'], query: 'input' },
          },
          { id: 'answer', type: 'ANSWER', config: { template: 'done' } },
        ],
        edges: [{ from: 'knowledge', to: 'answer', condition: 'always' }],
      },
    })

    expect(legacy.nodes.find((item) => item.id === 'knowledge')?.data.knowledgeConfig).toMatchObject({
      evidencePolicy: 'OPTIONAL',
      similarityThreshold: 0.5,
    })
    expect(createDefaultNodeData('knowledge', 'new').knowledgeConfig?.evidencePolicy).toBe('REQUIRED')
  })

  it('requires evidence and no_evidence routes and rejects unconditional fallback', () => {
    const knowledge = node('knowledge', 'knowledge')
    knowledge.data.knowledgeConfig!.knowledgeBaseCodes = ['kb_demo']
    expect(knowledge.data.knowledgeConfig!.evidencePolicy).toBe('REQUIRED')

    const nodes = ref<CanvasNode[]>([
      node('start', 'start'),
      knowledge,
      node('evidence_answer', 'answer'),
      node('no_evidence_answer', 'answer'),
      node('end', 'end'),
    ])
    const edges = ref<CanvasEdge[]>([
      edge('start-knowledge', 'start', 'knowledge', 'always'),
      edge('knowledge-evidence', 'knowledge', 'evidence_answer', 'route:evidence'),
      edge('evidence-end', 'evidence_answer', 'end', 'always'),
      edge('no-evidence-end', 'no_evidence_answer', 'end', 'always'),
    ])
    const analysis = useWorkflowStudioGraphAnalysis({
      nodes,
      edges,
      decorateWorkflowNode: (value) => value,
      markCanvasDirty: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined),
      getNodeMeasurement: () => undefined,
    })

    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('缺少“无证据”分支'))).toBe(true)

    edges.value.push(edge('knowledge-no-evidence', 'knowledge', 'no_evidence_answer', 'route:no_evidence'))
    expect(analysis.graphLintErrors.value.some((item) => item.nodeId === 'knowledge')).toBe(false)

    edges.value.push(edge('knowledge-always', 'knowledge', 'evidence_answer', 'always'))
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('不能使用 always/success'))).toBe(true)

    nodes.value.push(node('unsafe_llm', 'llm'))
    edges.value.push(edge('knowledge-unsafe', 'knowledge', 'unsafe_llm', 'route:no_evidence'))
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('必须直接连接固定回复节点'))).toBe(true)
  })
})
