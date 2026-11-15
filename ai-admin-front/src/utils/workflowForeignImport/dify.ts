import type {
  CanvasNodeData,
  CanvasNodeKind,
  FieldType,
  ListOperation,
  StudioCondition,
  StudioConditionGroup,
  StudioFieldSchema,
} from '@/types/studio'
import {
  DraftGraph,
  answerData,
  asList,
  asRecord,
  conditionOperator,
  llmData,
  placeholderTool,
  routeId,
  slugFrom,
  str,
  type ForeignImportResult,
} from './shared'

/**
 * Converts a Dify app DSL (the YAML from 「导出 DSL」, already parsed) into a ReachAI draft.
 * Every Dify node maps onto an existing ReachAI node type; what has no counterpart becomes an unbound
 * tool placeholder so the shape of the flow is kept.
 */

interface DifyNode {
  id: string
  type: string
  title: string
  data: Record<string, unknown>
  x: number
  y: number
  parent: string
}

interface DifyEdge {
  source: string
  target: string
  sourceHandle: string
}

/** How later nodes read a converted node's outputs. */
type OutputRef = (rest: string[]) => string

const SKIPPED = new Set(['iteration-start', 'loop-start', 'loop-end', ''])

const SYS_VARS: Record<string, string> = {
  query: 'input.message',
  files: 'input.files',
  user_id: 'sys.userId',
  conversation_id: 'sys.conversationId',
  dialogue_count: 'sys.dialogueCount',
  app_id: 'sys.appId',
  workflow_id: 'sys.workflowId',
  workflow_run_id: 'sys.runId',
}

export function isDifyDsl(value: unknown): boolean {
  const root = asRecord(value)
  const graph = asRecord(asRecord(root.workflow).graph)
  if (Array.isArray(graph.nodes)) return true
  return root.kind === 'app' && !!root.app
}

export function convertDifyDsl(value: unknown): ForeignImportResult {
  const root = asRecord(value)
  const app = asRecord(root.app)
  const mode = str(app.mode)
  if (mode && !['workflow', 'advanced-chat'].includes(mode)) {
    throw new Error(`这是 Dify 的「${mode === 'agent-chat' ? 'Agent' : mode === 'completion' ? '文本生成' : '聊天助手'}」应用，没有工作流图。只有「工作流」和「Chatflow」能导入；这类应用可以在「添加 API」里按 Dify 应用接入后直接调用。`)
  }
  const graph = asRecord(asRecord(root.workflow).graph)
  const rawNodes = asList(graph.nodes).map(asRecord)
  if (!rawNodes.length) throw new Error('Dify 文件里没有工作流节点')

  const positions = new Map<string, { x: number; y: number }>()
  for (const raw of rawNodes) {
    const position = asRecord(raw.position)
    positions.set(str(raw.id), { x: Number(position.x) || 0, y: Number(position.y) || 0 })
  }
  const nodes: DifyNode[] = rawNodes.map((raw) => {
    const data = asRecord(raw.data)
    const id = str(raw.id)
    const parent = str(raw.parentId) || str(data.iteration_id) || str(data.loop_id)
    const own = positions.get(id) || { x: 0, y: 0 }
    const parentPos = parent ? positions.get(parent) : undefined
    return {
      id,
      type: str(data.type),
      title: str(data.title) || str(data.type) || id,
      data,
      // Children of an iteration/loop are positioned relative to it.
      x: own.x + (parentPos && parent !== id ? parentPos.x : 0),
      y: own.y + (parentPos && parent !== id ? parentPos.y + 80 : 0),
      parent: parent === id ? '' : parent,
    }
  }).filter((node) => node.id && node.type !== 'custom-note')
  const edges: DifyEdge[] = asList(graph.edges).map(asRecord).map((edge) => ({
    source: str(edge.source),
    target: str(edge.target),
    sourceHandle: str(edge.sourceHandle) || 'source',
  }))

  const draft = new DraftGraph()
  const ids = new Map<string, string>()
  const refs = new Map<string, OutputRef>()
  const startIds = new Set<string>()
  const loopItem = new Map<string, { item: string; index: string }>()
  const byId = new Map(nodes.map((node) => [node.id, node]))

  for (const node of nodes) {
    if (SKIPPED.has(node.type)) continue
    ids.set(node.id, draft.uniqueId(node.id, node.type.replace(/-/g, '_')))
  }
  // Output references are known before any node is converted, so forward references (rare) still resolve.
  for (const node of nodes) {
    const id = ids.get(node.id)
    if (!id) continue
    if (node.type === 'start') startIds.add(node.id)
    refs.set(node.id, outputRef(node, id))
    if (node.type === 'iteration') loopItem.set(node.id, { item: 'item', index: 'index' })
  }

  function outputRef(node: DifyNode, id: string): OutputRef {
    const base = `nodeOutput.${id}`
    const tail = (rest: string[]) => (rest.length ? `${base}.${rest.join('.')}` : base)
    switch (node.type) {
      case 'llm':
      case 'knowledge-retrieval':
      case 'template-transform':
      case 'question-classifier':
      case 'document-extractor':
      case 'agent':
        return (rest) => (!rest.length || ['text', 'result', 'output', 'class_name'].includes(rest[0]) ? base
          : rest[0] === 'structured_output' ? tail(rest.slice(1)) : tail(rest))
      case 'http-request':
        return (rest) => {
          if (rest[0] === 'status_code') return `${base}.statusCode`
          if (rest[0] === 'headers') return tail(['headers', ...rest.slice(1)])
          return `${base}.body`
        }
      case 'iteration':
        return () => `var.${id}_results`
      case 'loop':
        return (rest) => (rest.length ? `var.${rest.join('.')}` : base)
      case 'list-operator':
        return (rest) => (rest[0] === 'first_record' ? `${base}.first` : rest[0] === 'last_record' ? `${base}.last` : `${base}.items`)
      case 'parameter-extractor':
        return (rest) => (!rest.length || rest[0].startsWith('__') ? base : tail(rest))
      case 'variable-aggregator':
      case 'variable-assigner':
        return (rest) => (!rest.length ? `${base}.output` : rest[0] === 'output' ? `${base}.output` : `${base}.${rest[0]}`)
      default:
        return tail
    }
  }

  function refPath(selector: unknown): string {
    const parts = asList(selector).map(str).filter(Boolean)
    if (!parts.length) return ''
    const [head, ...rest] = parts
    if (head === 'sys') return SYS_VARS[rest[0]] || `sys.${rest.join('.')}`
    if (head === 'env') return `env.${rest.join('.')}`
    if (head === 'conversation') return `var.${rest.join('.')}`
    if (startIds.has(head)) return rest.length ? `params.${rest.join('.')}` : 'params'
    const loop = loopItem.get(head)
    if (loop && (rest[0] === 'item' || rest[0] === 'index')) {
      return `var.${rest[0] === 'item' ? loop.item : loop.index}${rest.length > 1 ? `.${rest.slice(1).join('.')}` : ''}`
    }
    const ref = refs.get(head)
    if (ref) return ref(rest)
    return `nodeOutput.${head}${rest.length ? `.${rest.join('.')}` : ''}`
  }

  /** `{{#node.var#}}` → `{{ path }}`; `{{#context#}}` → the LLM's context variable. */
  function text(value: unknown, context = ''): string {
    return str(value).replace(/\{\{#([^#}]+)#\}\}/g, (_all, inner: string) => {
      const trimmed = inner.trim()
      if (trimmed === 'context') return context ? `{{ ${context} }}` : ''
      return `{{ ${refPath(trimmed.split('.'))} }}`
    })
  }

  /** A whole value that is a single reference becomes a bare path; anything else stays a template/literal. */
  function valueExpr(value: unknown): string {
    if (Array.isArray(value)) return refPath(value)
    const raw = str(value).trim()
    const single = raw.match(/^\{\{#([^#}]+)#\}\}$/)
    if (single) return refPath(single[1].trim().split('.'))
    return text(raw)
  }

  for (const node of nodes) {
    const id = ids.get(node.id)
    if (!id) continue
    convert(node, id)
  }

  function add(node: DifyNode, id: string, kind: CanvasNodeKind, data: Partial<CanvasNodeData>) {
    const description = str(node.data.desc)
    draft.add({ id, kind, label: node.title, x: node.x, y: node.y, data: { ...(description ? { description } : {}), ...data } })
    if (node.data.error_strategy === 'default-value') {
      const last = draft.nodes[draft.nodes.length - 1]
      last.data = { ...last.data, errorPolicy: { strategy: 'CONTINUE', defaultOutput: difyDefaultOutput(node) } }
    }
  }

  function convert(node: DifyNode, id: string) {
    const d = node.data
    const from = `Dify ${node.type}`
    switch (node.type) {
      case 'start': {
        const fields: StudioFieldSchema[] = asList(d.variables).map(asRecord).map((item) => {
          const options = asList(item.options).map(str).filter(Boolean)
          const field: StudioFieldSchema = {
            name: str(item.variable) || 'value',
            type: fieldType(str(item.type)),
            required: item.required !== false,
            description: str(item.label) || str(item.variable),
            source: `input.${str(item.variable) || 'value'}`,
          }
          if (options.length) field.options = options.map((option) => ({ label: option, value: option }))
          if (item.default !== undefined && item.default !== null && item.default !== '') field.defaultValue = str(item.default)
          return field
        })
        add(node, id, 'userInput', { userInputConfig: { outputAlias: 'params', fields } } as Partial<CanvasNodeData>)
        draft.note(node.title, from, fields.length ? `开始输入，${fields.length} 个字段` : '开始输入', 'ok')
        return
      }
      case 'end': {
        const outputs = asList(d.outputs).map(asRecord)
        const template = outputs.length === 1
          ? `{{ ${refPath(outputs[0].value_selector)} }}`
          : outputs.map((item) => `${str(item.variable)}：{{ ${refPath(item.value_selector)} }}`).join('\n') || '{{ lastOutput }}'
        add(node, id, 'answer', answerData(template))
        draft.note(node.title, from, '回复（结束输出）', 'ok')
        return
      }
      case 'answer':
        add(node, id, 'answer', answerData(text(d.answer) || '{{ lastOutput }}'))
        draft.note(node.title, from, '回复', 'ok')
        return
      case 'llm': {
        const context = asRecord(d.context)
        const contextPath = context.enabled ? refPath(context.variable_selector) : ''
        const prompts = Array.isArray(d.prompt_template) ? asList(d.prompt_template).map(asRecord) : [asRecord(d.prompt_template)]
        const pick = (item: Record<string, unknown>) => text(item.edition_type === 'jinja2' ? item.jinja2_text || item.text : item.text, contextPath)
        const system = prompts.filter((item) => str(item.role) === 'system').map(pick).join('\n\n')
        const user = prompts.filter((item) => str(item.role) !== 'system').map(pick).join('\n\n')
          || (contextPath ? `{{ input.message }}\n\n参考资料：{{ ${contextPath} }}` : '{{ input.message }}')
        const model = asRecord(d.model)
        const params = asRecord(model.completion_params)
        add(node, id, 'llm', llmData(system, user, {
          json: d.structured_output_enabled === true,
          temperature: typeof params.temperature === 'number' ? params.temperature : undefined,
        }))
        const extras: string[] = []
        if (str(model.name)) extras.push(`原模型 ${str(model.provider).split('/').pop() || ''} ${str(model.name)}，现用工作流默认模型`)
        if (asRecord(d.memory).window || d.memory) extras.push('Dify 的对话记忆没有带过来')
        if (asRecord(d.vision).enabled) extras.push('图片输入需要重新配置')
        if (prompts.some((item) => item.edition_type === 'jinja2')) extras.push('Jinja2 提示词请检查')
        draft.note(node.title, from, `LLM${extras.length ? `（${extras.join('；')}）` : ''}`, extras.length > 1 ? 'review' : 'ok')
        return
      }
      case 'knowledge-retrieval': {
        const datasets = asList(d.dataset_ids).length
        const multi = asRecord(d.multiple_retrieval_config)
        const topK = Number(multi.top_k) || undefined
        add(node, id, 'knowledge', {
          knowledgeConfig: {
            knowledgeBaseCodes: [],
            query: refPath(d.query_variable_selector) || 'input.message',
            evidencePolicy: 'OPTIONAL',
            ...(topK ? { topK: Math.min(20, topK) } : {}),
          },
          needsConfiguration: true,
          placeholderReason: `请选择知识库（原来连了 Dify 的 ${datasets} 个知识库）`,
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, `知识检索，需要重新选择知识库（原 ${datasets} 个）`, 'todo')
        return
      }
      case 'if-else': {
        const cases = asList(d.cases).map(asRecord)
        const groups: StudioConditionGroup[] = (cases.length ? cases : [{ case_id: 'true', logical_operator: d.logical_operator, conditions: d.conditions }])
          .map((item, index) => ({
            id: routeId(str(item.case_id), `case_${index + 1}`),
            label: cases.length > 1 ? `分支 ${index + 1}` : '是',
            logic: str(item.logical_operator).toLowerCase() === 'or' ? 'OR' : 'AND',
            conditions: asList(item.conditions).map(asRecord).map(condition).filter((c): c is StudioCondition => !!c),
          }))
        add(node, id, 'condition', { conditionConfig: { groups, defaultRoute: 'else' } } as Partial<CanvasNodeData>)
        const total = cases.length ? cases.reduce((sum, item) => sum + asList(item.conditions).length, 0) : asList(d.conditions).length
        const kept = groups.reduce((sum, group) => sum + group.conditions.length, 0)
        draft.note(node.title, from, `条件分支，${groups.length} 个分支${kept < total ? `，有 ${total - kept} 个条件没转过来，请补上` : ''}`, kept < total ? 'review' : 'ok')
        return
      }
      case 'question-classifier': {
        const classes = asList(d.classes).map(asRecord).map((item, index) => ({
          id: routeId(str(item.id), `class_${index + 1}`),
          label: str(item.name) || `分类 ${index + 1}`,
          description: str(item.name),
          keywords: [],
        }))
        add(node, id, 'classifier', {
          classifierConfig: {
            inputExpression: refPath(d.query_variable_selector) || 'input.message',
            strategy: 'LLM',
            classes,
            defaultRoute: classes[classes.length - 1]?.id || 'else',
            confidenceThreshold: 0.5,
            llmPrompt: text(d.instruction || d.instructions),
            modelInstanceId: '',
            modelParams: {},
          },
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, `问题分类（AI 判断），${classes.length} 类；判断不出时走最后一类`, 'ok')
        return
      }
      case 'parameter-extractor': {
        const fields: StudioFieldSchema[] = asList(d.parameters).map(asRecord).map((item) => {
          const options = asList(item.options).map(str).filter(Boolean)
          return {
            name: str(item.name) || 'value',
            type: fieldType(str(item.type)),
            required: item.required === true,
            description: str(item.description),
            ...(options.length ? { options: options.map((option) => ({ label: option, value: option })) } : {}),
          }
        })
        add(node, id, 'parameter', {
          parameterConfig: {
            mode: 'llm',
            inputExpression: refPath(d.query) || 'input.message',
            systemPrompt: text(d.instruction),
            userPrompt: '',
            modelParams: {},
            fields,
          },
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, `参数提取（AI），${fields.length} 个字段`, 'ok')
        return
      }
      case 'code': {
        const language = str(d.code_language)
        const inputs: Record<string, string> = {}
        for (const item of asList(d.variables).map(asRecord)) {
          if (str(item.variable)) inputs[str(item.variable)] = refPath(item.value_selector)
        }
        const outputs: Record<string, string> = {}
        for (const [name, spec] of Object.entries(asRecord(d.outputs))) outputs[name] = str(asRecord(spec).type) || 'string'
        if (!Object.keys(outputs).length) outputs.result = 'string'
        const isJs = language === 'javascript'
        const code = isJs ? str(d.code) : pythonStub(str(d.code), Object.keys(outputs))
        add(node, id, 'code', {
          codeConfig: { language: 'javascript', code, inputs, outputs },
          ...(isJs ? {} : { needsConfiguration: true, placeholderReason: '原来是 Python 代码，ReachAI 只能运行 JavaScript，请改写' }),
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, isJs ? '代码（JavaScript 原样保留，请试运行确认）' : '代码：Python 需要改写成 JavaScript，原代码放在注释里', isJs ? 'review' : 'todo')
        return
      }
      case 'template-transform': {
        const names = new Map<string, string>()
        for (const item of asList(d.variables).map(asRecord)) names.set(str(item.variable), refPath(item.value_selector))
        const source = str(d.template)
        const converted = source.replace(/\{\{\s*([A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z0-9_]+)*)\s*\}\}/g, (all, name: string, path: string) => {
          const target = names.get(name)
          return target ? `{{ ${target}${path || ''} }}` : all
        })
        const jinja = /\{%|\|\s*[a-z]/.test(source)
        add(node, id, 'template', { template: converted, writeToAnswer: false } as Partial<CanvasNodeData>)
        draft.note(node.title, from, jinja ? '模板（含 Jinja2 循环/过滤器，这部分不支持，请改写）' : '模板', jinja ? 'review' : 'ok')
        return
      }
      case 'http-request': {
        const body = asRecord(d.body)
        const bodyType = str(body.type)
        const auth = asRecord(d.authorization)
        let bodyText = ''
        if (Array.isArray(body.data)) {
          const items = asList(body.data).map(asRecord)
          if (bodyType === 'json' || bodyType === 'raw-text') bodyText = text(items[0]?.value)
          else bodyText = JSON.stringify(Object.fromEntries(items.map((item) => [str(item.key), valueTemplate(item.value)])), null, 2)
        } else {
          bodyText = text(body.data)
        }
        const timeout = asRecord(d.timeout)
        add(node, id, 'http', {
          httpConfig: {
            method: (str(d.method) || 'get').toUpperCase(),
            url: text(d.url),
            queryParams: keyValues(str(d.params), text),
            headers: keyValues(str(d.headers), text),
            bodyType: bodyType === 'none' || !bodyType ? 'none' : bodyType === 'raw-text' ? 'text' : 'json',
            body: bodyText,
            timeoutMs: Math.min(600000, Math.max(1000, (Number(timeout.max_read_timeout) || 30) * 1000)),
            credentialRef: '',
          },
        } as Partial<CanvasNodeData>)
        const notes: string[] = []
        if (str(auth.type) && str(auth.type) !== 'no-auth') notes.push('原来带了鉴权，请在节点里选择凭据（密钥没有导入）')
        if (bodyType === 'form-data' || bodyType === 'x-www-form-urlencoded') notes.push('表单请求体改成了 JSON')
        draft.note(node.title, from, `HTTP 请求${notes.length ? `（${notes.join('；')}）` : ''}`, notes.length ? 'review' : 'ok')
        return
      }
      case 'variable-aggregator':
      case 'variable-assigner': {
        const advanced = asRecord(d.advanced_settings)
        const groups = advanced.group_enabled ? asList(advanced.groups).map(asRecord) : []
        const inputs: Record<string, string> = {}
        let code: string
        let outputs: Record<string, string>
        if (groups.length) {
          const lines: string[] = []
          outputs = {}
          groups.forEach((group, groupIndex) => {
            const name = routeId(str(group.group_name), `group_${groupIndex + 1}`)
            const keys = asList(group.variables).map((selector, index) => {
              const key = `${name}_${index}`
              inputs[key] = refPath(selector)
              return key
            })
            outputs[name] = 'object'
            lines.push(`    ${name}: { output: first(${JSON.stringify(keys)}) },`)
          })
          code = coalesceCode(lines.join('\n'))
        } else {
          const keys = asList(d.variables).map((selector, index) => {
            const key = `v${index}`
            inputs[key] = refPath(selector)
            return key
          })
          outputs = { output: str(d.output_type) || 'string' }
          code = coalesceCode(`    output: first(${JSON.stringify(keys)}),`)
        }
        add(node, id, 'code', { codeConfig: { language: 'javascript', code, inputs, outputs } } as Partial<CanvasNodeData>)
        draft.note(node.title, from, '变量聚合（用代码节点取第一个有值的变量）', 'ok')
        return
      }
      case 'assigner': {
        const assignments: Record<string, string> = {}
        for (const item of asList(d.items).map(asRecord)) {
          const target = asList(item.variable_selector).map(str)
          const name = target[target.length - 1]
          if (!name) continue
          assignments[name] = item.input_type === 'constant' ? str(item.value) : refPath(item.value)
        }
        if (!Object.keys(assignments).length && d.assigned_variable_selector) {
          const target = asList(d.assigned_variable_selector).map(str)
          assignments[target[target.length - 1] || 'value'] = refPath(d.input_variable_selector)
        }
        add(node, id, 'variable', { assignments } as Partial<CanvasNodeData>)
        draft.note(node.title, from, '变量赋值（会话变量变成本次运行的 var.变量，不会跨对话保存）', 'review')
        return
      }
      case 'list-operator': {
        const operations: ListOperation[] = []
        const filter = asRecord(d.filter_by)
        if (filter.enabled) {
          const conditions: StudioCondition[] = []
          for (const item of asList(filter.conditions).map(asRecord)) {
            const operator = conditionOperator(str(item.comparison_operator))
            if (operator) conditions.push({ left: `item.${str(item.key) || 'value'}`, operator, right: valueExpr(item.value) })
          }
          if (conditions.length) operations.push({ type: 'filter', logic: 'AND', conditions })
        }
        const order = asRecord(d.order_by)
        if (order.enabled) operations.push({ type: 'sort', field: str(order.key) || undefined, order: str(order.value) === 'desc' ? 'desc' : 'asc' })
        const extract = asRecord(d.extract_by)
        if (extract.enabled) {
          const serial = Math.max(1, Number(extract.serial) || 1)
          operations.push({ type: 'offset', count: serial - 1 }, { type: 'limit', count: 1 })
        }
        const limit = asRecord(d.limit)
        if (limit.enabled) operations.push({ type: 'limit', count: Number(limit.size) || 10 })
        add(node, id, 'listOperator', { listConfig: { source: refPath(d.variable), operations } } as Partial<CanvasNodeData>)
        draft.note(node.title, from, '列表操作', 'ok')
        return
      }
      case 'iteration': {
        const children = nodes.filter((child) => child.parent === node.id && !SKIPPED.has(child.type))
        const bodyIds = children.map((child) => ids.get(child.id)).filter((value): value is string => !!value)
        const startId = str(d.start_node_id)
        const entryEdge = edges.find((edge) => edge.source === startId)
        const entry = (entryEdge && ids.get(entryEdge.target)) || bodyIds[0] || ''
        const exit = children.map((child) => child.id).find((childId) => !edges.some((edge) => edge.source === childId && ids.has(edge.target) && byId.get(edge.target)?.parent === node.id))
        const errorMode = str(d.error_handle_mode)
        add(node, id, 'loop', {
          loopConfig: {
            mode: 'FOREACH',
            collection: refPath(d.iterator_selector),
            itemAlias: 'item',
            indexAlias: 'index',
            outputAlias: `${id}_results`,
            bodyOutput: refPath(d.output_selector) || 'lastOutput',
            maxIterations: 100,
            parallelism: d.is_parallel ? Math.min(4, Math.max(1, Number(d.parallel_nums) || 1)) : 1,
            errorMode: errorMode === 'continue-on-error' || errorMode === 'remove-abnormal-output' ? 'SKIP' : 'TERMINATE',
            bodyEntry: entry,
            bodyExit: (exit && ids.get(exit)) || entry,
            bodyNodeIds: bodyIds,
          },
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, `循环（逐项处理），循环体 ${bodyIds.length} 个节点`, 'ok')
        return
      }
      case 'loop': {
        const children = nodes.filter((child) => child.parent === node.id && !SKIPPED.has(child.type))
        const bodyIds = children.map((child) => ids.get(child.id)).filter((value): value is string => !!value)
        const startId = str(d.start_node_id)
        const entryEdge = edges.find((edge) => edge.source === startId)
        const entry = (entryEdge && ids.get(entryEdge.target)) || bodyIds[0] || ''
        const exit = children.map((child) => child.id).find((childId) => !edges.some((edge) => edge.source === childId && ids.has(edge.target) && byId.get(edge.target)?.parent === node.id))
        const breakConditions = asList(d.break_conditions).map(asRecord).map(condition).filter((c): c is StudioCondition => !!c)
        add(node, id, 'loop', {
          loopConfig: {
            mode: 'WHILE',
            collection: '',
            itemAlias: 'item',
            indexAlias: 'index',
            outputAlias: `${id}_results`,
            bodyOutput: 'lastOutput',
            maxIterations: Math.min(100, Math.max(1, Number(d.loop_count) || 10)),
            breakConditions,
            breakLogic: str(d.logical_operator).toLowerCase() === 'or' ? 'OR' : 'AND',
            loopVariables: asList(d.loop_variables).map(asRecord).map((item) => ({
              name: str(item.label) || 'value',
              value: item.value_type === 'variable' ? refPath(item.value) : str(item.value),
            })),
            bodyEntry: entry,
            bodyExit: (exit && ids.get(exit)) || entry,
            bodyNodeIds: bodyIds,
          },
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, `循环（直到满足条件），最多 ${Math.min(100, Number(d.loop_count) || 10)} 轮`, 'review')
        return
      }
      case 'tool': {
        const inputs: Record<string, string> = {}
        for (const [name, spec] of Object.entries(asRecord(d.tool_parameters))) {
          const value = asRecord(spec)
          inputs[name] = value.type === 'variable' ? refPath(value.value) : valueTemplate(value.value)
        }
        const label = `${str(d.provider_name) || str(d.provider_id)} / ${str(d.tool_label) || str(d.tool_name)}`
        add(node, id, 'tool', placeholderTool(`原 Dify 工具「${label}」`, inputs))
        draft.note(node.title, from, `待绑定业务能力（原 Dify 工具 ${label}），入参已保留`, 'todo')
        return
      }
      case 'agent': {
        const params = asRecord(d.agent_parameters)
        const instruction = text(asRecord(params.instruction).value)
        const query = text(asRecord(params.query).value) || '{{ input.message }}'
        add(node, id, 'llm', {
          ...llmData(instruction, query),
          needsConfiguration: true,
          placeholderReason: 'Dify Agent 节点改成了普通 LLM；它原来会调用的工具请改用工具节点或业务能力',
        } as Partial<CanvasNodeData>)
        draft.note(node.title, from, 'Agent 改成了 LLM，原来挂的工具需要另接', 'todo')
        return
      }
      case 'document-extractor':
        add(node, id, 'tool', placeholderTool('原 Dify 文档提取：把上传的文件转成文字', { file: refPath(d.variable_selector) }))
        draft.note(node.title, from, '文档提取暂不支持，留了占位', 'todo')
        return
      default:
        add(node, id, 'tool', placeholderTool(`原 Dify 节点「${node.title}」（${node.type}）`))
        draft.note(node.title, from, `ReachAI 没有对应节点，留了占位`, 'todo')
    }
  }

  function condition(item: Record<string, unknown>): StudioCondition | null {
    const operator = conditionOperator(str(item.comparison_operator))
    const left = refPath(item.variable_selector)
    if (!operator || !left) return null
    const value = item.value
    const out: StudioCondition = { left, operator }
    if (['empty', 'not_empty', 'exists'].includes(operator)) return out
    if (Array.isArray(value)) {
      const head = str(value[0])
      out.right = head === 'sys' || startIds.has(head) || refs.has(head) ? refPath(value) : value.map(str).join(',')
    } else {
      out.right = valueExpr(value)
    }
    return out
  }

  function valueTemplate(value: unknown): string {
    if (Array.isArray(value)) return `{{ ${refPath(value)} }}`
    return text(value)
  }

  // Edges: skip the iteration/loop start markers (they define the body entry), map handles onto routes.
  for (const edge of edges) {
    const source = byId.get(edge.source)
    const from = ids.get(edge.source)
    const to = ids.get(edge.target)
    if (!source || !from || !to) continue
    const handle = edge.sourceHandle
    let condition = 'always'
    if (handle === 'fail-branch') condition = 'error'
    else if (source.type === 'if-else') condition = handle === 'false' ? 'else' : `route:${routeId(handle, 'true')}`
    else if (source.type === 'question-classifier') condition = `route:${routeId(handle, 'class_1')}`
    draft.connect(from, to, condition)
  }
  draft.serializeFanOut()

  const name = str(app.name) || 'Dify 工作流'
  return draft.build({
    name,
    keySlug: slugFrom(name, 'dify-workflow'),
    description: str(app.description),
    source: 'dify',
    sourceLabel: mode === 'advanced-chat' ? 'Dify Chatflow' : 'Dify 工作流',
  })
}

/** Convert Dify's typed fallback outputs into the object shape used by the Runtime node. */
function difyDefaultOutput(node: DifyNode): Record<string, unknown> {
  if (!['code', 'http-request'].includes(node.type) || !Array.isArray(node.data.default_value)) {
    throw new Error(`Dify 节点「${node.title}」的默认值错误处理暂不能安全转换，请先在 Dify 中改为失败分支后再导入。`)
  }
  const entries: Array<[string, unknown]> = []
  const keys = new Set<string>()
  for (const raw of node.data.default_value) {
    const item = asRecord(raw)
    const sourceKey = str(item.key).trim()
    const type = str(item.type).toLowerCase()
    const key = node.type === 'http-request' && sourceKey === 'status_code' ? 'statusCode' : sourceKey
    if (!key || keys.has(key) || !Object.prototype.hasOwnProperty.call(item, 'value')) {
      throw new Error(`Dify 节点「${node.title}」的默认输出不完整或重复，无法安全导入。`)
    }
    keys.add(key)
    let value = item.value
    if (type === 'number' && typeof value === 'string' && value.trim() !== '') value = Number(value)
    if ((type === 'object' || type.startsWith('array[')) && typeof value === 'string') {
      try {
        value = JSON.parse(value)
      } catch {
        throw new Error(`Dify 节点「${node.title}」的默认输出「${sourceKey}」不是有效 JSON。`)
      }
    }
    const arrayElementType = type.match(/^array\[(string|number|object|boolean)\]$/)?.[1]
    const validArray = !!arrayElementType && Array.isArray(value) && value.every((item) => {
      if (arrayElementType === 'object') return item !== null && typeof item === 'object' && !Array.isArray(item)
      if (arrayElementType === 'number') return typeof item === 'number' && Number.isFinite(item)
      return typeof item === arrayElementType
    })
    if (!(type === 'string' && typeof value === 'string'
        || type === 'number' && typeof value === 'number' && Number.isFinite(value)
        || type === 'boolean' && typeof value === 'boolean'
        || type === 'object' && value !== null && typeof value === 'object' && !Array.isArray(value)
        || validArray)) {
      throw new Error(`Dify 节点「${node.title}」的默认输出「${sourceKey}」类型无法安全转换。`)
    }
    entries.push([key, value])
  }
  if (!entries.length) throw new Error(`Dify 节点「${node.title}」没有可转换的默认输出。`)
  return Object.fromEntries(entries)
}

function fieldType(type: string): FieldType {
  switch (type) {
    case 'number': return 'number'
    case 'checkbox':
    case 'bool':
    case 'boolean': return 'boolean'
    case 'file': return 'file'
    case 'file-list':
    case 'array[string]':
    case 'array[number]':
    case 'array[object]':
    case 'array[boolean]': return 'array'
    case 'object': return 'object'
    default: return 'string'
  }
}

function keyValues(raw: string, convert: (value: string) => string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of raw.split('\n')) {
    const at = line.indexOf(':')
    if (at <= 0) continue
    const key = line.slice(0, at).trim()
    if (key) out[key] = convert(line.slice(at + 1).trim())
  }
  return out
}

function coalesceCode(body: string) {
  return `function main(inputs) {
  // 取第一个有值的变量（对应 Dify 变量聚合器）
  function first(keys) {
    for (var i = 0; i < keys.length; i++) {
      var value = inputs[keys[i]]
      if (value !== undefined && value !== null && value !== '') return value
    }
    return null
  }
  return {
${body}
  }
}`
}

function pythonStub(code: string, outputs: string[]) {
  const commented = code.split('\n').map((line) => `  // ${line}`).join('\n')
  return `function main(inputs) {
  // TODO：下面是原来的 Python 代码，请改写成 JavaScript
${commented}
  return {
${outputs.map((name) => `    ${name}: null,`).join('\n')}
  }
}`
}
