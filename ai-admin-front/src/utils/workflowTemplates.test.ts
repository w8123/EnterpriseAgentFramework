import { describe, expect, it } from 'vitest'
import { definitionToCanvas } from '@/utils/studio'
import { WORKFLOW_TEMPLATES, templateDocuments } from './workflowTemplates'

describe('built-in workflow templates', () => {
  it('ships six templates with unique keys', () => {
    expect(WORKFLOW_TEMPLATES).toHaveLength(6)
    expect(new Set(WORKFLOW_TEMPLATES.map((item) => item.key)).size).toBe(6)
  })

  for (const template of WORKFLOW_TEMPLATES) {
    it(`${template.name} builds a valid GraphSpec draft that reopens in Studio`, () => {
      const docs = templateDocuments(template)
      const graph = JSON.parse(docs.graphSpecJson)
      expect(graph.schemaVersion).toBe(2)
      expect(graph.entryNodeId).toBe('input')
      expect(graph.exitNodeIds.length).toBeGreaterThan(0)
      const ids = new Set(graph.nodes.map((node: { id: string }) => node.id))
      for (const edge of graph.edges) {
        expect(ids.has(edge.from)).toBe(true)
        expect(ids.has(edge.to)).toBe(true)
      }
      expect(graph.inputSchema?.type).toBe('object')
      const canvas = definitionToCanvas({ graphSpec: graph, canvasJson: docs.canvasJson } as never)
      expect(canvas.nodes.length).toBe(graph.nodes.length + 2)
      const layout = JSON.parse(docs.canvasJson)
      expect(layout.schemaVersion).toBe(1)
    })
  }

  it('keeps branch routes on condition, approval, knowledge and classifier edges', () => {
    const routes = WORKFLOW_TEMPLATES.flatMap((template) => JSON.parse(templateDocuments(template).graphSpecJson).edges)
      .map((edge: { condition: string }) => edge.condition)
    expect(routes).toEqual(expect.arrayContaining(['route:has_low', 'route:approved', 'route:rejected', 'route:evidence', 'route:no_evidence', 'route:billing', 'else']))
  })

  it('binds the inventory threshold input to the filter in the saved graph', () => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.key === 'inventory-alert')!
    const graph = JSON.parse(templateDocuments(template).graphSpecJson)
    const input = graph.nodes.find((node: { id: string }) => node.id === 'input')
    const filter = graph.nodes.find((node: { id: string }) => node.id === 'pick_low')
    expect(input.config.fields).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: 'threshold', source: 'params.threshold', defaultValue: '10' }),
    ]))
    expect(filter.config.operations[0].conditions[0]).toMatchObject({
      left: 'item.quantity', operator: 'lt', right: 'params.threshold',
    })
  })
})
