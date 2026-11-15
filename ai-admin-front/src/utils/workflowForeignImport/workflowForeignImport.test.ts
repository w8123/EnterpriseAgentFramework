import { readFileSync } from 'fs'
import { resolve } from 'path'
import { describe, expect, it } from 'vitest'
import { convertForeignWorkflow } from './index'
import { convertDifyDsl } from './dify'

const fixture = (name: string) => readFileSync(resolve(__dirname, '__fixtures__', name), 'utf8')

type GraphNode = { id: string; type: string; name?: string; config: Record<string, unknown>; errorPolicy?: { strategy?: string; defaultOutput?: Record<string, unknown> } }
type GraphEdge = { from: string; to: string; condition: string }

function graph(result: NonNullable<ReturnType<typeof convertForeignWorkflow>>) {
  const spec = result.graphSpec as { schemaVersion: number; nodes: GraphNode[]; edges: GraphEdge[]; entryNodeId: string; exitNodeIds: string[] }
  const byName = (name: string) => {
    const node = spec.nodes.find((item) => item.name === name)
    if (!node) throw new Error(`missing ${name}: ${spec.nodes.map((item) => item.name).join(',')}`)
    return node
  }
  return { spec, byName, out: (id: string) => spec.edges.filter((edge) => edge.from === id) }
}

describe('Dify DSL import', () => {
  const result = convertForeignWorkflow(fixture('dify-chatflow.yml'), 'kefu.yml')!

  it('builds a GraphSpec v2 draft with the app name', () => {
    expect(result.name).toBe('客服助手')
    expect(result.report.sourceLabel).toBe('Dify Chatflow')
    const { spec } = graph(result)
    expect(spec.schemaVersion).toBe(2)
    expect(spec.nodes.map((node) => node.type)).toEqual(expect.arrayContaining([
      'USER_INPUT', 'INTENT_CLASSIFIER', 'KNOWLEDGE_RETRIEVAL', 'LLM', 'PARAMETER_EXTRACT', 'IF_ELSE', 'HTTP_REQUEST', 'CODE', 'TOOL', 'ANSWER',
    ]))
    expect(spec.nodes.some((node) => node.name === '')).toBe(false)
  })

  it('rewrites Dify variable references', () => {
    const { byName } = graph(result)
    const llm = JSON.stringify(byName('回答产品问题').config)
    expect(llm).toContain('{{ nodeOutput.knowledge_retrieval_1711528917469 }}')
    expect(llm).toContain('{{ params.level }}')
    expect(llm).toContain('{{ input.message }}')
    expect(JSON.stringify(byName('查订单').config)).toContain('{{ nodeOutput.parameter_extractor_1711528919501.order_no }}')
    expect(JSON.stringify(byName('回复订单').config)).toContain('nodeOutput.agg1.output')
  })

  it('binds each imported start field to its own named input', () => {
    const { byName } = graph(result)
    const start = byName('开始')
    const fields = start.config.fields as Array<{ name: string; source?: string }>
    expect(fields.length).toBeGreaterThan(0)
    for (const field of fields) expect(field.source).toBe(`input.${field.name}`)
  })

  it('preserves failure edges and typed default outputs', () => {
    const node = (id: string, type: string, extra: Record<string, unknown> = {}): { id: string; data: Record<string, unknown> } => ({ id, data: { type, title: id, ...extra } })
    const input = {
      app: { name: 'test', mode: 'workflow' },
      workflow: { graph: {
        nodes: [node('start', 'start'), node('code', 'code', {
          code_language: 'javascript', code: 'function main() { throw new Error("bad") }',
          error_strategy: 'fail-branch',
        }), node('good', 'end'), node('bad', 'end')],
        edges: [{ source: 'start', target: 'code' }, { source: 'code', target: 'good', sourceHandle: 'source' },
          { source: 'code', target: 'bad', sourceHandle: 'fail-branch' }],
      } },
    }
    const failed = graph(convertDifyDsl(input))
    const code = failed.byName('code')
    expect(code.errorPolicy?.strategy).not.toBe('CONTINUE')
    expect(failed.out(code.id).map((edge) => edge.condition)).toContain('error')

    input.workflow.graph.nodes[1].data.error_strategy = 'default-value'
    Object.assign(input.workflow.graph.nodes[1].data, { default_value: [
      { key: 'text', type: 'string', value: 'fallback' },
      { key: 'count', type: 'number', value: '2' },
      { key: 'items', type: 'array[number]', value: '[1,2]' },
    ] })
    const fallback = graph(convertDifyDsl(input)).byName('code')
    expect(fallback.errorPolicy).toMatchObject({ strategy: 'CONTINUE', defaultOutput: { text: 'fallback', count: 2, items: [1, 2] } })
  })

  it('rejects fallback shapes that cannot preserve the source output semantics', () => {
    const input = { app: { name: 'test', mode: 'workflow' }, workflow: { graph: { nodes: [
      { id: 's', data: { type: 'start', title: 'start' } },
      { id: 'llm', data: { type: 'llm', title: 'llm', error_strategy: 'default-value',
        default_value: [{ key: 'text', type: 'string', value: 'fallback' }] } },
      { id: 'e', data: { type: 'end', title: 'end' } },
    ], edges: [{ source: 's', target: 'llm' }, { source: 'llm', target: 'e' }] } } }
    expect(() => convertDifyDsl(input)).toThrow(/默认值错误处理暂不能安全转换/)
  })

  it('maps classifier and if-else handles to routes', () => {
    const { byName, out } = graph(result)
    const classifier = byName('问题分类')
    expect(out(classifier.id).map((edge) => edge.condition).sort()).toEqual(['route:1', 'route:2'])
    const ifNode = byName('有订单号吗')
    expect(out(ifNode.id).map((edge) => edge.condition).sort()).toEqual(['else', 'route:true'])
  })

  it('serializes the parallel branches and never imports secrets', () => {
    const { byName, out } = graph(result)
    const http = byName('查订单')
    expect(out(http.id)).toHaveLength(1)
    expect(result.report.remarks.join()).toContain('依次执行')
    expect(JSON.stringify(result.graphSpec)).not.toContain('secret-should-not-import')
    const tool = byName('发飞书通知')
    expect(out(tool.id).map((edge) => edge.to)).toEqual([byName('汇总').id])
  })

  it('reports placeholders the author has to finish', () => {
    const todo = result.report.notes.filter((note) => note.level === 'todo').map((note) => note.node)
    expect(todo).toEqual(expect.arrayContaining(['查产品知识', '发飞书通知']))
    expect(result.report.placeholders).toBe(todo.length)
  })

  it('turns an iteration into a FOREACH loop', () => {
    const loopResult = convertForeignWorkflow(fixture('dify-iteration.yml'), 'a.yml')!
    const { byName, spec } = graph(loopResult)
    const loop = byName('逐句翻译')
    expect(loop.type).toBe('LOOP')
    expect(loop.config).toMatchObject({ mode: 'FOREACH', collection: 'params.lines', parallelism: 4, bodyNodeIds: ['tr'], bodyEntry: 'tr', bodyExit: 'tr' })
    expect(JSON.stringify(byName('翻译').config)).toContain('{{ var.item }}')
    expect(JSON.stringify(byName('结束').config)).toContain('var.it_results')
    expect(spec.edges.some((edge) => edge.to === 'tr' || edge.from === 'tr')).toBe(false)
  })

  it('explains apps without a workflow graph', () => {
    expect(() => convertForeignWorkflow('app:\n  mode: agent-chat\n  name: x\nkind: app\nversion: 0.1.5\n', 'a.yml')).toThrow(/Agent/)
  })
})

describe('n8n import', () => {
  const result = convertForeignWorkflow(fixture('n8n-webhook.json'), 'flow.json')!

  it('converts nodes and skips model sub-nodes, notes and no-ops', () => {
    expect(result.name).toBe('工单分派')
    const { spec } = graph(result)
    const names = spec.nodes.map((node) => node.name)
    expect(names).not.toContain('OpenAI Chat Model')
    expect(names).not.toContain('Sticky Note')
    expect(names).not.toContain('No Operation')
    expect(names).toEqual(expect.arrayContaining(['Webhook', 'Edit Fields', 'If', 'Create Issue', 'AI Agent', 'Slack', 'Respond']))
  })

  it('rewrites $json references and collects webhook fields', () => {
    const { byName } = graph(result)
    const set = byName('Edit Fields')
    expect(JSON.stringify(set.config)).toContain('params.title')
    const input = byName('Webhook')
    expect(JSON.stringify(input.config)).toContain('"title"')
    const fields = input.config.fields as Array<{ name: string; source?: string }>
    for (const field of fields) expect(field.source).toBe(`input.${field.name}`)
    const ifNode = byName('If')
    expect(JSON.stringify(ifNode.config)).toContain(`nodeOutput.${set.id}.priority`)
    expect(JSON.stringify(byName('Respond').config)).toContain(`nodeOutput.${byName('Create Issue').id}.parsedBody.key`)
    expect(JSON.stringify(byName('Slack').config)).toContain(`nodeOutput.${byName('AI Agent').id}`)
  })

  it('bridges the no-op and maps if outputs', () => {
    const { byName, out } = graph(result)
    expect(out(byName('Create Issue').id).map((edge) => edge.to)).toEqual([byName('Respond').id])
    expect(out(byName('If').id).map((edge) => edge.condition).sort()).toEqual(['else', 'route:true'])
  })

  it('flags untranslatable expressions and app nodes', () => {
    const set = result.report.notes.find((note) => note.node === 'Edit Fields')!
    expect(set.level).toBe('review')
    const slack = result.report.notes.find((note) => note.node === 'Slack')!
    expect(slack.level).toBe('todo')
  })

  it('returns null for ReachAI files', () => {
    expect(convertForeignWorkflow(JSON.stringify({ format: 'reachai-workflow', version: 1 }), 'a.json')).toBeNull()
  })
})
