import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { baseParse, NodeTypes } from '@vue/compiler-dom'
import ts from 'typescript'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const files = {
  layout: 'src/styles/tokens/_layout.scss',
  brand: 'src/styles/tokens/_brand.scss',
  header: 'src/components/common/PageHeader.vue',
  collapsibleRegion: 'src/components/common/CollapsibleHeaderRegion.vue',
  collapseComposable: 'src/composables/useCollapsiblePageHeader.ts',
  projectList: 'src/views/registry/RegistryProjectList.vue',
  projectDetail: 'src/views/registry/RegistryProjectDetail.vue',
  sdkWorkbench: 'src/views/registry/SdkAccessWizard.vue',
  pageWorkbench: 'src/views/registry/PageAssistantWizard.vue',
  agentList: 'src/views/agent/AgentList.vue',
  workflowList: 'src/views/workflow/WorkflowList.vue',
  toolRetrieval: 'src/views/tool/ToolRetrievalTest.vue',
  toolAcl: 'src/views/settings/ToolAclList.vue',
  knowledgeList: 'src/views/KnowledgeList.vue',
  knowledgeImport: 'src/views/KnowledgeImport.vue',
  knowledgeDetail: 'src/views/KnowledgeDetail.vue',
  fileDetail: 'src/views/FileDetail.vue',
  retrievalTest: 'src/views/RetrievalTest.vue',
  bizIndexList: 'src/views/BizIndexList.vue',
  bizIndexDetail: 'src/views/BizIndexDetail.vue',
  modelInstances: 'src/views/model/ModelInstances.vue',
  modelPlayground: 'src/views/model/ModelPlayground.vue',
  mcpHub: 'src/views/mcp-hub/McpHubLayout.vue',
  router: 'src/router/index.ts',
  a2aHub: 'src/views/a2a-hub/A2aHubLayout.vue',
  a2aOverview: 'src/views/a2a-hub/A2aHubOverview.vue',
  a2aPublications: 'src/views/a2a-hub/publications/A2aPublicationList.vue',
  a2aRemoteAgents: 'src/views/a2a-hub/remote-agents/A2aRemoteAgentCatalog.vue',
  a2aTasks: 'src/views/a2a-hub/tasks/A2aTaskCenter.vue',
  a2aTrust: 'src/views/a2a-hub/trust/A2aTrustWorkspace.vue',
  a2aDeveloper: 'src/views/a2a-hub/developer/A2aDeveloperConsole.vue',
  platformUsers: 'src/views/settings/PlatformUserSettings.vue',
  businessUsers: 'src/views/settings/BusinessUserDirectory.vue',
  authProviders: 'src/views/settings/AuthProviderSettings.vue',
  domainList: 'src/views/domain/DomainList.vue',
  domainBoard: 'src/views/domain/DomainAssignmentBoard.vue',
  domainClassifier: 'src/views/domain/DomainClassifierTest.vue',
  contextGovernance: 'src/views/context/ContextGovernance.vue',
  runOpsList: 'src/views/runops/RunOpsList.vue',
  runOpsDetail: 'src/views/runops/RunOpsDetail.vue',
}

const materialFiles = [
  'tech-purple.webp',
  'aurora-cyan.webp',
  'nebula-violet.webp',
  'coral-rose.webp',
  'metro-green.webp',
  'solar-gold.webp',
  'deep-ocean.webp',
]

const readSources = () =>
  Object.fromEntries(Object.entries(files).map(([key, relative]) => [key, fs.readFileSync(path.join(root, relative), 'utf8')]))

function expectIncludes(failures, source, fragment, label) {
  if (!source.includes(fragment)) failures.push(`${label} is missing ${fragment}`)
}

function liveSource(source) {
  return source.replace(/<!--[\s\S]*?-->/g, '')
}

function pageHeaderTag(source) {
  return liveSource(source).match(/<PageHeader\b[\s\S]*?>/)?.[0] ?? ''
}

function actionSlot(source) {
  return liveSource(source).match(/<template\s+#actions>[\s\S]*?<\/template>/)?.[0] ?? ''
}

function expectPageHeader(
  failures,
  source,
  variant,
  domain,
  label,
  { compact = false, enriched = false, noActionControls = false, primaryLast = false } = {},
) {
  const activeSource = liveSource(source)
  const matches = activeSource.match(/<PageHeader\b/g) ?? []
  const tag = pageHeaderTag(activeSource)
  if (matches.length !== 1 || !tag) {
    failures.push(`${label} must render PageHeader`)
    return
  }
  expectIncludes(
    failures,
    activeSource,
    "import PageHeader from '@/components/common/PageHeader.vue'",
    `${label} PageHeader import`,
  )
  expectIncludes(failures, tag, `variant="${variant}"`, `${label} PageHeader`)
  expectIncludes(failures, tag, `domain="${domain}"`, `${label} PageHeader`)
  if (compact) {
    if (!/\scompact(?:\s|\/>|>)/.test(tag)) failures.push(`${label} PageHeader must declare compact`)
  } else if (/\scompact(?:\s|\/>|>)/.test(tag)) {
    failures.push(`${label} PageHeader must not declare compact`)
  }
  if (enriched) {
    if (!/\seyebrow=/.test(tag)) failures.push(`${label} PageHeader must declare eyebrow`)
    if (!/\sdescription=/.test(tag)) failures.push(`${label} PageHeader must declare description`)
  }
  if (/\s(?:class|style|:class|:style)=/.test(tag)) {
    failures.push(`${label} must not override PageHeader with class/style`)
  }
  if (/\s:?artwork="false"/.test(tag)) {
    failures.push(`${label} must not disable PageHeader artwork`)
  }
  if (
    /<(?:div|section|header)\b[^>]*class="[^"]*\b(?:page-header|model-hero|runtime-hero)\b/.test(activeSource)
    || /\.(?:model-hero|runtime-hero)\b/.test(activeSource)
  ) {
    failures.push(`${label} must not retain a legacy page header`)
  }
  const actions = actionSlot(activeSource)
  if (noActionControls && /<el-(?:input|select|radio(?:-group|-button)?|input-number|date-picker)\b/.test(actions)) {
    failures.push(`${label} action dock must not contain form controls`)
  }
  if (primaryLast && actions) {
    const buttons = [...actions.matchAll(/<el-button\b[\s\S]*?(?:\/>|>)/g)].map((match) => match[0])
    const primaryIndex = buttons.findIndex((button) => /\stype="primary"/.test(button))
    if (primaryIndex >= 0 && primaryIndex !== buttons.length - 1) {
      failures.push(`${label} primary action must be the last button`)
    }
  }
}

function expectCollapsibleHeader(failures, source, label, stateName, rootSelector, tableSelector) {
  expectIncludes(
    failures,
    source,
    "import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'",
    `${label} collapsible region import`,
  )
  expectIncludes(
    failures,
    source,
    "import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'",
    `${label} collapse composable import`,
  )
  expectIncludes(failures, source, `<CollapsibleHeaderRegion :collapsed="${stateName}">`, `${label} collapse region`)
  expectIncludes(failures, pageHeaderTag(source), `:collapsed="${stateName}"`, `${label} PageHeader collapse state`)
  expectIncludes(failures, source, `rootSelector: '${rootSelector}'`, `${label} collapse root`)
  expectIncludes(failures, source, `'${tableSelector}'`, `${label} internal table scroll target`)
  expectIncludes(failures, source, 'onScroll:', `${label} table layout callback`)
  expectIncludes(failures, source, 'onLayoutChange:', `${label} collapsed layout callback`)
}

function webpDimensions(buffer) {
  if (buffer.toString('ascii', 0, 4) !== 'RIFF' || buffer.toString('ascii', 8, 12) !== 'WEBP') return null
  let offset = 12
  while (offset + 8 <= buffer.length) {
    const type = buffer.toString('ascii', offset, offset + 4)
    const size = buffer.readUInt32LE(offset + 4)
    const data = offset + 8
    if (type === 'VP8X' && data + 10 <= buffer.length) {
      return {
        width: 1 + buffer[data + 4] + (buffer[data + 5] << 8) + (buffer[data + 6] << 16),
        height: 1 + buffer[data + 7] + (buffer[data + 8] << 8) + (buffer[data + 9] << 16),
      }
    }
    if (type === 'VP8 ' && data + 10 <= buffer.length) {
      return { width: buffer.readUInt16LE(data + 6) & 0x3fff, height: buffer.readUInt16LE(data + 8) & 0x3fff }
    }
    if (type === 'VP8L' && data + 5 <= buffer.length && buffer[data] === 0x2f) {
      const bits = buffer.readUInt32LE(data + 1)
      return { width: 1 + (bits & 0x3fff), height: 1 + ((bits >> 14) & 0x3fff) }
    }
    offset = data + size + (size % 2)
  }
  return null
}

const a2aOwners = [
  ['overview', 'a2aOverview', 'hub-overview', 'getA2aHubOverview'],
  ['publications', 'a2aPublications', 'publication-page', 'listA2aPublications'],
  ['remote-agents', 'a2aRemoteAgents', 'remote-catalog', 'listA2aRemoteAgents'],
  ['tasks', 'a2aTasks', 'task-center', 'listA2aTasks'],
  ['trust', 'a2aTrust', 'trust-workspace', 'listA2aTrustProfiles'],
  ['developer', 'a2aDeveloper', 'developer-console', 'listA2aPublications'],
]

function astNodes(node, predicate) {
  const found = []
  const visit = current => {
    if (predicate(current)) found.push(current)
    ts.forEachChild(current, visit)
  }
  if (node) visit(node)
  return found
}

const objectProperty = (node, name) => node?.properties?.find(property =>
  ts.isPropertyAssignment(property) && property.name.getText().replace(/^['"]|['"]$/g, '') === name)?.initializer
const literalText = node => node && ts.isStringLiteral(node) ? node.text : undefined
const staticProp = (node, name) => node?.props?.find(prop => prop.type === NodeTypes.ATTRIBUTE && prop.name === name)?.value?.content
const directiveProp = (node, name, argument) => node?.props?.find(prop => prop.type === NodeTypes.DIRECTIVE &&
  prop.name === name && (argument === undefined ? !prop.arg : prop.arg?.content === argument))
const directiveText = (node, name, argument) => directiveProp(node, name, argument)?.exp?.content?.trim()

function vueAst(source, label, failures) {
  const parsed = parseSfc(source, { filename: label })
  if (parsed.errors.length) failures.push(`${label} must remain a parseable SFC`)
  const elements = []
  let ast
  try {
    ast = baseParse(parsed.descriptor.template?.content ?? '')
    const visit = node => {
      if (node.type === NodeTypes.ELEMENT) elements.push(node)
      for (const child of node.children ?? []) visit(child)
    }
    visit(ast)
  } catch {
    failures.push(`${label} must retain a parseable template`)
  }
  const script = ts.createSourceFile(label, parsed.descriptor.scriptSetup?.content ?? '', ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  if (script.parseDiagnostics.length) failures.push(`${label} must retain parseable script setup`)
  return { ast, elements, script, styles: parsed.descriptor.styles }
}

function changedContractSource(source, mutate, name) {
  const transformed = mutate(source)
  if (transformed === source) throw new Error(`${name} must change its baseline before validation`)
  return transformed
}

function validateMcpHubHeader(failures, source) {
  const layout = vueAst(source, 'McpHubLayout', failures)
  const roots = layout.ast?.children.filter(node => node.type === NodeTypes.ELEMENT) ?? []
  const headers = layout.elements.filter(node => node.tag === 'PageHeader')
  const rootNode = roots[0]
  const header = headers[0]
  const imports = (name, module) => layout.script.statements.some(node => ts.isImportDeclaration(node) &&
    literalText(node.moduleSpecifier) === module && node.importClause?.name?.text === name)
  const unreachable = node => !node || node.props.some(prop => prop.type === NodeTypes.ATTRIBUTE
    ? ['hidden', 'inert', 'style'].includes(prop.name) ||
      (prop.name === 'aria-hidden' && prop.value?.content.trim().toLowerCase() === 'true')
    : prop.type === NodeTypes.DIRECTIVE && (['if', 'show', 'else', 'else-if', 'for', 'html', 'text', 'slot'].includes(prop.name) ||
      (prop.name === 'bind' && (!prop.arg || ['hidden', 'inert', 'aria-hidden', 'style', 'class'].includes(prop.arg.content)))))
  if (!(roots.length === 1 && rootNode?.tag === 'WorkbenchPage' && staticProp(rootNode, 'class') === 'mcp-hub-layout' &&
    staticProp(rootNode, 'layout') === 'list' && imports('WorkbenchPage', '@/components/common/WorkbenchPage.vue') &&
    imports('PageHeader', '@/components/common/PageHeader.vue') && headers.length === 1 &&
    rootNode.children.includes(header) && !unreachable(rootNode) && !unreachable(header))) {
    failures.push('McpHubLayout must retain one reachable direct shared PageHeader in its WorkbenchPage')
  }
  if (!(staticProp(header, 'variant') === 'overview' && staticProp(header, 'domain') === 'platform')) {
    failures.push('McpHubLayout shared PageHeader must remain exactly overview/platform')
  }
  if (!staticProp(header, 'description')?.trim()) {
    failures.push('McpHubLayout PageHeader must retain a non-empty purpose description')
  }
}

function validateA2aHubOwners(failures, sources) {
  const assert = (condition, message) => { if (!condition) failures.push(message) }
  const router = ts.createSourceFile('router/index.ts', sources.router, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const hubs = astNodes(router, node => ts.isObjectLiteralExpression(node) && literalText(objectProperty(node, 'path')) === 'a2a-hub')
  const hub = hubs[0]
  const dynamicImport = node => astNodes(node, call => ts.isCallExpression(call) && call.expression.kind === ts.SyntaxKind.ImportKeyword)
    .map(call => literalText(call.arguments[0]))
  const children = objectProperty(hub, 'children')
  const routes = children && ts.isArrayLiteralExpression(children) ? children.elements : []
  assert(!router.parseDiagnostics.length && hubs.length === 1 &&
    JSON.stringify(dynamicImport(objectProperty(hub, 'component'))) === JSON.stringify(['@/views/a2a-hub/A2aHubLayout.vue']) &&
    literalText(objectProperty(hub, 'redirect')) === '/a2a-hub/overview' && routes.length === a2aOwners.length,
  'A2A Hub must preserve the exact current parent route and shared header owner')
  for (const [routePath, key] of a2aOwners) {
    const registered = routes.filter(node => literalText(objectProperty(node, 'path')) === routePath)
    assert(registered.length === 1 && JSON.stringify(dynamicImport(objectProperty(registered[0], 'component'))) ===
      JSON.stringify([`@/${files[key].slice('src/'.length)}`]) &&
      literalText(objectProperty(objectProperty(registered[0], 'meta'), 'activeMenu')) === '/a2a-hub',
    `A2A Hub ${routePath} must register its current exact owner`)
  }

  const layout = vueAst(sources.a2aHub, 'A2aHubLayout', failures)
  const roots = layout.ast?.children.filter(node => node.type === NodeTypes.ELEMENT) ?? []
  const header = layout.elements.filter(node => node.tag === 'PageHeader')
  const nav = layout.elements.find(node => node.tag === 'nav')
  const link = layout.elements.find(node => node.tag === 'router-link')
  const items = astNodes(layout.script, node => ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === 'items')[0]?.initializer
  const paths = items && ts.isArrayLiteralExpression(items) ? items.elements.map(node => literalText(objectProperty(node, 'path'))) : []
  assert(roots.length === 1 && roots[0].tag === 'WorkbenchPage' && header.length === 1 &&
    roots[0].children.includes(header[0]) &&
    layout.script.statements.some(node => ts.isImportDeclaration(node) &&
      literalText(node.moduleSpecifier) === '@/components/common/WorkbenchPage.vue' && node.importClause?.name?.text === 'WorkbenchPage') &&
    !header[0].props.some(prop => prop.type === NodeTypes.DIRECTIVE && ['if', 'show', 'else', 'else-if'].includes(prop.name)) &&
    roots[0].children.some(node => node.type === NodeTypes.ELEMENT && node.tag === 'router-view'),
  'A2aHubLayout must retain one reachable shared PageHeader and its child router view')
  assert(staticProp(nav, 'aria-label') === 'A2A Hub 工作区' && directiveText(link, 'for') === 'item in items' &&
    directiveText(link, 'bind', 'to') === 'item.path' && directiveText(link, 'bind', 'key') === 'item.path' &&
    link.children.some(node => node.type === NodeTypes.ELEMENT && node.tag === 'strong' &&
      node.children.some(child => child.type === NodeTypes.INTERPOLATION && child.content?.content.trim() === 'item.label')) &&
    JSON.stringify(paths) === JSON.stringify(a2aOwners.map(([routePath]) => `/a2a-hub/${routePath}`)) &&
    link.props.every(prop => prop.type === NodeTypes.ATTRIBUTE
      ? ['class'].includes(prop.name)
      : prop.type === NodeTypes.DIRECTIVE && (prop.name === 'for' ||
        (prop.name === 'bind' && ['key', 'to', 'class', 'title'].includes(prop.arg?.content)))) &&
    !layout.styles.some(style => /outline\s*:\s*(?:none|0)(?:\s|;|})/i.test(style.content)),
  'A2aHubLayout must retain six native keyboard-focusable workspace links without focus suppression')
  assert(layout.elements.some(node => node.tag === 'el-tag' && staticProp(node, 'type') === 'primary') &&
    layout.elements.some(node => node.tag === 'el-tag' && staticProp(node, 'type') === 'info'),
  'A2aHubLayout must retain the protocol status tags')
  for (const [, key, rootClass, reloadApi] of a2aOwners) {
    const owner = vueAst(sources[key], files[key], failures)
    const rootNode = owner.ast?.children.find(node => node.type === NodeTypes.ELEMENT)
    const heading = rootNode?.children.find(node => node.type === NodeTypes.ELEMENT)
    const headingElements = []
    const visit = node => {
      if (!node) return
      if (node.type === NodeTypes.ELEMENT) headingElements.push(node)
      for (const child of node.children ?? []) visit(child)
    }
    visit(heading)
    const reload = owner.script.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'reload')
    const importsPanel = owner.script.statements.some(node => ts.isImportDeclaration(node) &&
      literalText(node.moduleSpecifier) === '@/components/common/WorkbenchPanel.vue' && node.importClause?.name?.text === 'WorkbenchPanel')
    assert(rootNode?.tag === 'section' && staticProp(rootNode, 'class') === rootClass && importsPanel &&
      ![rootNode, heading].some(node => node?.props.some(prop => prop.type === NodeTypes.DIRECTIVE && ['if', 'show', 'else', 'else-if'].includes(prop.name))) &&
      !owner.elements.some(node => node.tag === 'PageHeader') &&
      headingElements.some(node => node.tag === 'h2') &&
      headingElements.some(node => node.tag === 'el-button' && directiveText(node, 'on', 'click') === 'reload' &&
        directiveText(node, 'bind', 'loading') === 'loading' && !directiveProp(node, 'if') && !directiveProp(node, 'show')) &&
      astNodes(reload?.body, node => ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === reloadApi).length > 0,
    `${files[key]} must preserve its reachable section heading and real reload action under the shared Hub header`)
  }
}

function validate(sources, { validateAssets = true } = {}) {
  const failures = []
  const requiredTokens = new Map([
    ['--layout-page-header-height-compact', '76px'],
    ['--layout-page-header-height-standard', '96px'],
    ['--layout-page-header-height-emphasis', '112px'],
    ['--layout-page-header-leading-size', '52px'],
    ['--layout-page-header-action-gap', '8px'],
    ['--layout-page-header-tag-gap', '8px'],
    ['--layout-page-header-art-opacity', '0.92'],
  ])
  for (const [token, value] of requiredTokens) {
    expectIncludes(failures, sources.layout, `${token}: ${value};`, 'layout token contract')
  }

  for (const variant of ['overview', 'entity', 'workbench', 'standard']) {
    expectIncludes(failures, sources.header, `'${variant}'`, 'PageHeader variant union')
  }
  for (const domain of ['project', 'agent', 'workflow', 'tool', 'knowledge', 'governance', 'platform']) {
    expectIncludes(failures, sources.header, `'${domain}'`, 'PageHeader domain union')
  }
  for (const slot of ['leading', 'tags', 'meta', 'summary', 'mode', 'actions']) {
    expectIncludes(failures, sources.header, `name="${slot}"`, 'PageHeader slot contract')
  }
  expectIncludes(failures, sources.header, 'app-page-header__action-dock', 'PageHeader neutral action dock')
  expectIncludes(failures, sources.header, 'var(--page-header-material)', 'PageHeader material recipe')
  expectIncludes(failures, sources.header, 'app-page-header__title-row::before', 'PageHeader title-aligned accent')
  expectIncludes(failures, sources.header, 'collapsed?: boolean', 'PageHeader collapsed prop')
  expectIncludes(failures, sources.header, 'collapsed: false', 'PageHeader collapsed default')
  expectIncludes(failures, sources.header, "'is-collapsed': props.collapsed", 'PageHeader collapsed class')
  expectIncludes(failures, sources.header, '.app-page-header.is-collapsed', 'PageHeader collapsed height recipe')
  expectIncludes(
    failures,
    sources.header,
    "[data-theme='dark'] .app-page-header__tags :deep(.el-tag)",
    'PageHeader dark tag surface',
  )
  expectIncludes(
    failures,
    sources.header,
    'background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);',
    'PageHeader dark tag semantic background',
  )
  expectIncludes(
    failures,
    sources.header,
    "[data-theme='dark'] .app-page-header__tags :deep(.el-tag--info)",
    'PageHeader dark info tag readability',
  )
  expectIncludes(failures, sources.collapsibleRegion, 'collapsible-header-region__summary', 'collapsible region summary slot')
  expectIncludes(failures, sources.collapsibleRegion, ':aria-hidden="props.collapsed"', 'collapsible region accessibility state')
  expectIncludes(failures, sources.collapsibleRegion, 'grid-template-rows: 0fr', 'collapsible region hidden summary recipe')
  for (const fragment of [
    'DEFAULT_COLLAPSE_SCROLL_TOP = 80',
    'DEFAULT_EXPAND_SCROLL_TOP = 24',
    'DEFAULT_WHEEL_DELTA = 8',
    "addEventListener('wheel', handleWheel",
    "addEventListener('scroll', updateCollapsedByScroll",
    'refreshScrollTargets',
  ]) {
    expectIncludes(failures, sources.collapseComposable, fragment, 'collapse composable contract')
  }

  expectPageHeader(failures, sources.projectList, 'overview', 'project', 'RegistryProjectList')
  expectPageHeader(failures, sources.projectDetail, 'entity', 'project', 'RegistryProjectDetail')
  expectIncludes(
    failures,
    sources.projectDetail,
    'v-if="project?.environment"',
    'RegistryProjectDetail real environment tag',
  )
  for (const fragment of [
    'if (projectMissing.value)',
    "value: '项目不存在'",
    'if (loadError.value)',
    "value: '数据不可用'",
    "value: loading.value ? '正在加载' : '尚未加载'",
  ]) {
    expectIncludes(
      failures,
      sources.projectDetail,
      fragment,
      'RegistryProjectDetail truthful header state',
    )
  }
  if (sources.projectDetail.includes("project?.environment || 'dev'")) {
    failures.push('RegistryProjectDetail must not fabricate a dev environment before project data loads')
  }
  expectPageHeader(failures, sources.sdkWorkbench, 'workbench', 'project', 'SdkAccessWizard')
  expectPageHeader(failures, sources.pageWorkbench, 'workbench', 'project', 'BusinessPageWorkbench')
  expectPageHeader(failures, sources.agentList, 'overview', 'agent', 'AgentList')
  expectPageHeader(failures, sources.workflowList, 'overview', 'workflow', 'WorkflowList')
  expectCollapsibleHeader(
    failures,
    sources.projectList,
    'RegistryProjectList',
    'isProjectHeaderCollapsed',
    '.registry-project-page',
    '.project-table .el-scrollbar__wrap',
  )
  expectCollapsibleHeader(
    failures,
    sources.workflowList,
    'WorkflowList',
    'isWorkflowHeaderCollapsed',
    '.workflow-list-page',
    '.workflow-table .el-scrollbar__wrap',
  )
  expectCollapsibleHeader(
    failures,
    sources.agentList,
    'AgentList',
    'isAgentHeaderCollapsed',
    '.agent-page',
    '.agent-table .el-scrollbar__wrap',
  )
  expectPageHeader(failures, sources.toolRetrieval, 'standard', 'tool', 'ToolRetrievalTest', { enriched: true })
  expectPageHeader(failures, sources.toolAcl, 'standard', 'governance', 'ToolAclList', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.knowledgeList, 'overview', 'knowledge', 'KnowledgeList', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.knowledgeImport, 'workbench', 'knowledge', 'KnowledgeImport', { enriched: true })
  expectPageHeader(failures, sources.knowledgeDetail, 'entity', 'knowledge', 'KnowledgeDetail', { primaryLast: true })
  expectPageHeader(failures, sources.fileDetail, 'entity', 'knowledge', 'FileDetail')
  expectPageHeader(failures, sources.retrievalTest, 'standard', 'knowledge', 'RetrievalTest', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.bizIndexList, 'standard', 'knowledge', 'BizIndexList', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.bizIndexDetail, 'entity', 'knowledge', 'BizIndexDetail')
  expectPageHeader(failures, sources.modelInstances, 'overview', 'platform', 'ModelInstances', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.modelPlayground, 'standard', 'platform', 'ModelPlayground', { compact: true })
  // Hub titles already provide their accessible name; eyebrow is optional auxiliary copy.
  // Replace only this owner's enriched requirement with real description/reachability checks.
  expectPageHeader(failures, sources.mcpHub, 'overview', 'platform', 'McpHubLayout', { noActionControls: true })
  validateMcpHubHeader(failures, sources.mcpHub)
  expectPageHeader(failures, sources.a2aHub, 'overview', 'platform', 'A2aHubLayout', { noActionControls: true, primaryLast: true })
  expectIncludes(failures, pageHeaderTag(sources.a2aHub), 'description=', 'A2aHubLayout PageHeader description')
  validateA2aHubOwners(failures, sources)
  // Metrics plus account/role/audit partitions make identity governance an overview.
  expectPageHeader(failures, sources.platformUsers, 'overview', 'platform', 'PlatformUserSettings', { enriched: true })
  expectPageHeader(failures, sources.businessUsers, 'standard', 'platform', 'BusinessUserDirectory', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.authProviders, 'standard', 'platform', 'AuthProviderSettings', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.domainList, 'standard', 'governance', 'DomainList', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.domainBoard, 'workbench', 'governance', 'DomainAssignmentBoard', { enriched: true })
  expectPageHeader(failures, sources.domainClassifier, 'standard', 'governance', 'DomainClassifierTest', { compact: true })
  expectPageHeader(failures, sources.contextGovernance, 'workbench', 'governance', 'ContextGovernance', { enriched: true })
  expectPageHeader(failures, sources.runOpsList, 'overview', 'governance', 'RunOpsList', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.runOpsDetail, 'entity', 'governance', 'RunOpsDetail', { primaryLast: true })
  expectIncludes(failures, sources.sdkWorkbench, '<HeaderModeSwitch', 'SdkAccessWizard mode control')
  expectIncludes(failures, actionSlot(sources.platformUsers), '@click="reload"', 'PlatformUserSettings reload action')
  expectIncludes(failures, actionSlot(sources.authProviders), '@click="reload"', 'AuthProviderSettings reload action')
  expectIncludes(failures, actionSlot(sources.domainList), '@click="openCreate"', 'DomainList create action')
  expectIncludes(failures, actionSlot(sources.contextGovernance), '@click="reloadAll"', 'ContextGovernance reload action')
  expectIncludes(failures, actionSlot(sources.runOpsList), '@click="refreshAll"', 'RunOpsList refresh action')
  for (const [fragment, label] of [
    ['<MetricStrip', 'metric strip'],
    ['<FilterBar', 'shared filter bar'],
    ['<DataTableShell', 'shared data table shell'],
  ]) {
    if (!sources.agentList.includes(fragment)) {
      failures.push(`AgentList overview must render ${label}`)
    }
  }
  if ((sources.knowledgeList.match(/<HeaderMiniStat\b/g) ?? []).length !== 2) {
    failures.push('KnowledgeList overview header must render exactly two HeaderMiniStat items')
  }
  if (sources.modelInstances.includes('<HeaderMiniStat')) {
    failures.push('ModelInstances overview header must not render HeaderMiniStat items')
  }
  for (const fragment of [
    '<CollapsibleHeaderRegion :collapsed="isModelHeaderCollapsed">',
    ':collapsed="isModelHeaderCollapsed"',
    '<MetricStrip class="model-metric-strip"',
    "rootSelector: '.model-center'",
  ]) {
    if (!sources.modelInstances.includes(fragment)) {
      failures.push(`ModelInstances collapsible metric header is missing ${fragment}`)
    }
  }

  if (validateAssets) {
    for (const file of materialFiles) {
      const absolute = path.join(root, 'public/page-header-materials', file)
      if (!fs.existsSync(absolute)) {
        failures.push(`missing page-header material ${file}`)
        continue
      }
      const dimensions = webpDimensions(fs.readFileSync(absolute))
      if (!dimensions || dimensions.width !== 2048 || dimensions.height !== 512) {
        failures.push(`${file} must be a 2048x512 WebP; got ${dimensions ? `${dimensions.width}x${dimensions.height}` : 'invalid WebP'}`)
      }
      expectIncludes(failures, sources.brand, `/page-header-materials/${file}`, `brand material mapping for ${file}`)
    }
  }
  return failures
}

const sources = readSources()
const failures = validate(sources)

if (process.argv.includes('--self-test')) {
  // Preserve all baseline failures and still prove independent affected owners.
  // A proof requires a new, expected failure; it cannot borrow an unrelated baseline error.
  const baselineFailures = validate(sources, { validateAssets: false })
  if (failures.length === 0) console.log('POSITIVE_PROOF header-family-current=1')
  {
    try {
      changedContractSource(sources.a2aHub, value => value, 'header-family-no-op')
      failures.push('header-family no-op harness must reject unchanged input')
    } catch (error) {
      if (!error.message.includes('must change its baseline before validation')) failures.push(error.message)
      else console.log('HARNESS_PROOF header-family-no-op-rejected=1')
    }
    const mutations = [
      ['height-token', 'layout', (value) => value.replace('--layout-page-header-height-compact: 76px;', '--layout-page-header-height-compact: 75px;'), 'layout token contract'],
      ['variant-union', 'header', (value) => value.replaceAll("'overview'", "'overview-disabled'"), 'PageHeader variant union'],
      ['page-class-override', 'projectList', (value) => value.replace('<PageHeader', '<PageHeader class="rogue-header"'), 'must not override PageHeader'],
      ['tool-retrieval-compact', 'toolRetrieval', (value) => value.replace('title="Tool 检索测试"', 'title="Tool 检索测试"\n      compact'), 'ToolRetrievalTest PageHeader must not declare compact'],
      ['knowledge-wrong-variant', 'knowledgeList', (value) => value.replace('variant="overview"', 'variant="standard"'), 'KnowledgeList PageHeader is missing variant="overview"'],
      ['mcp-wrong-domain', 'mcpHub', (value) => value.replace('domain="platform"', 'domain="governance"'), 'McpHubLayout PageHeader is missing domain="platform"'],
      ['legacy-page-header', 'a2aHub', (value) => value.replace('<PageHeader', '<div class="page-header"></div>\n    <PageHeader'), 'A2aHubLayout must not retain a legacy page header'],
      ['knowledge-artwork-disabled', 'retrievalTest', (value) => value.replace('title="召回测试实验室"', 'title="召回测试实验室"\n      :artwork="false"'), 'RetrievalTest must not disable PageHeader artwork'],
      ['platform-users-domain', 'platformUsers', (value) => value.replace('domain="platform"', 'domain="governance"'), 'PlatformUserSettings PageHeader is missing domain="platform"'],
      ['context-wrong-variant', 'contextGovernance', (value) => value.replace('variant="workbench"', 'variant="standard"'), 'ContextGovernance PageHeader is missing variant="workbench"'],
      ['runops-control-in-dock', 'runOpsList', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-input />'), 'RunOpsList action dock must not contain form controls'],
      ['auth-primary-order', 'authProviders', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-button type="primary">非法前置主操作</el-button>'), 'AuthProviderSettings primary action must be the last button'],
      ['classifier-compact', 'domainClassifier', (value) => value.replace(' compact>', '>'), 'DomainClassifierTest PageHeader must declare compact'],
      ['project-collapse-binding', 'projectList', (value) => value.replace(':collapsed="isProjectHeaderCollapsed"', ':collapsed="false"'), 'RegistryProjectList collapse region is missing <CollapsibleHeaderRegion :collapsed="isProjectHeaderCollapsed">'],
      ['workflow-page-collapse-binding', 'workflowList', (value) => value.replace(':collapsed="isWorkflowHeaderCollapsed"', ':collapsed="false"'), 'WorkflowList collapse region is missing <CollapsibleHeaderRegion :collapsed="isWorkflowHeaderCollapsed">'],
      ['agent-page-collapse-binding', 'agentList', (value) => value.replace(':collapsed="isAgentHeaderCollapsed"', ':collapsed="false"'), 'AgentList collapse region is missing <CollapsibleHeaderRegion :collapsed="isAgentHeaderCollapsed">'],
      ['collapse-wheel-listener', 'collapseComposable', (value) => value.replace("addEventListener('wheel', handleWheel", "addEventListener('wheel-disabled', handleWheel"), 'collapse composable contract is missing addEventListener(\'wheel\', handleWheel'],
      ['collapse-summary-recipe', 'collapsibleRegion', (value) => value.replace('grid-template-rows: 0fr', 'grid-template-rows: 1fr'), 'collapsible region hidden summary recipe is missing grid-template-rows: 0fr'],
      ['a2a-missing-shared-header', 'a2aHub', value => value.replaceAll('PageHeader', 'MissingHeader'), 'A2aHubLayout must render PageHeader'],
      ['a2a-wrong-domain', 'a2aHub', value => value.replace('domain="platform"', 'domain="governance"'), 'A2aHubLayout PageHeader is missing domain="platform"'],
      ['a2a-wrong-variant', 'a2aHub', value => value.replace('variant="overview"', 'variant="standard"'), 'A2aHubLayout PageHeader is missing variant="overview"'],
      ['a2a-hidden-header', 'a2aHub', value => value.replace('<PageHeader', '<PageHeader v-show="false"'), 'one reachable shared PageHeader'],
      ['a2a-keyboard-focus', 'a2aHub', value => value.replace('class="hub-nav__item"', 'tabindex="-1" class="hub-nav__item"'), 'six native keyboard-focusable workspace links'],
      ['a2a-focus-suppression', 'a2aHub', value => value.replace('</style>', '.hub-nav__item:focus-visible { outline: none; }\n</style>'), 'six native keyboard-focusable workspace links'],
      ['a2a-wrong-link-target', 'a2aHub', value => value.replace(':to="item.path"', ':to="\'/a2a-hub/overview\'"'), 'six native keyboard-focusable workspace links'],
      ['a2a-missing-link-label', 'a2aHub', value => value.replace('<strong>{{ item.label }}</strong>', '<strong />'), 'six native keyboard-focusable workspace links'],
      ['a2a-wrong-owner', 'router', value => value.replace("@/views/a2a-hub/tasks/A2aTaskCenter.vue", "@/views/a2a/A2aSessionMonitor.vue"), 'A2A Hub tasks must register its current exact owner'],
      ...a2aOwners.map(([routePath, key]) => [`a2a-${routePath}-reload`, key,
        value => value.replace('@click="reload"', '@click="noop"'), 'reachable section heading and real reload action']),
      ['mcp-wrong-variant', 'mcpHub', value => value.replace('variant="overview"', 'variant="standard"'), 'McpHubLayout PageHeader is missing variant="overview"'],
      ['mcp-missing-description', 'mcpHub', value => value.replace('description="将能力和已发布 Workflow 安全发布为 MCP Tool。"', ''), 'McpHubLayout PageHeader must retain a non-empty purpose description'],
      ['mcp-empty-description', 'mcpHub', value => value.replace('description="将能力和已发布 Workflow 安全发布为 MCP Tool。"', 'description="  \n  "'), 'McpHubLayout PageHeader must retain a non-empty purpose description'],
      ['mcp-hidden-header', 'mcpHub', value => value.replace('<PageHeader', '<PageHeader v-show="false"'), 'one reachable direct shared PageHeader'],
      ['mcp-conditional-header', 'mcpHub', value => value.replace('<PageHeader', '<PageHeader v-if="false"'), 'one reachable direct shared PageHeader'],
      ['mcp-unreachable-header', 'mcpHub', value => value.replace(/(<PageHeader\b[\s\S]*?\/>)/, '<template v-if="false">$1</template>'), 'one reachable direct shared PageHeader'],
      ['mcp-hidden-root', 'mcpHub', value => value.replace('<WorkbenchPage', '<WorkbenchPage hidden'), 'one reachable direct shared PageHeader'],
      ['mcp-conditional-root', 'mcpHub', value => value.replace('<WorkbenchPage', '<WorkbenchPage v-if="false"'), 'one reachable direct shared PageHeader'],
      ['mcp-aria-hidden-header', 'mcpHub', value => value.replace('<PageHeader', '<PageHeader aria-hidden="true"'), 'one reachable direct shared PageHeader'],
      ['mcp-missing-shared-import', 'mcpHub', value => value.replace('import PageHeader from', 'import MissingPageHeader from'), 'one reachable direct shared PageHeader'],
      ['platform-users-wrong-variant', 'platformUsers', value => value.replace('variant="overview"', 'variant="standard"'), 'PlatformUserSettings PageHeader is missing variant="overview"'],
      ['platform-users-missing-eyebrow', 'platformUsers', value => value.replace('eyebrow="Identity & Access Management"', ''), 'PlatformUserSettings PageHeader must declare eyebrow'],
      ['platform-users-reload', 'platformUsers', value => value.replace('@click="reload"', '@click="noop"'), 'PlatformUserSettings reload action is missing @click="reload"'],
      ['business-users-wrong-variant', 'businessUsers', value => value.replace('variant="standard"', 'variant="overview"'), 'BusinessUserDirectory PageHeader is missing variant="standard"'],
    ]
    for (const [name, key, mutate, expectedFailure] of mutations) {
      let transformed
      try { transformed = changedContractSource(sources[key], mutate, `self-test mutation ${name}`) }
      catch (error) {
        failures.push(error.message)
        continue
      }
      const mutated = { ...sources, [key]: transformed }
      const proof = validate(mutated, { validateAssets: false })
      const introduced = proof.filter(failure => !baselineFailures.includes(failure))
      if (!introduced.some((failure) => failure.includes(expectedFailure))) {
        failures.push(`self-test mutation ${name} did not prove ${expectedFailure}`)
      } else {
        console.log(`MUTATION_PROOF ${name}=1`)
      }
    }
    const safeProofs = [
      ['attribute-order', 'platformUsers', (value) => value.replace('variant="overview"\n      domain="platform"', 'domain="platform"\n      variant="overview"')],
      ['filter-control-outside-dock', 'businessUsers', (value) => value.replace('<FilterBar', '<!-- filters stay outside PageHeader -->\n    <FilterBar')],
      ['a2a-header-attribute-order', 'a2aHub', value => value.replace('variant="overview"\n      height-preset="standard"', 'height-preset="standard"\n      variant="overview"')],
      ['a2a-native-link-comment', 'a2aHub', value => value.replace('<router-link', '<!-- native link retains keyboard focus -->\n      <router-link')],
      ['mcp-optional-eyebrow', 'mcpHub', value => value.replace('title="MCP 互联中心"', 'eyebrow="MCP HUB" title="MCP 互联中心"')],
      ['mcp-description-attribute-order', 'mcpHub', value => value.replace('      description="将能力和已发布 Workflow 安全发布为 MCP Tool。"\n', '')
        .replace('domain="platform"', 'description="将能力和已发布 Workflow 安全发布为 MCP Tool。"\n      domain="platform"')],
    ]
    for (const [name, key, mutate] of safeProofs) {
      let transformed
      try { transformed = changedContractSource(sources[key], mutate, `self-test safe proof ${name}`) }
      catch (error) {
        failures.push(error.message)
        continue
      }
      const mutated = { ...sources, [key]: transformed }
      const proof = validate(mutated, { validateAssets: false })
      if (JSON.stringify([...proof].sort()) !== JSON.stringify([...baselineFailures].sort())) {
        failures.push(`self-test safe proof ${name} produced ${proof.join(' | ')}`)
      } else {
        console.log(`SAFE_PROOF ${name}=1 (baseline failures retained: ${baselineFailures.length})`)
      }
    }
  }
}

if (failures.length) {
  for (const failure of failures) console.error(failure)
  process.exitCode = 1
} else {
  console.log('ReachAI page-header family contract is aligned.')
}
