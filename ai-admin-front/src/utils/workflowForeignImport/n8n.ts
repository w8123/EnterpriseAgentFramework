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
  slugFrom,
  str,
  type ForeignImportResult,
  type ImportNoteLevel,
} from './shared'

/**
 * Converts an n8n workflow export (JSON from 「Download」 or copied nodes) into a ReachAI draft.
 * n8n passes item lists between nodes and builds values with JavaScript expressions; simple references
 * (`$json.x`, `$('节点').item.json.x`) are rewritten to ReachAI variables, anything else is kept as text and
 * flagged so the author can check it.
 */

interface N8nNode {
  id: string
  name: string
  type: string
  short: string
  version: number
  parameters: Record<string, unknown>
  x: number
  y: number
  disabled: boolean
}

interface SourceEdge {
  from: string
  to: string
  output: number
}

type Role = 'trigger' | 'pass' | 'skip' | 'node'

const PASS_TYPES = new Set(['noOp', 'merge', 'wait', 'splitInBatches', 'stopAndError'])
const SKIP_TYPES = new Set(['stickyNote'])
const LLM_TYPES = new Set(['agent', 'chainLlm', 'openAi', 'lmChatOpenAi', 'chainSummarization', 'chainRetrievalQa'])

export function isN8nWorkflow(value: unknown): boolean {
  const root = asRecord(value)
  const nodes = asList(root.nodes)
  return nodes.length > 0 && !!root.connections && typeof root.connections === 'object'
    && nodes.every((node) => typeof asRecord(node).type === 'string' && typeof asRecord(node).name === 'string')
}

export function convertN8nWorkflow(value: unknown): ForeignImportResult {
  const root = asRecord(value)
  const nodes: N8nNode[] = asList(root.nodes).map(asRecord).map((raw) => {
    const type = str(raw.type)
    const position = asList(raw.position)
    return {
      id: str(raw.id) || str(raw.name),
      name: str(raw.name),
      type,
      short: type.split('.').pop() || type,
      version: Number(raw.typeVersion) || 1,
      parameters: asRecord(raw.parameters),
      x: Number(position[0]) || 0,
      y: Number(position[1]) || 0,
      disabled: raw.disabled === true,
    }
  }).filter((node) => node.name && node.type)
  if (!nodes.length) throw new Error('n8n 文件里没有节点')
  const byName = new Map(nodes.map((node) => [node.name, node]))

  // Main data edges, plus which nodes only feed an AI node (model, memory, tools) rather than the main flow.
  const mainEdges: SourceEdge[] = []
  const aiFeeds = new Map<string, Array<{ from: string; kind: string }>>()
  const subNodes = new Set<string>()
  for (const [from, outputs] of Object.entries(asRecord(root.connections))) {
    for (const [kind, lists] of Object.entries(asRecord(outputs))) {
      asList(lists).forEach((targets, output) => {
        for (const target of asList(targets).map(asRecord)) {
          const to = str(target.node)
          if (!byName.has(to) || !byName.has(from)) continue
          if (kind === 'main') mainEdges.push({ from, to, output })
          else {
            subNodes.add(from)
            aiFeeds.set(to, [...(aiFeeds.get(to) || []), { from, kind }])
          }
        }
      })
    }
  }

  const draft = new DraftGraph()
  const roles = new Map<string, Role>()
  let trigger: N8nNode | null = null
  const extraTriggers: string[] = []
  for (const node of nodes) {
    if (SKIP_TYPES.has(node.short) || subNodes.has(node.name)) roles.set(node.name, 'skip')
    else if (isTrigger(node)) {
      if (!trigger) { trigger = node; roles.set(node.name, 'trigger') }
      else { extraTriggers.push(node.name); roles.set(node.name, 'skip') }
    } else if (PASS_TYPES.has(node.short) || node.disabled) roles.set(node.name, 'pass')
    else roles.set(node.name, 'node')
  }
  const ids = new Map<string, string>()
  for (const node of nodes) {
    const role = roles.get(node.name)
    if (role === 'node' || role === 'trigger') ids.set(node.name, draft.uniqueId(node.name, node.short))
  }

  const inputsOf = (name: string) => mainEdges.filter((edge) => edge.to === name).map((edge) => edge.from)
  const triggerFields = new Map<string, StudioFieldSchema>()
  const flagged = new Set<string>()

  /** Variable path for data coming out of a node (`rest` = path inside its JSON item). */
  function outputPath(name: string, rest: string[], depth = 0): string {
    const node = byName.get(name)
    if (!node || depth > 20) return rest.length ? `lastOutput.${rest.join('.')}` : 'lastOutput'
    const role = roles.get(name)
    const id = ids.get(name) || ''
    const base = `nodeOutput.${id}`
    const tail = (parts: string[]) => (parts.length ? `${base}.${parts.join('.')}` : base)
    if (role === 'trigger') return triggerPath(node, rest)
    if (role === 'pass' || ['if', 'switch', 'filter'].includes(node.short)) {
      const prev = inputsOf(name)[0]
      return prev ? outputPath(prev, rest, depth + 1) : 'lastOutput'
    }
    if (node.short === 'httpRequest') return rest.length ? `${base}.parsedBody.${rest.join('.')}` : `${base}.body`
    if (LLM_TYPES.has(node.short)) return !rest.length || ['output', 'text', 'response', 'message', 'content'].includes(rest[0]) ? base : tail(rest)
    if (['filter', 'sort', 'limit', 'removeDuplicates'].includes(node.short)) return `${base}.items`
    return tail(rest)
  }

  function triggerPath(node: N8nNode, rest: string[]): string {
    let parts = rest
    if (node.short === 'webhook' && ['body', 'query', 'params'].includes(parts[0])) parts = parts.slice(1)
    if (node.short === 'chatTrigger' && (parts[0] === 'chatInput' || !parts.length)) return 'input.message'
    if (!parts.length) return 'params'
    const field = parts[0]
    if (!triggerFields.has(field)) triggerFields.set(field, { name: field, type: 'string', required: false, description: field })
    return `params.${parts.join('.')}`
  }

  /** Rewrites one `{{ ... }}` body; null when it is real JavaScript we cannot translate. */
  function simpleRef(expr: string, current: string): string | null {
    const e = expr.trim()
    const prop = String.raw`((?:\.[A-Za-z_$][\w$]*|\[\s*(?:"[^"]*"|'[^']*'|\d+)\s*\])*)`
    const split = (chain: string) => (chain.match(/\.[A-Za-z_$][\w$]*|\[\s*(?:"[^"]*"|'[^']*'|\d+)\s*\]/g) || [])
      .map((part) => part.startsWith('.') ? part.slice(1) : part.replace(/^\[\s*["']?|["']?\s*\]$/g, ''))
    let match = e.match(new RegExp(`^\\$json${prop}$`))
    if (match) return pathFrom(inputsOf(current)[0], split(match[1]))
    match = e.match(new RegExp(`^\\$input\\.(?:item|first\\(\\)|last\\(\\))\\.json${prop}$`))
    if (match) return pathFrom(inputsOf(current)[0], split(match[1]))
    match = e.match(new RegExp(`^\\$\\(\\s*(?:"([^"]+)"|'([^']+)')\\s*\\)\\.(?:item|first\\(\\)|last\\(\\)|itemMatching\\([^)]*\\))\\.json${prop}$`))
    if (match) return pathFrom(match[1] || match[2], split(match[3]))
    match = e.match(new RegExp(`^\\$node\\[\\s*(?:"([^"]+)"|'([^']+)')\\s*\\]\\.json${prop}$`))
    if (match) return pathFrom(match[1] || match[2], split(match[3]))
    match = e.match(/^\$(?:env|vars)\.([A-Za-z_][\w]*)$/)
    if (match) return `env.${match[1]}`
    if (e === '$now') return 'sys.now'
    if (e === '$today') return 'sys.today'
    if (e === '$execution.id') return 'sys.runId'
    return null
  }

  function pathFrom(name: string | undefined, rest: string[]) {
    return name ? outputPath(name, rest) : (rest.length ? `lastOutput.${rest.join('.')}` : 'lastOutput')
  }

  /** n8n parameter value → ReachAI template text (`=…{{ }}…` expressions rewritten). */
  function text(value: unknown, current: string): string {
    if (value === undefined || value === null) return ''
    if (typeof value !== 'string') return typeof value === 'object' ? JSON.stringify(value) : String(value)
    if (!value.startsWith('=')) return value
    return value.slice(1).replace(/\{\{([\s\S]+?)\}\}/g, (all, inner: string) => {
      const path = simpleRef(inner, current)
      if (path) return `{{ ${path} }}`
      flagged.add(current)
      return all
    })
  }

  /** A value that is exactly one reference becomes a bare path (for conditions and mappings). */
  function expr(value: unknown, current: string): string {
    const converted = text(value, current).trim()
    const single = converted.match(/^\{\{\s*([A-Za-z_][\w.]*)\s*\}\}$/)
    return single ? single[1] : converted
  }

  function add(node: N8nNode, kind: CanvasNodeKind, data: Partial<CanvasNodeData>) {
    const id = ids.get(node.name)
    if (!id) return
    const notes = str(asRecord(node.parameters.options).notes)
    draft.add({ id, kind, label: node.name, x: node.x, y: node.y, data: { ...(notes ? { description: notes } : {}), ...data } })
  }

  function note(node: N8nNode, result: string, level: ImportNoteLevel = 'ok') {
    draft.note(node.name, `n8n ${node.short}`, result, level)
  }

  for (const node of nodes) {
    const role = roles.get(node.name)
    if (role === 'trigger') convertTrigger(node)
    else if (role === 'node') convert(node)
  }

  function convertTrigger(node: N8nNode) {
    const p = node.parameters
    if (node.short === 'formTrigger') {
      for (const item of asList(asRecord(p.formFields).values).map(asRecord)) {
        const name = str(item.fieldLabel) || 'value'
        triggerFields.set(name, {
          name,
          type: str(item.fieldType) === 'number' ? 'number' : 'string',
          required: item.requiredField === true,
          description: name,
        })
      }
    }
    add(node, 'userInput', { userInputConfig: { outputAlias: 'params', fields: [] } } as Partial<CanvasNodeData>)
    const generic = ['manualTrigger', 'webhook', 'formTrigger', 'chatTrigger', 'executeWorkflowTrigger', 'start'].includes(node.short)
    note(node, generic ? '开始输入' : `开始输入（原来由 ${node.short.replace(/Trigger$/, '')} 事件触发，现在改成手动、定时或接口触发）`, generic ? 'ok' : 'review')
  }

  function convert(node: N8nNode) {
    const p = node.parameters
    const name = node.name
    switch (node.short) {
      case 'if': {
        const { conditions, total, logic } = ifConditions(p, name)
        const groups: StudioConditionGroup[] = [{ id: 'true', label: '是', logic, conditions }]
        add(node, 'condition', { conditionConfig: { groups, defaultRoute: 'else' } } as Partial<CanvasNodeData>)
        note(node, `条件分支${conditions.length < total ? `，有 ${total - conditions.length} 个条件没转过来` : ''}`, conditions.length < total ? 'review' : 'ok')
        return
      }
      case 'switch': {
        const rules = asList(asRecord(p.rules).values ?? asRecord(p.rules).rules).map(asRecord)
        let total = 0
        const groups: StudioConditionGroup[] = rules.map((rule, index) => {
          if (rule.conditions) {
            const parsed = ifConditions({ conditions: rule.conditions }, name)
            total += parsed.total
            return { id: `case_${index}`, label: str(rule.outputKey) || `分支 ${index + 1}`, logic: parsed.logic, conditions: parsed.conditions }
          }
          total += 1
          const operator = conditionOperator(str(rule.operation))
          const left = expr(p.value1, name)
          return {
            id: `case_${index}`,
            label: `分支 ${index + 1}`,
            logic: 'AND',
            conditions: operator && left ? [{ left, operator, right: expr(rule.value2 ?? rule.value, name) }] : [],
          }
        })
        add(node, 'condition', { conditionConfig: { groups, defaultRoute: 'else' } } as Partial<CanvasNodeData>)
        const kept = groups.reduce((sum, group) => sum + group.conditions.length, 0)
        note(node, `多路分支，${groups.length} 个分支${kept < total || str(p.mode) === 'expression' ? '，部分规则需要手动补' : ''}`, kept < total || str(p.mode) === 'expression' ? 'review' : 'ok')
        return
      }
      case 'httpRequest': {
        const legacy = node.version < 3
        const method = (str(p.method) || str(p.requestMethod) || 'GET').toUpperCase()
        const pairs = (list: unknown) => Object.fromEntries(asList(list).map(asRecord)
          .filter((item) => str(item.name)).map((item) => [str(item.name), text(item.value, name)]))
        const query = legacy ? pairs(asRecord(p.queryParametersUi).parameter) : p.sendQuery ? pairs(asRecord(p.queryParameters).parameters) : {}
        const headers = legacy ? pairs(asRecord(p.headerParametersUi).parameter) : p.sendHeaders ? pairs(asRecord(p.headerParameters).parameters) : {}
        let body = ''
        let bodyType: 'none' | 'json' | 'text' = 'none'
        if (legacy) {
          if (p.jsonParameters && p.bodyParametersJson) { bodyType = 'json'; body = text(p.bodyParametersJson, name) }
          else if (asList(asRecord(p.bodyParametersUi).parameter).length) { bodyType = 'json'; body = JSON.stringify(pairs(asRecord(p.bodyParametersUi).parameter), null, 2) }
        } else if (p.sendBody) {
          const contentType = str(p.contentType) || 'json'
          bodyType = contentType === 'raw' ? 'text' : 'json'
          if (str(p.specifyBody) === 'json') body = text(p.jsonBody, name)
          else if (contentType === 'raw') body = text(p.body, name)
          else body = JSON.stringify(pairs(asRecord(p.bodyParameters).parameters), null, 2)
        }
        add(node, 'http', {
          httpConfig: {
            method,
            url: text(p.url, name),
            queryParams: query,
            headers,
            bodyType,
            body,
            timeoutMs: Math.min(600000, Math.max(1000, Number(asRecord(p.options).timeout) || 30000)),
            credentialRef: '',
          },
        } as Partial<CanvasNodeData>)
        const auth = str(p.authentication)
        const notes: string[] = []
        if (auth && auth !== 'none') notes.push('原来带了鉴权，请在节点里选择凭据（凭据没有导入）')
        if (['form-urlencoded', 'multipart-form-data', 'binaryData'].includes(str(p.contentType))) notes.push('表单 / 文件请求体改成了 JSON')
        note(node, `HTTP 请求${notes.length ? `（${notes.join('；')}）` : ''}`, notes.length ? 'review' : 'ok')
        return
      }
      case 'set': {
        const outputs: Record<string, string> = {}
        const assignments = asList(asRecord(p.assignments).assignments).map(asRecord)
        for (const item of assignments) if (str(item.name)) outputs[str(item.name)] = expr(item.value, name)
        for (const item of asList(asRecord(p.fields).values).map(asRecord)) {
          const valueKey = Object.keys(item).find((key) => key.endsWith('Value'))
          if (str(item.name)) outputs[str(item.name)] = expr(valueKey ? item[valueKey] : item.value, name)
        }
        for (const list of Object.values(asRecord(p.values))) {
          for (const item of asList(list).map(asRecord)) if (str(item.name)) outputs[str(item.name)] = expr(item.value, name)
        }
        if (!Object.keys(outputs).length) outputs.value = 'lastOutput'
        add(node, 'code', { codeConfig: { language: 'expression', outputs } } as Partial<CanvasNodeData>)
        note(node, `设置字段（${Object.keys(outputs).length} 个）`, 'ok')
        return
      }
      case 'code':
      case 'function':
      case 'functionItem': {
        const python = str(p.language) === 'python' || str(p.language) === 'pythonNative'
        const source = str(p.jsCode) || str(p.functionCode) || str(p.pythonCode)
        const prev = inputsOf(name)[0]
        add(node, 'code', {
          codeConfig: {
            language: 'javascript',
            code: codeStub(source, python),
            inputs: { items: prev ? outputPath(prev, []) : 'lastOutput' },
            outputs: { result: 'object' },
          },
          needsConfiguration: true,
          placeholderReason: 'n8n 代码按条目（$input）处理数据，写法不同，请改写成 main(inputs)',
        } as Partial<CanvasNodeData>)
        note(node, `代码需要改写（原 ${python ? 'Python' : 'JavaScript'} 放在注释里，n8n 的 $input 写法不通用）`, 'todo')
        return
      }
      case 'agent':
      case 'chainLlm':
      case 'openAi':
      case 'chainSummarization':
      case 'chainRetrievalQa': {
        const options = asRecord(p.options)
        let system = text(options.systemMessage, name)
        let user = str(p.promptType) === 'define' || p.text ? text(p.text, name) : ''
        const messages = asList(asRecord(p.messages).values ?? asRecord(p.messages).messageValues).map(asRecord)
        for (const message of messages) {
          const content = text(message.content ?? message.message, name)
          const role = str(message.role) || (str(message.type).startsWith('System') ? 'system' : 'user')
          if (role === 'system') system = [system, content].filter(Boolean).join('\n\n')
          else user = [user, content].filter(Boolean).join('\n\n')
        }
        if (!user) user = '{{ input.message }}'
        const feeds = aiFeeds.get(name) || []
        const tools = feeds.filter((feed) => feed.kind === 'ai_tool').map((feed) => feed.from)
        const memory = feeds.some((feed) => feed.kind === 'ai_memory')
        const retriever = feeds.some((feed) => ['ai_retriever', 'ai_vectorStore'].includes(feed.kind))
        add(node, 'llm', {
          ...llmData(system, user, { json: p.hasOutputParser === true }),
          ...(tools.length ? { needsConfiguration: true, placeholderReason: `原来挂了工具：${tools.join('、')}，请改用工具节点或业务能力` } : {}),
        } as Partial<CanvasNodeData>)
        const extras: string[] = ['现用工作流默认模型']
        if (tools.length) extras.push(`原来挂的工具（${tools.join('、')}）需要另接`)
        if (memory) extras.push('对话记忆没有带过来')
        if (retriever) extras.push('向量检索请改用知识检索节点')
        note(node, `LLM（${extras.join('；')}）`, tools.length || retriever ? 'todo' : 'ok')
        return
      }
      case 'textClassifier':
      case 'sentimentAnalysis': {
        const categories = node.short === 'sentimentAnalysis'
          ? (str(asRecord(p.options).categories) || 'Positive, Neutral, Negative').split(',').map((item) => ({ category: item.trim(), description: '' }))
          : asList(asRecord(p.categories).categories).map(asRecord).map((item) => ({ category: str(item.category), description: str(item.description) }))
        const classes = categories.map((item, index) => ({ id: `class_${index}`, label: item.category || `分类 ${index + 1}`, description: item.description || item.category, keywords: [] }))
        add(node, 'classifier', {
          classifierConfig: {
            inputExpression: expr(p.inputText, name) || 'input.message',
            strategy: 'LLM',
            classes,
            defaultRoute: classes[classes.length - 1]?.id || 'else',
            confidenceThreshold: 0.5,
            llmPrompt: text(asRecord(p.options).systemPromptTemplate, name),
            modelInstanceId: '',
            modelParams: {},
          },
        } as Partial<CanvasNodeData>)
        note(node, `分类（AI 判断），${classes.length} 类`, 'ok')
        return
      }
      case 'informationExtractor': {
        const fields: StudioFieldSchema[] = asList(asRecord(p.attributes).attributes).map(asRecord).map((item) => ({
          name: str(item.name) || 'value',
          type: fieldType(str(item.type)),
          required: item.required === true,
          description: str(item.description),
        }))
        add(node, 'parameter', {
          parameterConfig: {
            mode: 'llm',
            inputExpression: expr(p.text, name) || 'input.message',
            systemPrompt: text(asRecord(p.options).systemPromptTemplate, name),
            userPrompt: '',
            modelParams: {},
            fields,
          },
        } as Partial<CanvasNodeData>)
        note(node, `信息提取（AI），${fields.length} 个字段`, 'ok')
        return
      }
      case 'respondToWebhook': {
        const respondWith = str(p.respondWith) || 'firstIncomingItem'
        const prev = inputsOf(name)[0]
        const template = ['text', 'json'].includes(respondWith) && p.responseBody !== undefined
          ? text(p.responseBody, name)
          : `{{ ${prev ? outputPath(prev, []) : 'lastOutput'} }}`
        add(node, 'answer', answerData(template))
        note(node, '回复', 'ok')
        return
      }
      case 'filter':
      case 'sort':
      case 'limit':
      case 'removeDuplicates': {
        const prev = inputsOf(name)[0]
        const operations: ListOperation[] = []
        if (node.short === 'filter') {
          const parsed = ifConditions(p, name, true)
          if (parsed.conditions.length) operations.push({ type: 'filter', logic: parsed.logic, conditions: parsed.conditions })
        } else if (node.short === 'sort') {
          const field = asList(asRecord(p.sortFieldsUi).sortField).map(asRecord)[0]
          operations.push({ type: 'sort', field: str(field?.fieldName) || undefined, order: str(field?.order) === 'descending' ? 'desc' : 'asc' })
        } else if (node.short === 'limit') {
          operations.push({ type: 'limit', count: Number(p.maxItems) || 1 })
        } else {
          operations.push({ type: 'unique' })
        }
        add(node, 'listOperator', { listConfig: { source: prev ? outputPath(prev, []) : 'lastOutput', operations } } as Partial<CanvasNodeData>)
        note(node, '列表操作（n8n 是逐条处理，这里按整个列表处理，请确认数据来源是列表）', 'review')
        return
      }
      case 'executeWorkflow':
        add(node, 'subWorkflow', {
          subWorkflowConfig: { workflowId: '', inputs: {} },
          needsConfiguration: true,
          placeholderReason: '请选择要调用的子流程（原来调用 n8n 的另一个工作流）',
        } as Partial<CanvasNodeData>)
        note(node, '子流程，需要选择 ReachAI 里的工作流', 'todo')
        return
      default: {
        const inputs: Record<string, string> = {}
        for (const [key, raw] of Object.entries(p)) {
          if (Object.keys(inputs).length >= 12) break
          if (typeof raw === 'string' && raw && !['resource', 'operation', 'authentication'].includes(key)) inputs[key] = expr(raw, name)
        }
        const action = [str(p.resource), str(p.operation)].filter(Boolean).join(' / ')
        const label = `${node.short}${action ? `：${action}` : ''}`
        add(node, 'tool', placeholderTool(`原 n8n 节点「${name}」（${label}）`, inputs))
        note(node, `待绑定业务能力（原 n8n ${label}），参数已保留`, 'todo')
      }
    }
  }

  function ifConditions(p: Record<string, unknown>, current: string, filter = false) {
    const block = asRecord(p.conditions)
    const conditions: StudioCondition[] = []
    let total = 0
    let logic: 'AND' | 'OR' = 'AND'
    if (Array.isArray(block.conditions)) {
      logic = str(block.combinator).toLowerCase() === 'or' ? 'OR' : 'AND'
      for (const item of asList(block.conditions).map(asRecord)) {
        total++
        const op = asRecord(item.operator)
        const operation = str(op.operation)
        const operator = conditionOperator(operation)
        let left = expr(item.leftValue, current)
        if (filter) left = left.replace(/^nodeOutput\.[^.]+\.items\b|^lastOutput\b/, 'item')
        if (!operator || !left) continue
        const condition: StudioCondition = { left, operator }
        if (operation === 'true' || operation === 'false') condition.right = operation
        else if (!op.singleValue && !['empty', 'not_empty', 'exists'].includes(operator)) condition.right = expr(item.rightValue, current)
        conditions.push(condition)
      }
    } else {
      logic = str(p.combineOperation) === 'any' ? 'OR' : 'AND'
      for (const list of Object.values(block)) {
        for (const item of asList(list).map(asRecord)) {
          total++
          const operator = conditionOperator(legacyOperation(str(item.operation)))
          const left = expr(item.value1, current)
          if (!operator || !left) continue
          conditions.push({ left, operator, ...(item.value2 !== undefined ? { right: expr(item.value2, current) } : {}) })
        }
      }
    }
    return { conditions, total, logic }
  }

  // Edges between kept nodes; pass-through nodes are bridged, routes come from the output index.
  function targets(name: string, seen = new Set<string>()): string[] {
    if (seen.has(name)) return []
    seen.add(name)
    const role = roles.get(name)
    if (role === 'node' || role === 'trigger') return [name]
    if (role === 'pass') return mainEdges.filter((edge) => edge.from === name).flatMap((edge) => targets(edge.to, seen))
    return []
  }
  for (const edge of mainEdges) {
    const source = byName.get(edge.from)
    const from = ids.get(edge.from)
    if (!source || !from) continue
    let condition = 'always'
    if (source.short === 'if') condition = edge.output === 0 ? 'route:true' : 'else'
    else if (source.short === 'switch') {
      const rules = asList(asRecord(source.parameters.rules).values ?? asRecord(source.parameters.rules).rules)
      condition = edge.output < rules.length ? `route:case_${edge.output}` : 'else'
    } else if (source.short === 'textClassifier' || source.short === 'sentimentAnalysis') condition = `route:class_${edge.output}`
    else if (edge.output > 0) condition = 'error'
    for (const target of targets(edge.to)) {
      const to = ids.get(target)
      if (to) draft.connect(from, to, condition)
    }
  }
  draft.breakCycles()
  draft.serializeFanOut()

  // Trigger fields are discovered while rewriting references.
  const triggerNode = trigger ? draft.nodes.find((node) => node.id === ids.get((trigger as N8nNode).name)) : null
  if (triggerNode) {
    const fields = [...triggerFields.values()].map((field) => ({ ...field, source: `input.${field.name}` }))
    triggerNode.data = { ...triggerNode.data, userInputConfig: { outputAlias: 'params', fields } } as Partial<CanvasNodeData>
  }
  for (const node of draft.notes) {
    const source = byName.get(node.node)
    if (source && flagged.has(source.name) && node.level === 'ok') {
      node.level = 'review'
      node.result += '（有 n8n 表达式没能自动改写，请检查 {{ }} 里的内容）'
    }
  }
  const passed = nodes.filter((node) => roles.get(node.name) === 'pass')
  if (passed.length) draft.remarks.push(`这些节点没有单独转换，数据直接往下传：${passed.map((node) => `${node.name}${node.disabled ? '（已停用）' : ''}`).join('、')}。`)
  if (extraTriggers.length) draft.remarks.push(`工作流里有多个触发器，只用了「${trigger?.name}」，其余（${extraTriggers.join('、')}）没有导入。`)
  if (!trigger) draft.remarks.push('原工作流没有触发器，已从第一个节点开始。')

  const workflowName = str(root.name) || 'n8n 工作流'
  return draft.build({
    name: workflowName,
    keySlug: slugFrom(workflowName, 'n8n-workflow'),
    description: '',
    source: 'n8n',
    sourceLabel: 'n8n 工作流',
  })
}

function isTrigger(node: N8nNode) {
  return /trigger$/i.test(node.short) || ['webhook', 'start', 'cron', 'interval'].includes(node.short)
}

function legacyOperation(operation: string) {
  const table: Record<string, string> = {
    equal: 'equals', notEqual: 'not_equals', smaller: 'lt', smallerEqual: 'lte', larger: 'gt', largerEqual: 'gte',
    contains: 'contains', notContains: 'not_contains', startsWith: 'starts_with', endsWith: 'ends_with',
    isEmpty: 'empty', isNotEmpty: 'not_empty', regex: 'regex',
  }
  return table[operation] || operation
}

function fieldType(type: string): FieldType {
  if (type === 'number') return 'number'
  if (type === 'boolean') return 'boolean'
  if (type === 'array') return 'array'
  if (type === 'object') return 'object'
  return 'string'
}

function codeStub(source: string, python: boolean) {
  const commented = source.split('\n').map((line) => `  // ${line}`).join('\n')
  return `function main(inputs) {
  // TODO：下面是原来的 n8n ${python ? 'Python' : 'JavaScript'} 代码，请改写。
  // n8n 用 $input.all() 拿条目，这里直接用 inputs.items（上一步的输出）。
${commented}
  return { result: inputs.items }
}`
}
