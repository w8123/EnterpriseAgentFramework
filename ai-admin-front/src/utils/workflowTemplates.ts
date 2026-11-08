import type { AgentForm } from '@/types/agent'
import type { CanvasEdge, CanvasNode, CanvasNodeData, CanvasNodeKind, CanvasSnapshot } from '@/types/studio'
import { canvasToDefinition, createDefaultNodeData } from '@/utils/studio'
import { defaultLlmMessages } from '@/utils/studio/defaults'

/**
 * Built-in enterprise workflow templates. They ship with the front end (no table) and are built through the
 * same canvas → GraphSpec conversion the Studio uses on save, so a template is always a valid draft.
 * Business-system steps are TOOL nodes left unbound: after creating, the author binds their own capability.
 */
export interface WorkflowTemplate {
  key: string
  name: string
  description: string
  /** What the author still has to do after creating from the template. */
  setupHint: string
  tags: string[]
  build: () => TemplateCanvas
}

interface TemplateNode {
  id: string
  kind: CanvasNodeKind
  label: string
  x: number
  y: number
  data?: Partial<CanvasNodeData>
}

interface TemplateEdge {
  from: string
  to: string
  /** Branch id for condition / classifier / approval / knowledge routes; omitted means always. */
  route?: string
}

interface TemplateCanvas {
  nodes: TemplateNode[]
  edges: TemplateEdge[]
}

const TEMPLATE_BASE: AgentForm = {
  keySlug: 'template',
  name: 'template',
  description: '',
  intentType: '',
  systemPrompt: '',
  tools: [],
  modelInstanceId: '',
  maxSteps: 10,
  type: 'workflow',
  enabled: true,
  extra: {},
} as unknown as AgentForm

function llm(systemPrompt: string, userPrompt: string): Partial<CanvasNodeData> {
  return {
    llmConfig: {
      systemPrompt,
      userPrompt,
      messages: defaultLlmMessages(systemPrompt, userPrompt),
      outputFormat: 'text',
      modelParams: {},
    },
  } as Partial<CanvasNodeData>
}

function unboundTool(note: string): Partial<CanvasNodeData> {
  return {
    description: `模板占位：请在右侧选择你自己的业务能力。${note}`,
    needsConfiguration: true,
    placeholderReason: `请绑定业务能力：${note}`,
    toolConfig: { inputMapping: {}, mappingNote: note },
  } as Partial<CanvasNodeData>
}

function answer(template: string): Partial<CanvasNodeData> {
  return { answerConfig: { template } } as Partial<CanvasNodeData>
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  {
    key: 'inventory-alert',
    name: '库存预警',
    description: '查询库存，挑出低于阈值的商品，有就让 AI 写一段预警，没有就回复库存正常。',
    setupHint: '把「查询库存」绑定到你的库存查询能力，并确认列表字段名（默认 item.quantity）。',
    tags: ['定时任务', '列表处理', '条件分支'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '开始输入', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'threshold', type: 'number', required: false, source: 'params.threshold', defaultValue: '10', description: '低于这个数量就预警' },
          ] } } as Partial<CanvasNodeData>,
        },
        { id: 'query_stock', kind: 'tool', label: '查询库存', x: 500, y: 220, data: unboundTool('返回商品列表，每项包含 name 和 quantity。') },
        {
          id: 'pick_low', kind: 'listOperator', label: '挑出低库存', x: 740, y: 220,
          data: {
            outputAlias: 'low_stock',
            listConfig: { source: 'nodeOutput.query_stock', operations: [
              { type: 'filter', logic: 'AND', conditions: [{ left: 'item.quantity', operator: 'lt', right: 'params.threshold' }] },
              { type: 'sort', field: 'quantity', order: 'asc' },
            ] },
          } as Partial<CanvasNodeData>,
        },
        {
          id: 'has_low', kind: 'condition', label: '有低库存吗', x: 980, y: 220,
          data: { conditionConfig: { defaultRoute: 'else', groups: [
            { id: 'has_low', label: '有', logic: 'AND', conditions: [{ left: 'var.low_stock.count', operator: 'gt', right: '0' }] },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'write_alert', kind: 'llm', label: '写预警', x: 1220, y: 120,
          data: llm('你是仓库助理，用简短的中文列出需要补货的商品和数量。', '低库存商品：{{ var.low_stock.items }}'),
        },
        { id: 'alert_answer', kind: 'answer', label: '回复预警', x: 1460, y: 120, data: answer('{{ lastOutput }}') },
        { id: 'ok_answer', kind: 'answer', label: '库存正常', x: 1220, y: 340, data: answer('库存都正常，没有低于阈值的商品。') },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'query_stock' },
        { from: 'query_stock', to: 'pick_low' },
        { from: 'pick_low', to: 'has_low' },
        { from: 'has_low', to: 'write_alert', route: 'has_low' },
        { from: 'has_low', to: 'ok_answer', route: 'else' },
        { from: 'write_alert', to: 'alert_answer' },
        { from: 'alert_answer', to: 'end' },
        { from: 'ok_answer', to: 'end' },
      ],
    }),
  },
  {
    key: 'create-customer-approval',
    name: '新建客户（带确认）',
    description: '收集客户信息，先让运行的人确认，确认后才在业务系统里新建客户。',
    setupHint: '把「新建客户」绑定到你的新建客户能力，并把输入映射到 params.name / params.phone / params.level。',
    tags: ['写操作', '人工确认'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '客户信息', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'name', type: 'string', required: true, description: '客户名称' },
            { name: 'phone', type: 'string', required: false, description: '联系电话' },
            {
              name: 'level', type: 'string', required: false, description: '客户等级', defaultValue: '普通',
              options: [{ label: '普通', value: '普通' }, { label: '重要', value: '重要' }, { label: 'VIP', value: 'VIP' }],
            },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'confirm', kind: 'approval', label: '确认新建', x: 500, y: 220,
          data: { approvalConfig: {
            title: '确认新建客户',
            prompt: '将新建客户：{{ params.name }}，电话 {{ params.phone }}，等级 {{ params.level }}。确认吗？',
            approvers: [],
            timeoutSeconds: 3600,
          } } as Partial<CanvasNodeData>,
        },
        { id: 'create_customer', kind: 'tool', label: '新建客户', x: 740, y: 120, data: unboundTool('新建客户，入参 name、phone、level。') },
        { id: 'done_answer', kind: 'answer', label: '回复结果', x: 980, y: 120, data: answer('客户 {{ params.name }} 已新建。') },
        { id: 'cancel_answer', kind: 'answer', label: '已取消', x: 740, y: 340, data: answer('已取消，没有新建客户。') },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'confirm' },
        { from: 'confirm', to: 'create_customer', route: 'approved' },
        { from: 'confirm', to: 'cancel_answer', route: 'rejected' },
        { from: 'create_customer', to: 'done_answer' },
        { from: 'done_answer', to: 'end' },
        { from: 'cancel_answer', to: 'end' },
      ],
    }),
  },
  {
    key: 'knowledge-qa',
    name: '知识问答',
    description: '先查知识库，找到资料就让 AI 依据资料回答；找不到就直接说明，不编造。',
    setupHint: '在「查知识库」里选择你的知识库。',
    tags: ['知识库'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '提问', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'question', type: 'string', required: true, description: '要问的问题' },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'search', kind: 'knowledge', label: '查知识库', x: 500, y: 220,
          data: { knowledgeConfig: { knowledgeBaseCodes: [], query: '{{ params.question }}', evidencePolicy: 'REQUIRED' } } as Partial<CanvasNodeData>,
        },
        {
          id: 'reply', kind: 'llm', label: '依据资料回答', x: 740, y: 120,
          data: llm('只根据提供的资料回答，资料里没有的就说不知道。回答简洁，用中文。', '问题：{{ params.question }}\n资料：{{ lastOutput }}'),
        },
        { id: 'reply_answer', kind: 'answer', label: '回复', x: 980, y: 120, data: answer('{{ lastOutput }}') },
        { id: 'no_answer', kind: 'answer', label: '没找到', x: 740, y: 340, data: answer('知识库里没有找到相关资料，换个问法试试，或联系负责人补充资料。') },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'search' },
        { from: 'search', to: 'reply', route: 'evidence' },
        { from: 'search', to: 'no_answer', route: 'no_evidence' },
        { from: 'reply', to: 'reply_answer' },
        { from: 'reply_answer', to: 'end' },
        { from: 'no_answer', to: 'end' },
      ],
    }),
  },
  {
    key: 'batch-documents',
    name: '批量处理单据',
    description: '传入一批单据，逐张调用业务能力处理（两张同时跑），失败的跳过，最后汇总结果。',
    setupHint: '把「处理单据」绑定到你的业务能力，入参用 var.item（当前这张单据）。',
    tags: ['迭代', '批量'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '单据列表', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'documents', type: 'array', required: true, description: '要处理的单据，JSON 数组' },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'each', kind: 'loop', label: '逐张处理', x: 500, y: 220,
          data: { loopConfig: {
            mode: 'FOREACH',
            collection: 'params.documents',
            itemAlias: 'item',
            indexAlias: 'index',
            outputAlias: 'processed',
            bodyOutput: 'lastOutput',
            maxIterations: 100,
            parallelism: 2,
            errorMode: 'SKIP',
            bodyEntry: 'handle_document',
            bodyExit: 'handle_document',
            bodyNodeIds: ['handle_document'],
          } } as Partial<CanvasNodeData>,
        },
        { id: 'handle_document', kind: 'tool', label: '处理单据', x: 500, y: 400, data: unboundTool('处理一张单据，入参来自 var.item。') },
        {
          id: 'count_done', kind: 'listOperator', label: '统计结果', x: 760, y: 220,
          data: { outputAlias: 'done', listConfig: { source: 'var.processed', operations: [] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'summary', kind: 'answer', label: '汇总', x: 1000, y: 220,
          data: answer('处理完成，成功 {{ var.done.count }} 张，处理失败的已跳过。'),
        },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'each' },
        { from: 'each', to: 'count_done' },
        { from: 'count_done', to: 'summary' },
        { from: 'summary', to: 'end' },
      ],
    }),
  },
  {
    key: 'parallel-lookup',
    name: '多系统并行查询汇总',
    description: '同时查订单系统和工单系统，等两边都返回后合并，让 AI 写一段客户概况。',
    setupHint: '把「查订单」「查工单」分别绑定到对应系统的查询能力。',
    tags: ['并行', '汇总'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '客户', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'customer', type: 'string', required: true, description: '客户名称或编号' },
          ] } } as Partial<CanvasNodeData>,
        },
        { id: 'fan_out', kind: 'parallel', label: '同时查询', x: 500, y: 220, data: { parallelConfig: { maxConcurrency: 2, failureMode: 'CONTINUE' } } as Partial<CanvasNodeData> },
        { id: 'orders', kind: 'tool', label: '查订单', x: 740, y: 120, data: { ...unboundTool('按 params.customer 查最近订单。'), outputAlias: 'orders' } },
        { id: 'tickets', kind: 'tool', label: '查工单', x: 740, y: 340, data: { ...unboundTool('按 params.customer 查未关闭工单。'), outputAlias: 'tickets' } },
        {
          id: 'merge', kind: 'aggregate', label: '合并结果', x: 980, y: 220,
          data: { outputAlias: 'customer_data', aggregateConfig: { mode: 'object', template: '', items: [
            { name: 'orders', source: 'nodeOutput.orders' },
            { name: 'tickets', source: 'nodeOutput.tickets' },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'brief', kind: 'llm', label: '写客户概况', x: 1220, y: 220,
          data: llm('你是客户经理助理，用三五句中文概括客户最近的订单和问题。', '客户：{{ params.customer }}\n数据：{{ var.customer_data }}'),
        },
        { id: 'brief_answer', kind: 'answer', label: '回复', x: 1460, y: 220, data: answer('{{ lastOutput }}') },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'fan_out' },
        { from: 'fan_out', to: 'orders' },
        { from: 'fan_out', to: 'tickets' },
        { from: 'orders', to: 'merge' },
        { from: 'tickets', to: 'merge' },
        { from: 'merge', to: 'brief' },
        { from: 'brief', to: 'brief_answer' },
        { from: 'brief_answer', to: 'end' },
      ],
    }),
  },
  {
    key: 'ticket-routing',
    name: '工单分类分派',
    description: '按关键词判断工单类型：账务类转财务，技术类在系统里建工单，其他的让 AI 先回复。',
    setupHint: '把「创建技术工单」绑定到你的工单系统，按需要调整分类关键词。',
    tags: ['分类', '分派'],
    build: () => ({
      nodes: [
        {
          id: 'input', kind: 'userInput', label: '工单内容', x: 260, y: 220,
          data: { userInputConfig: { outputAlias: 'params', fields: [
            { name: 'content', type: 'string', required: true, description: '用户反馈的问题' },
          ] } } as Partial<CanvasNodeData>,
        },
        {
          id: 'classify', kind: 'classifier', label: '判断类型', x: 500, y: 220,
          data: { classifierConfig: {
            inputExpression: 'params.content',
            strategy: 'KEYWORD',
            defaultRoute: 'else',
            confidenceThreshold: 0.7,
            llmPrompt: '',
            modelInstanceId: '',
            modelParams: {},
            classes: [
              { id: 'billing', label: '账务', keywords: ['发票', '付款', '退款', '账单'] },
              { id: 'tech', label: '技术', keywords: ['报错', '打不开', '登录', '无法'] },
            ],
          } } as Partial<CanvasNodeData>,
        },
        { id: 'billing_answer', kind: 'answer', label: '转财务', x: 760, y: 80, data: answer('已转给财务同事处理，会尽快联系你。') },
        { id: 'create_ticket', kind: 'tool', label: '创建技术工单', x: 760, y: 220, data: unboundTool('创建工单，内容用 params.content。') },
        { id: 'tech_answer', kind: 'answer', label: '已建工单', x: 1000, y: 220, data: answer('已为你创建技术工单，技术同事会跟进。') },
        {
          id: 'general_reply', kind: 'llm', label: '先回复', x: 760, y: 380,
          data: llm('你是客服，礼貌地用中文简短回复用户，并说明会有同事跟进。', '{{ params.content }}'),
        },
        { id: 'general_answer', kind: 'answer', label: '回复', x: 1000, y: 380, data: answer('{{ lastOutput }}') },
      ],
      edges: [
        { from: 'start', to: 'input' },
        { from: 'input', to: 'classify' },
        { from: 'classify', to: 'billing_answer', route: 'billing' },
        { from: 'classify', to: 'create_ticket', route: 'tech' },
        { from: 'classify', to: 'general_reply', route: 'else' },
        { from: 'create_ticket', to: 'tech_answer' },
        { from: 'general_reply', to: 'general_answer' },
        { from: 'billing_answer', to: 'end' },
        { from: 'tech_answer', to: 'end' },
        { from: 'general_answer', to: 'end' },
      ],
    }),
  },
]

function edgeCondition(route?: string) {
  if (!route) return 'always'
  return route === 'else' ? 'else' : `route:${route}`
}

/** Builds the Studio canvas for a template, with start/end boundary nodes. */
export function templateCanvas(template: WorkflowTemplate): CanvasSnapshot {
  const source = template.build()
  const lastX = Math.max(...source.nodes.map((node) => node.x), 260)
  const nodes: CanvasNode[] = [
    { id: 'start', type: 'start', position: { x: 40, y: 220 }, data: { label: '开始', kind: 'start', configVersion: 2 } },
    ...source.nodes.map((node) => ({
      id: node.id,
      type: node.kind,
      position: { x: node.x, y: node.y },
      data: { ...createDefaultNodeData(node.kind, node.label, TEMPLATE_BASE), ...node.data, label: node.label } as CanvasNodeData,
    })),
    { id: 'end', type: 'end', position: { x: lastX + 260, y: 220 }, data: { label: '结束', kind: 'end', configVersion: 2 } },
  ]
  const edges: CanvasEdge[] = source.edges.map((edge, index) => {
    const condition = edgeCondition(edge.route)
    return {
      id: `tpl-e-${index}-${edge.from}-${edge.to}`,
      source: edge.from,
      target: edge.to,
      condition,
      label: condition,
      ...(edge.route ? { sourceHandle: edge.route } : {}),
    } as CanvasEdge
  })
  return { version: 2, nodes, edges }
}

/** GraphSpec + canvas layout JSON ready for createWorkflow. */
export function templateDocuments(template: WorkflowTemplate, modelInstanceId?: string) {
  const base = { ...TEMPLATE_BASE, modelInstanceId: modelInstanceId || '' } as AgentForm
  const definition = canvasToDefinition(base, templateCanvas(template))
  return {
    graphSpecJson: JSON.stringify(definition.graphSpec),
    canvasJson: definition.canvasJson || '',
  }
}
