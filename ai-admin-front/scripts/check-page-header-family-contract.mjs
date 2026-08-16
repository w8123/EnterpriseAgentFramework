import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

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
  toolList: 'src/views/tool/ToolList.vue',
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
  mcpVisibility: 'src/views/mcp/McpVisibilityBoard.vue',
  mcpClients: 'src/views/mcp/McpClientList.vue',
  mcpCalls: 'src/views/mcp/McpCallMonitor.vue',
  mcpOnboarding: 'src/views/mcp/McpOnboarding.vue',
  a2aEndpoints: 'src/views/a2a/A2aEndpointList.vue',
  a2aSessions: 'src/views/a2a/A2aSessionMonitor.vue',
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
  expectPageHeader(failures, sources.toolList, 'standard', 'tool', 'ToolList')
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
  expectPageHeader(failures, sources.mcpVisibility, 'standard', 'governance', 'McpVisibilityBoard', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.mcpClients, 'standard', 'governance', 'McpClientList', { enriched: true, primaryLast: true })
  expectPageHeader(failures, sources.mcpCalls, 'standard', 'governance', 'McpCallMonitor', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.mcpOnboarding, 'workbench', 'platform', 'McpOnboarding', { enriched: true })
  expectPageHeader(failures, sources.a2aEndpoints, 'standard', 'platform', 'A2aEndpointList', { enriched: true, noActionControls: true, primaryLast: true })
  expectPageHeader(failures, sources.a2aSessions, 'standard', 'governance', 'A2aSessionMonitor', { enriched: true, noActionControls: true })
  expectPageHeader(failures, sources.platformUsers, 'standard', 'platform', 'PlatformUserSettings', { enriched: true })
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
  expectIncludes(failures, sources.runOpsList, '@query="openTrace"', 'RunOpsList trace lookup entry')
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
  if (failures.length === 0) {
    const mutations = [
      ['height-token', 'layout', (value) => value.replace('--layout-page-header-height-compact: 76px;', '--layout-page-header-height-compact: 75px;'), 'layout token contract'],
      ['variant-union', 'header', (value) => value.replaceAll("'overview'", "'overview-disabled'"), 'PageHeader variant union'],
      ['page-class-override', 'projectList', (value) => value.replace('<PageHeader', '<PageHeader class="rogue-header"'), 'must not override PageHeader'],
      ['tool-retrieval-compact', 'toolRetrieval', (value) => value.replace('title="Tool 检索测试"', 'title="Tool 检索测试"\n      compact'), 'ToolRetrievalTest PageHeader must not declare compact'],
      ['knowledge-wrong-variant', 'knowledgeList', (value) => value.replace('variant="overview"', 'variant="standard"'), 'KnowledgeList PageHeader is missing variant="overview"'],
      ['mcp-wrong-domain', 'mcpVisibility', (value) => value.replace('domain="governance"', 'domain="platform"'), 'McpVisibilityBoard PageHeader is missing domain="governance"'],
      ['legacy-page-header', 'a2aEndpoints', (value) => value.replace('<PageHeader', '<div class="page-header"></div>\n    <PageHeader'), 'A2aEndpointList must not retain a legacy page header'],
      ['form-control-in-dock', 'mcpCalls', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-select />'), 'McpCallMonitor action dock must not contain form controls'],
      ['primary-action-order', 'mcpClients', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-button type="primary">非法前置主操作</el-button>'), 'McpClientList primary action must be the last button'],
      ['knowledge-artwork-disabled', 'retrievalTest', (value) => value.replace('title="召回测试实验室"', 'title="召回测试实验室"\n      :artwork="false"'), 'RetrievalTest must not disable PageHeader artwork'],
      ['platform-users-domain', 'platformUsers', (value) => value.replace('domain="platform"', 'domain="governance"'), 'PlatformUserSettings PageHeader is missing domain="platform"'],
      ['context-wrong-variant', 'contextGovernance', (value) => value.replace('variant="workbench"', 'variant="standard"'), 'ContextGovernance PageHeader is missing variant="workbench"'],
      ['runops-control-in-dock', 'runOpsList', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-input />'), 'RunOpsList action dock must not contain form controls'],
      ['auth-primary-order', 'authProviders', (value) => value.replace('<template #actions>', '<template #actions>\n        <el-button type="primary">非法前置主操作</el-button>'), 'AuthProviderSettings primary action must be the last button'],
      ['classifier-compact', 'domainClassifier', (value) => value.replace(' compact>', '>'), 'DomainClassifierTest PageHeader must declare compact'],
      ['runops-trace-entry', 'runOpsList', (value) => value.replace('@query="openTrace"', ''), 'RunOpsList trace lookup entry is missing @query="openTrace"'],
      ['project-collapse-binding', 'projectList', (value) => value.replace(':collapsed="isProjectHeaderCollapsed"', ':collapsed="false"'), 'RegistryProjectList collapse region is missing <CollapsibleHeaderRegion :collapsed="isProjectHeaderCollapsed">'],
      ['workflow-page-collapse-binding', 'workflowList', (value) => value.replace(':collapsed="isWorkflowHeaderCollapsed"', ':collapsed="false"'), 'WorkflowList collapse region is missing <CollapsibleHeaderRegion :collapsed="isWorkflowHeaderCollapsed">'],
      ['agent-page-collapse-binding', 'agentList', (value) => value.replace(':collapsed="isAgentHeaderCollapsed"', ':collapsed="false"'), 'AgentList collapse region is missing <CollapsibleHeaderRegion :collapsed="isAgentHeaderCollapsed">'],
      ['collapse-wheel-listener', 'collapseComposable', (value) => value.replace("addEventListener('wheel', handleWheel", "addEventListener('wheel-disabled', handleWheel"), 'collapse composable contract is missing addEventListener(\'wheel\', handleWheel'],
      ['collapse-summary-recipe', 'collapsibleRegion', (value) => value.replace('grid-template-rows: 0fr', 'grid-template-rows: 1fr'), 'collapsible region hidden summary recipe is missing grid-template-rows: 0fr'],
    ]
    for (const [name, key, mutate, expectedFailure] of mutations) {
      const mutated = { ...sources, [key]: mutate(sources[key]) }
      const proof = validate(mutated, { validateAssets: false })
      if (!proof.some((failure) => failure.includes(expectedFailure))) {
        failures.push(`self-test mutation ${name} did not prove ${expectedFailure}`)
      } else {
        console.log(`MUTATION_PROOF ${name}=1`)
      }
    }
    const safeProofs = [
      ['attribute-order', 'platformUsers', (value) => value.replace('variant="standard"\n      domain="platform"', 'domain="platform"\n      variant="standard"')],
      ['filter-control-outside-dock', 'businessUsers', (value) => value.replace('<FilterBar', '<!-- filters stay outside PageHeader -->\n    <FilterBar')],
    ]
    for (const [name, key, mutate] of safeProofs) {
      const mutated = { ...sources, [key]: mutate(sources[key]) }
      const proof = validate(mutated, { validateAssets: false })
      if (proof.length) {
        failures.push(`self-test safe proof ${name} produced ${proof.join(' | ')}`)
      } else {
        console.log(`SAFE_PROOF ${name}=1`)
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
