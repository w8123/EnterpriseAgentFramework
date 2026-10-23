import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { compileScript, parse as parseSfc } from '@vue/compiler-sfc'
import { baseParse, NodeTypes } from '@vue/compiler-dom'

const read = path => readFileSync(join(process.cwd(), path), 'utf8')
const shell = read('src/views/capability/BusinessCapabilityWorkbench.vue')
const panel = read('src/views/capability/source/SourceWorkbenchPanel.vue')
const scope = read('src/views/capability/source/BusinessCapabilitySourceWorkbench.vue')
const routes = read('src/router/index.ts')
const sourceActions = read('src/views/capability/source/components/SourceActions.vue')
const importDialog = read('src/views/capability/source/components/SourceImportDialog.vue')
const observations = read('src/views/capability/source/components/SourceObservationsPanel.vue')
const classificationSections = read('src/views/capability/components/CatalogClassificationSections.vue')
const classificationEditor = read('src/views/capability/components/CatalogClassificationEditor.vue')
const classificationActions = read('src/views/capability/composables/useCatalogClassificationEditor.ts')
const semantic = read('src/views/capability/source/composables/useSourceSemanticDocs.ts')
const graph = read('src/views/capability/source/graph/CapabilityGraphCanvas.vue')
const enrichment = read('src/views/capability/components/CapabilityEnrichmentPanel.vue')
const semanticDocsPanel = read('src/views/capability/source/components/SourceSemanticDocsPanel.vue')
const semanticDocument = read('src/views/capability/components/CapabilitySemanticDocument.vue')

// Inspect the real template tree so moving navigation inside a tab cannot pass via matching text.
const { descriptor, errors } = parseSfc(shell)
assert.deepEqual(errors, [], '业务能力外层模板必须可解析')
assert.ok(descriptor.template, '业务能力外层必须有模板')
const elements = []
function collectElements(node, ancestors = []) {
  if (node.type === NodeTypes.ELEMENT) {
    elements.push({ node, ancestors })
    for (const child of node.children) collectElements(child, [...ancestors, node])
  } else if (node.type === NodeTypes.ROOT) {
    for (const child of node.children) collectElements(child, ancestors)
  }
}
collectElements(baseParse(descriptor.template.content))
const attribute = (node, name) => node.props.find(prop => prop.type === NodeTypes.ATTRIBUTE && prop.name === name)
const directive = (node, name, argument) => node.props.find(prop => prop.type === NodeTypes.DIRECTIVE
  && prop.name === name && (argument === undefined || prop.arg?.content === argument))
const withClass = name => elements.filter(({ node }) => attribute(node, 'class')?.value?.content.split(/\s+/).includes(name))
const headers = elements.filter(({ node }) => node.tag === 'PageHeader')
assert.equal(headers.length, 1, '统一工作台只能有一个共享页头')
assert.equal(attribute(headers[0].node, 'variant')?.value?.content, 'standard', '工作台须使用标准紧凑页头，不能回到overview hero')
assert.ok(attribute(headers[0].node, 'compact'), '工作台页头必须使用共享组件的compact模式')
assert.equal(attribute(headers[0].node, 'compact').value, undefined, 'compact必须为布尔模式')
assert.equal(directive(headers[0].node, 'bind', 'artwork')?.exp?.content, 'false', '工作台页头不得占用装饰背景高度')
assert.equal(attribute(headers[0].node, 'title')?.value?.content, '业务能力')
assert.equal(attribute(headers[0].node, 'eyebrow'), undefined, '不得重新添加装饰性业务能力重复标题')
assert.equal(elements.filter(({ node }) => ['h1', 'header'].includes(node.tag)).length, 0, '外层不得另造第二个hero或标题')
const navigation = withClass('business-capability-workspaces')
assert.equal(navigation.length, 1, '所有工作视图共用一份局部导航')
assert.equal(navigation[0].node.tag, 'LiquidGlassNav')
assert.match(read('src/components/common/LiquidGlassNav.vue'), /<nav\b/, '公共局部导航保留原生 nav 语义')
assert.equal(attribute(navigation[0].node, 'aria-label')?.value?.content, '业务能力工作视图')
assert.ok(!navigation[0].ancestors.some(node => ['LiquidGlassTabs', 'el-tab-pane'].includes(node.tag)), '局部导航必须位于分类TAB外')
assert.equal(directive(navigation[0].node, 'if')?.exp?.content, '!classificationMode.active.value',
  '工作视图导航仅在分类调整模式隐藏')
assert.ok(!navigation[0].ancestors.some(node => directive(node, 'if') || directive(node, 'else') || directive(node, 'else-if')),
  '共享页头与导航容器必须常驻')
const tools = elements.find(({ node }) => node.tag === 'LiquidGlassDropdown')
assert.equal(directive(tools.node, 'if')?.exp?.content, "activeWorkspace === 'catalog' && !classificationMode.active.value",
  '分类调整时不显示无关整理入口')
const catalogActions = withClass('business-capability-catalog-actions')
assert.equal(catalogActions.length, 1, '目录整理操作只能有一个容器')
assert.ok(catalogActions[0].ancestors.some(node => node.tag === 'PageHeader')
  && !catalogActions[0].ancestors.some(node => ['LiquidGlassTabs', 'el-tab-pane'].includes(node.tag)),
  '整理操作须集中在公共页头操作区，资产 TAB 只负责类型切换')
assert.ok(tools.ancestors.includes(catalogActions[0].node), '整理菜单须归属于目录操作容器')
const tabs = elements.filter(({ node }) => node.tag === 'LiquidGlassTabs')
assert.match(read('src/components/common/LiquidGlassTabs.vue'), /<el-tabs v-bind="\$attrs"/, '公共标签页须转交 before-leave 与原生属性')
assert.equal(tabs.length, 1, 'Java/HTTP分类只能在资产目录出现一份')
assert.equal(directive(tabs[0].node, 'if')?.exp?.content, "activeWorkspace === 'catalog'", '项目共享来源、分组、语义与图谱不得显示分类TAB')
const sharedScope = withClass('business-capability-scope')
assert.equal(sharedScope.length, 0, '不得恢复已移除的页签右侧项目共享说明')
assert.doesNotMatch(descriptor.template.content, /项目共享 · Java 业务方法与 HTTP API/, '不得恢复已移除的页签右侧项目共享说明')
assert.ok(elements.some(({ node, ancestors }) => node.tag === 'router-view' && directive(node, 'else')
  && ancestors[ancestors.length - 1]?.tag === 'section'
  && !ancestors.some(parent => ['el-tabs', 'el-tab-pane'].includes(parent.tag))), '共享工作视图必须直接承接内容，不能嵌入分类TAB')

for (const text of ['Java 业务方法', 'HTTP API', '能力目录', '接入与变更', '批量整理说明', '关联分析']) assert.ok(shell.includes(text), `工作台缺少 ${text}`)
assert.doesNotMatch(shell, /合并业务分类|command="merge"/, '业务分类合并入口必须退场')
function assertTaskNavigation(source) {
  const script = compileScript(parseSfc(source).descriptor, { id: 'task-navigation' }).scriptSetupAst || []
  const views = script.flatMap(node => node.type === 'VariableDeclaration' ? node.declarations : [])
    .find(node => node.id.name === 'workspaces')?.init
  const array = views?.type === 'TSAsExpression' ? views.expression : views
  assert.equal(array?.type, 'ArrayExpression')
  assert.deepEqual(array.elements.map(item => Object.fromEntries(item.properties.map(prop => [prop.key.name, prop.value.value]))),
    [{ value: 'catalog', label: '能力目录' }, { value: 'sources', label: '接入与变更' }], '主导航只能提供目录和接入维护两条任务路径')
}
assertTaskNavigation(shell)
assert.throws(() => assertTaskNavigation(shell.replace("{ value: 'sources', label: '接入与变更' }", "{ value: 'sources', label: '接入与变更' }, { value: 'graph', label: '图谱' }")), '守卫必须拒绝恢复五个平级入口')
assert.doesNotMatch(shell, /项目接入与同步|openProject|FolderOpened/, '不得恢复已移除的页头接入动作')
assert.match(routes, /:category\(java-methods\|http-apis\)\/:workspace\(sources\|semantics\|graph\)/, '局部视图必须有同一工作台的稳定地址')
assert.match(routes, /BusinessCapabilityLegacyGroups/, '原分组链接必须转到能力目录')
assert.doesNotMatch(panel, /SourceGroupsPanel|openMergeDialog|openRenameDialog/, '来源页不得保留第二套分类管理')
assert.equal(existsSync(join(process.cwd(), 'src/views/capability/source/components/SourceGroupsPanel.vue')), false)
assert.equal(existsSync(join(process.cwd(), 'src/views/capability/components/CatalogGroupFilter.vue')), false)
assert.match(read('src/views/capability/components/BusinessCapabilityCatalogList.vue'), /preserve-empty-body/, '空结果必须保留分类导航和整理入口')
assert.match(read('src/styles/_project-list.scss'), /project-list-table-shell--fit:not\(\.data-table-shell--preserve-empty-body\):has/, '空表隐藏规则必须尊重共享持久内容变体')
assert.doesNotMatch(routes, /path: 'scan-project\/:id'|name: 'ScanProjectDetail'/, '旧重复管理页必须退场')
assert.equal(existsSync(join(process.cwd(), 'src/views/scan/ScanProjectDetail.vue')), false)
assert.match(scope, /scope\?\.canLoadData\.value/, '来源工作区必须使用已确认项目')
assert.match(scope, /platformSessionId/, '来源工作区必须隔离账号')
assert.match(scope, /:key="contextKey"/, '账号或项目变化必须卸载旧控制器')
assert.doesNotMatch(panel, /<h1|<PageHeader|<el-tab-pane/, '共享来源组件不能创建第二页头或第三套分类')
assert.doesNotMatch(panel, /整理整个项目的 Java 业务方法和 HTTP API|项目共享 · Java 业务方法与 HTTP API/, '共享内容不得恢复已移除的项目范围说明')
for (const feature of ['SourceImportDialog', 'SourceObservationsPanel', 'SourceSemanticTasksDrawer',
  'SourceSemanticDialogs', 'SourceObservationDiffDialog', 'SourceGraphPanel', 'SourceSemanticDocsPanel']) {
  assert.ok(panel.includes(`<${feature}`), `真实功能未承接：${feature}`)
}
for (const operation of ['scanSdkCapabilities', 'handleRescan', 'handleSaveScanSettings', 'handleExportScanToolsExcel',
  'startSensitiveDataScanFlow', 'regenerateTool', 'regenerateSemanticDoc', 'handleRescanToolFromSource', 'handleReconcile']) {
  assert.ok(panel.includes(operation), `来源操作未承接：${operation}`)
}
assert.match(sourceActions, /批量补充说明/)
assert.match(sourceActions, /核对来源关联/)
assert.equal(existsSync(join(process.cwd(), 'src/views/capability/source/components/SourceMaintenanceDrawer.vue')), false,
  '只承载无效检索索引重建的维护抽屉必须退场')
for (const [label, source] of [
  ['source actions', sourceActions],
  ['source workbench', panel],
  ['source operations', read('src/views/capability/source/composables/useSourceOperations.ts')],
]) {
  assert.doesNotMatch(source, /更新检索索引|rebuild-index|rebuildToolIndex|startToolRetrievalRebuild|ToolRebuildTask/,
    `${label} 不得恢复已退场的检索索引重建入口或任务状态`)
}
assert.match(sourceActions, /v-if="workspace === 'sources'"/, '同步操作不应在整理工具中重复')
for (const operation of ['SDK 接入指引', '扫描并同步接口', '重新扫描', '扫描解析设置', 'ReachAI 将主动调用业务系统', '当前回调地址使用 localhost', '最近一次同步失败']) {
  assert.ok(importDialog.includes(operation), `接入/设置行为未承接：${operation}`)
}
assert.doesNotMatch(importDialog, /AI Coding 扫描|name="ai-coding"/, '取消未实现的扫描占位入口')
for (const file of ['src/views/capability/BusinessMethodCatalog.vue', 'src/views/api/HttpApiCatalog.vue']) {
  const catalog = read(file)
  assert.match(catalog, /<CatalogClassificationSections/, '分类必须融入两类能力的列表')
  assert.match(catalog, /<CatalogClassificationEditor/, '分类说明和整理必须在目录承接')
}
assert.match(classificationSections, /本页/, '分页分类不能冒充全量成员')
assert.match(classificationSections, /未分类/)
assert.match(classificationEditor, /onBeforeRouteUpdate\(requestLeave\)/, '离开编辑上下文须保护草稿')
for (const action of ['createScanModule', 'renameScanModule', 'getClassificationAssignments', 'replaceClassifications', 'getCapabilityEnrichment']) assert.ok(classificationActions.includes(action))
assert.match(classificationSections, /新增分类/, '调整模式须有独立新增分类入口')
assert.match(classificationSections, /v-if="canAdjust" class="classification-selection"/, '空结果也必须保留新增分类入口')
for (const [file, source] of [
  ['classification editor', classificationEditor],
  ['classification actions', classificationActions],
  ['semantic API', read('src/api/semanticDoc.ts')],
  ['semantic types', read('src/types/semanticDoc.ts')],
]) {
  assert.doesNotMatch(source, /mergeScanModules|ModuleMergeRequest|mergeTargetId|selectedSources|mergeConfirmed|groups\/merge|确认合并分类/,
    `${file} 不得保留分类合并状态、请求或契约`)
}
assert.match(observations, /扫描敏感数据/)
assert.match(observations, /导出 EXCEL/)
assert.doesNotMatch(observations, /promoteTool|pushTool|unpromoteTool|promoteModuleTools|testTool|editTool|batchToggle|toggleEnabled/)
for (const action of ['confirmEdge', 'rejectEdge', 'submitPendingEdge', 'startApiDrag', 'inferRequestResponse', 'regenerate']) {
  assert.ok(graph.includes(action), `图谱操作未承接：${action}`)
}
assert.match(graph, /qualifiedName/)
assert.match(graph, /查看所属资产/)
assert.match(semantic, /subjectType/)
assert.match(semantic, /sourceObservationIds/)
assert.match(semantic, /disposed \|\| expected !== taskSequence/)
assert.match(semantic, /stopPollingSensitiveTask\(\)/)
assert.doesNotMatch(read('src/api/semanticDoc.ts'), /\/api\/scan-projects|\/api\/scan-modules|\/api\/tools/, '新语义与分组不能回到旧维护入口')
assert.match(enrichment, /data\.projectId !== props\.projectId/)
assert.match(enrichment, /semanticStale/)
assert.match(enrichment, /<CapabilitySemanticDocument/, '资产详情必须复用安全正文组件')
assert.match(semanticDocsPanel, /<CapabilitySemanticDocument/, '语义阅读器必须复用相同正文排版')
const { descriptor: documentDescriptor, errors: documentErrors } = parseSfc(semanticDocument)
assert.deepEqual(documentErrors, [], '共享语义正文模板必须可解析')
function syntaxNodes(value) {
  if (!value || typeof value !== 'object') return []
  return [value, ...Object.entries(value).filter(([key]) => !['loc', 'comments', 'leadingComments', 'trailingComments'].includes(key))
    .flatMap(([, child]) => Array.isArray(child) ? child.flatMap(syntaxNodes) : syntaxNodes(child))]
}
function assertSafeDocumentRenderer(source) {
  const { descriptor: doc, errors: parseErrors } = parseSfc(source)
  assert.deepEqual(parseErrors, [])
  const htmlDirectives = syntaxNodes(baseParse(doc.template?.content || ''))
    .filter(node => node.type === NodeTypes.DIRECTIVE && node.name === 'html')
  assert.equal(htmlDirectives.length, 1, '共享正文只能有一个HTML输出')
  assert.equal(htmlDirectives[0].exp?.content, 'content', '正文必须绑定安全处理后的内容')
  const ast = compileScript(doc, { id: 'semantic-document-contract' }).scriptSetupAst || []
  assert.ok(ast.some(node => node.type === 'ImportDeclaration' && node.source.value === '@/utils/safeMarkdown'
    && node.specifiers.some(item => item.imported?.name === 'renderSafeMarkdown' && item.local.name === 'renderSafeMarkdown')))
  const content = ast.flatMap(node => node.type === 'VariableDeclaration' ? node.declarations : []).find(node => node.id.name === 'content')?.init
  assert.equal(content?.callee?.name, 'computed')
  const body = content.arguments[0]?.body
  assert.equal(body?.type, 'BlockStatement')
  assert.equal(body.body.length, 4, '安全清理之后只允许固定的键盘无障碍后处理')
  const template = body.body.find(node => node.type === 'VariableDeclaration')?.declarations[0]
  assert.equal(template?.id.name, 'template')
  assert.equal(template?.init?.callee?.object?.name, 'document')
  assert.equal(template?.init?.callee?.property?.name, 'createElement')
  assert.equal(template?.init?.arguments[0]?.value, 'template')
  const htmlWrites = syntaxNodes(body).filter(node => node.type === 'AssignmentExpression' && node.left?.property?.name === 'innerHTML')
  assert.equal(htmlWrites.length, 1, '后处理不能覆盖已清理的HTML')
  assert.equal(htmlWrites[0].left.object.name, 'template')
  assert.equal(htmlWrites[0].right.callee?.name, 'renderSafeMarkdown', '正文必须先经过安全Markdown渲染器')
  assert.equal(htmlWrites[0].right.arguments?.[0]?.object?.name, 'props')
  assert.equal(htmlWrites[0].right.arguments?.[0]?.property?.name, 'contentMd')
  const postprocess = body.body[2]
  assert.equal(postprocess.type, 'ForOfStatement')
  assert.equal(postprocess.left?.kind, 'const')
  assert.equal(postprocess.left?.declarations?.[0]?.id?.name, 'element')
  assert.equal(postprocess.right?.callee?.object?.object?.name, 'template')
  assert.equal(postprocess.right?.callee?.object?.property?.name, 'content')
  assert.equal(postprocess.right?.callee?.property?.name, 'querySelectorAll')
  assert.deepEqual(postprocess.right.arguments.map(arg => arg.value), ['pre, table'])
  assert.equal(postprocess.body?.type, 'BlockStatement')
  assert.equal(postprocess.body.body.length, 3, '后处理不能添加其他DOM修改')
  const attributes = ['tabindex', 'role', 'aria-label']
  for (const [index, statement] of postprocess.body.body.entries()) {
    assert.equal(statement.type, 'ExpressionStatement')
    const call = statement.expression
    assert.equal(call.type, 'CallExpression')
    assert.equal(call.callee?.object?.name, 'element')
    assert.equal(call.callee?.property?.name, 'setAttribute')
    assert.equal(call.arguments.length, 2)
    assert.equal(call.arguments[0]?.value, attributes[index], '只能补充固定的无障碍属性')
    if (index < 2) assert.equal(call.arguments[1]?.value, index === 0 ? '0' : 'region')
    else {
      const label = call.arguments[1]
      assert.equal(label.type, 'ConditionalExpression')
      assert.equal(label.test?.operator, '===')
      assert.equal(label.test?.left?.object?.name, 'element')
      assert.equal(label.test?.left?.property?.name, 'tagName')
      assert.equal(label.test?.right?.value, 'PRE')
      for (const branch of [label.consequent, label.alternate]) {
        assert.equal(branch.type, 'StringLiteral', '阅读区域名称只能使用固定文案')
        assert.ok(branch.value.trim())
      }
    }
  }
  const returned = body.body.at(-1)
  assert.equal(returned?.type, 'ReturnStatement')
  assert.equal(returned?.argument?.object?.name, 'template')
  assert.equal(returned?.argument?.property?.name, 'innerHTML', '只能返回经过清理与键盘标记的正文')
}
assertSafeDocumentRenderer(semanticDocument)
for (const unsafe of [semanticDocument.replace('v-html="content"', 'v-html="props.contentMd"'),
  semanticDocument.replace('template.innerHTML = renderSafeMarkdown(props.contentMd)', 'template.innerHTML = props.contentMd'),
  semanticDocument.replace('return template.innerHTML', 'return props.contentMd'),
  semanticDocument.replace("element.setAttribute('role', 'region')", "element.setAttribute('onclick', props.contentMd)"),
  semanticDocument.replace('return template.innerHTML', 'template.content.querySelector("pre")?.setAttribute("onclick", props.contentMd); return template.innerHTML')]) {
  assert.throws(() => assertSafeDocumentRenderer(unsafe), '正文安全检查必须拒绝未经清理的HTML')
}
assert.match(enrichment, /<CapabilityParamHints/)
assert.match(graph, /<CapabilityEnrichmentPanel/)
const hints = read('src/views/capability/components/CapabilityParamHints.vue')
assert.match(hints, /Array\.isArray\(props\.hints\)/)
assert.doesNotMatch(hints, /getSubjectApiGraphParamHints|onMounted|watch\(/, '参数提示使用同次 enrichment 数据，不能重复发起读取')
assert.match(enrichment, /:hints="[^"]*paramHints"/)
assert.match(hints, /graphState === 'STALE'/)
assert.match(hints, /当前无法判断是否有可用提示/)
assert.doesNotMatch(hints, /semanticStale/)
assert.match(read('src/composables/useCatalogQuery.ts'), /disposed \|\| leaving \|\| route.path !== ownerPath/)
for (const [file, referencesApi] of [
  ['src/views/api/HttpApiDetail.vue', 'getHttpApiReferences'],
  ['src/views/capability/components/BusinessMethodDetailDialog.vue', 'getBusinessMethodReferences'],
]) {
  const detail = read(file)
  assert.match(detail, /<CapabilityEnrichmentPanel/)
  assert.doesNotMatch(detail, /WorkflowHandoff|workflowHandoff|workflow-handoff|用于\s*Workflow/,
    `${file} 不得恢复能力详情到 Workflow 的添加入口`)
  assert.match(detail, /使用位置/, `${file} 必须保留使用位置`)
  assert.match(detail, new RegExp(`${referencesApi}\\(`), `${file} 必须保留真实使用位置查询`)
  assert.match(detail, /usageRoute\(usage\)/, `${file} 必须保留已有引用的查看入口`)
  assert.match(detail, /runtimeEvidence === 'COMPLETE'[\s\S]*?publicationEvidence === 'COMPLETE'/,
    `${file} 不能将不完整的引用证据视为没有使用方`)
}
for (const file of [
  'src/views/capability/components/CapabilityWorkflowHandoff.vue',
  'src/views/capability/components/useCapabilityWorkflowHandoff.ts',
  'src/views/workflow/capabilityWorkflowHandoff.ts',
  'src/views/workflow/composables/useWorkflowCapabilityHandoff.ts',
]) {
  assert.equal(existsSync(join(process.cwd(), file)), false, `${file} 专用添加衔接实现必须退场`)
}
const workflowStudio = read('src/views/workflow/WorkflowStudio.vue')
assert.doesNotMatch(workflowStudio, /[Cc]apabilityHandoff|useWorkflowCapabilityHandoff|capability-handoff/,
  'Workflow Studio 不得恢复来自能力详情的添加请求接收逻辑')
assert.match(workflowStudio, /<NodeConfigPanel[\s\S]*?:project-id="studio\?\.projectId \|\| null"[\s\S]*?:project-code="studio\?\.projectCode \|\| null"/,
  'Workflow Studio 必须保留所属项目的节点资源配置')
assert.match(read('src/views/workflow/studio-panels/NodeConfigPanel.vue'), /tool: ToolConfigPanel/,
  '能力节点必须继续使用原生资源配置面板')
const toolConfigPanel = read('src/views/workflow/studio-panels/ToolConfigPanel.vue')
for (const picker of ['openBusinessMethodPicker', 'openHttpApiPicker']) {
  assert.ok(toolConfigPanel.includes(`@click="${picker}"`), `Workflow Studio 必须保留 ${picker} 选择入口`)
}
for (const selection of ['applyBusinessMethodSelection', 'applyHttpApiSelection']) {
  assert.match(toolConfigPanel, new RegExp(`${selection}\\(config\\.value,`),
    `Workflow Studio 必须继续通过 ${selection} 应用所选资源`)
}
const apiMarket = read('src/views/api-market/ApiMarket.vue')
assert.doesNotMatch(apiMarket, /createWorkflowFromIntegration|creatingWorkflow|workflowFeedback|buildApiMarketWorkflowDraft|创建\s*Workflow/,
  'API 市场不得恢复单 API 的 Workflow 草稿创建入口或专用逻辑')
assert.equal(existsSync(join(process.cwd(), 'src/utils/apiMarketWorkflow.ts')), false,
  'API 市场专用 Workflow 草稿构造工具必须退场')
assert.match(apiMarket, /@click="openOwnerApi\(row, binding\.apiId\)"/,
  'API 市场必须保留配置所属 API 的入口')
assert.match(apiMarket, /async function openOwnerApi\(/,
  'API 市场必须继续提供所属 API 详情跳转')
console.log('Business capability workbench ownership and feature handover checks passed.')
