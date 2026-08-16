import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { parse } from '@vue/compiler-sfc'
import { compileString } from 'sass'
import ts from 'typescript'

const REQUIRED_LAYOUT_MODES = new Set(['standard', 'project-workbench', 'edge-to-edge', 'studio'])
const REQUIRED_LAYOUT_TOKEN_SEQUENCES = new Map([
  ['--layout-breadcrumb-height', [['base :root', '40px']]],
  ['--layout-page-header-height-compact', [['base :root', '76px']]],
  ['--layout-page-header-height-standard', [['base :root', '96px']]],
  ['--layout-page-header-height-emphasis', [['base :root', '112px']]],
  ['--layout-page-header-leading-size', [['base :root', '52px']]],
  ['--layout-page-header-action-gap', [['base :root', '8px']]],
  ['--layout-page-header-tag-gap', [['base :root', '8px']]],
  ['--layout-page-header-art-opacity', [['base :root', '0.92']]],
  ['--layout-list-page-end', [['base :root', 'var(--layout-sidebar-inset-block)']]],
  [
    '--layout-content-inline',
    [
      ['base :root', '28px'],
      ['@media (max-width: 900px) :root', '16px'],
      ['@media (max-width: 760px) :root', '12px'],
    ],
  ],
  [
    '--layout-page-start',
    [
      ['base :root', '10px'],
      ['@media (max-width: 900px) :root', '10px'],
      ['@media (max-width: 760px) :root', '8px'],
    ],
  ],
  [
    '--layout-page-end',
    [
      ['base :root', '16px'],
      ['@media (max-width: 900px) :root', '16px'],
      ['@media (max-width: 760px) :root', '12px'],
    ],
  ],
  [
    '--layout-page-gap',
    [
      ['base :root', '10px'],
      ['@media (max-width: 900px) :root', '10px'],
      ['@media (max-width: 760px) :root', '8px'],
    ],
  ],
  [
    '--layout-page-header-padding-block',
    [
      ['base :root', '14px'],
      ['@media (max-width: 900px) :root', '18px'],
      ['@media (max-width: 760px) :root', '16px'],
    ],
  ],
  [
    '--layout-page-header-padding-inline',
    [
      ['base :root', '28px'],
      ['@media (max-width: 900px) :root', '18px'],
      ['@media (max-width: 760px) :root', '16px'],
    ],
  ],
])

const REQUIRED_PROJECT_WORKBENCH_RECIPE = new Map([
  ['display', 'flex'],
  ['flex-direction', 'column'],
  ['width', '100%'],
  ['min-width', '0'],
  ['box-sizing', 'border-box'],
  [
    'padding',
    'var(--layout-page-start) var(--layout-content-inline) var(--layout-page-end)',
  ],
  ['gap', 'var(--layout-page-gap)'],
])

const REQUIRED_LIST_PAGE_RECIPE = new Map([
  ['min-height', '100%'],
  ['padding-bottom', 'var(--layout-list-page-end)'],
])

const REQUIRED_LIST_SURFACE_RECIPE = new Map([
  ['flex', '1 0 auto'],
  ['min-height', '0'],
])

const FORBIDDEN_LEGACY_SPACING_TOKENS = [
  '--reachai-workbench-page-padding',
  '--reachai-workbench-title-gap',
  '--reachai-workbench-breadcrumb-height',
  '--reachai-workbench-title-padding',
]

const STANDARD_PAGE_FILES = new Set([
  'src/views/dashboard/Dashboard.vue',
  'src/views/agent/AgentList.vue',
  'src/views/agent/AgentEdit.vue',
  'src/views/agent/AgentEval.vue',
  'src/views/workflow/WorkflowVersions.vue',
  'src/views/runops/RunOpsList.vue',
  'src/views/runops/RunOpsDetail.vue',
  'src/views/KnowledgeList.vue',
  'src/views/KnowledgeImport.vue',
  'src/views/KnowledgeDetail.vue',
  'src/views/FileDetail.vue',
  'src/views/RetrievalTest.vue',
  'src/views/BizIndexList.vue',
  'src/views/BizIndexDetail.vue',
  'src/views/model/ModelInstances.vue',
  'src/views/model/ModelInstanceDetail.vue',
  'src/views/tool/ToolList.vue',
  'src/views/tool/ToolRetrievalTest.vue',
  'src/views/capability/CapabilityKernel.vue',
  'src/views/capability/CapabilityMining.vue',
  'src/views/capability/slot/SlotExtractorList.vue',
  'src/views/capability/slot/SlotDictDept.vue',
  'src/views/capability/slot/SlotDictUser.vue',
  'src/views/capability/slot/SlotExtractLogs.vue',
  'src/views/registry/CapabilitySyncDebug.vue',
  'src/views/mcp/McpVisibilityBoard.vue',
  'src/views/mcp/McpClientList.vue',
  'src/views/mcp/McpCallMonitor.vue',
  'src/views/mcp/McpOnboarding.vue',
  'src/views/a2a/A2aEndpointList.vue',
  'src/views/a2a/A2aSessionMonitor.vue',
  'src/views/settings/PlatformUserSettings.vue',
  'src/views/settings/BusinessUserDirectory.vue',
  'src/views/settings/AuthProviderSettings.vue',
  'src/views/settings/ToolAclList.vue',
  'src/views/settings/PersonalMemory.vue',
  'src/views/settings/MemoryErasure.vue',
  'src/views/domain/DomainList.vue',
  'src/views/domain/DomainAssignmentBoard.vue',
  'src/views/domain/DomainClassifierTest.vue',
  'src/views/context/ContextGovernance.vue',
])

const PROJECT_WORKBENCH_PAGE_FILES = new Set([
  'src/views/workflow/WorkflowList.vue',
  'src/views/registry/RegistryProjectList.vue',
  'src/views/registry/RegistryProjectDetail.vue',
  'src/views/scan/ScanProjectDetail.vue',
  'src/views/registry/PageAssistantWizard.vue',
  'src/views/registry/SdkAccessWizard.vue',
  'src/views/settings/EmbedOpsMonitor.vue',
  'src/views/settings/EmbedSessionAudit.vue',
])

const LIST_FILL_PAGE_FILES = new Set([
  'src/views/agent/AgentList.vue',
  'src/views/workflow/WorkflowList.vue',
  'src/views/KnowledgeList.vue',
  'src/views/BizIndexList.vue',
  'src/views/model/ModelInstances.vue',
  'src/views/tool/ToolList.vue',
  'src/views/capability/CapabilityKernel.vue',
  'src/views/capability/slot/SlotExtractorList.vue',
  'src/views/capability/slot/SlotDictDept.vue',
  'src/views/capability/slot/SlotDictUser.vue',
  'src/views/capability/slot/SlotExtractLogs.vue',
  'src/views/registry/RegistryProjectList.vue',
  'src/views/registry/RegistryProjectDetail.vue',
  'src/views/mcp/McpVisibilityBoard.vue',
  'src/views/mcp/McpClientList.vue',
  'src/views/mcp/McpCallMonitor.vue',
  'src/views/a2a/A2aEndpointList.vue',
  'src/views/a2a/A2aSessionMonitor.vue',
  'src/views/settings/PlatformUserSettings.vue',
  'src/views/settings/BusinessUserDirectory.vue',
  'src/views/settings/AuthProviderSettings.vue',
  'src/views/settings/ToolAclList.vue',
  'src/views/domain/DomainList.vue',
])

const PROJECT_WORKBENCH_STYLE_FILES = new Map([
  [
    'src/views/registry/styles/RegistryProjectDetail.scss',
    'src/views/registry/RegistryProjectDetail.vue',
  ],
  [
    'src/views/registry/styles/RegistryProjectDetail.global.scss',
    'src/views/registry/RegistryProjectDetail.vue',
  ],
  [
    'src/views/registry/styles/PageWorkbench.scss',
    'src/views/registry/PageAssistantWizard.vue',
  ],
  [
    'src/views/registry/styles/SdkAccessWizard.scss',
    'src/views/registry/SdkAccessWizard.vue',
  ],
  [
    'src/views/registry/styles/SdkAccessWizard.ai-coding.figma.scss',
    'src/views/registry/SdkAccessWizard.vue',
  ],
  [
    'src/views/scan/styles/ScanProjectDetail.scss',
    'src/views/scan/ScanProjectDetail.vue',
  ],
])

const PROJECT_WORKBENCH_DIRECT_COMPONENT_FILES = new Map([
  [
    'src/views/scan/components/scan-project/ScanProjectHeader.vue',
    'src/views/scan/ScanProjectDetail.vue',
  ],
])

const EXEMPT_PAGE_FILES = new Set([
  'src/views/agent/AgentDebug.vue',
  'src/views/workflow/WorkflowStudio.vue',
  'src/views/model/ModelPlayground.vue',
])

function createValidFixture() {
  return {
    files: new Map([
      [
        'src/styles/tokens/_layout.scss',
        `:root {
  --layout-breadcrumb-height: 40px;
  --layout-page-header-height-compact: 76px;
  --layout-page-header-height-standard: 96px;
  --layout-page-header-height-emphasis: 112px;
  --layout-page-header-leading-size: 52px;
  --layout-page-header-action-gap: 8px;
  --layout-page-header-tag-gap: 8px;
  --layout-page-header-art-opacity: 0.92;
  --layout-list-page-end: var(--layout-sidebar-inset-block);
  --layout-content-inline: 28px;
  --layout-page-start: 10px;
  --layout-page-end: 16px;
  --layout-page-gap: 10px;
  --layout-page-header-padding-block: 14px;
  --layout-page-header-padding-inline: 28px;
}

.project-workbench-page {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: var(--layout-page-start) var(--layout-content-inline) var(--layout-page-end);
  gap: var(--layout-page-gap);
}

.workbench-page--list {
  min-height: 100%;
  padding-bottom: var(--layout-list-page-end);
}

.workbench-page--list > .workbench-list-surface {
  flex: 1 0 auto;
  min-height: 0;
}

@media (max-width: 900px) {
  :root {
    --layout-content-inline: 16px;
    --layout-page-start: 10px;
    --layout-page-end: 16px;
    --layout-page-gap: 10px;
    --layout-page-header-padding-block: 18px;
    --layout-page-header-padding-inline: 18px;
  }
}

@media (max-width: 760px) {
  :root {
    --layout-content-inline: 12px;
    --layout-page-start: 8px;
    --layout-page-end: 12px;
    --layout-page-gap: 8px;
    --layout-page-header-padding-block: 16px;
    --layout-page-header-padding-inline: 16px;
  }
}`,
      ],
      [
        'src/components/common/WorkbenchPage.vue',
        `<script setup lang="ts">
type WorkbenchPageLayout = 'flow' | 'list'
const props = defineProps<{ layout?: WorkbenchPageLayout }>()
</script>
<template><main :class="['workbench-page', { 'workbench-page--list': props.layout === 'list' }]"><slot /></main></template>`,
      ],
      [
        'src/styles/index.scss',
        `.shared-control { padding: 8px; }`,
      ],
      [
        'src/views/layout/MainLayout.vue',
        `<template><main :data-layout-mode="layoutMode"><router-view /></main></template>
<style scoped>
.main-content { overflow-y: auto; }
.layout-standard > .main-content { display: grid; }
</style>`,
      ],
      [
        'src/views/StandardPage.vue',
        `<template><WorkbenchPage class="standard-page" layout="list"><section class="page-section workbench-list-surface"><el-row :gutter="16"><el-col /></el-row><div class="nested-toolbar" /></section></WorkbenchPage></template>
<style scoped>
.standard-page { display: grid; }
.standard-page__header { padding: 4px; }
.standard-page > .page-section { padding: 2px; margin: 0 0px; }
.page-section { display: block; }
.nested-toolbar { margin-bottom: 14px; }
</style>`,
      ],
      [
        'src/views/ProjectPage.vue',
        `<template><div class="project-page project-workbench-page workbench-page--list"><ProjectHeader /><section class="project-shell workbench-list-surface"><div class="internal-grid"><el-row :gutter="16"><el-col /></el-row></div></section></div></template>
<style scoped>
.project-page { min-height: 0; }
.project-shell { margin-top: 0; }
.internal-grid { display: grid; gap: 12px; margin-top: 12px; padding: 8px; }
.project-shell:has(.internal-grid) { border-width: 1px; }
</style>`,
      ],
      [
        'src/components/ProjectHeader.vue',
        `<template><header class="project-header" /></template>
<style scoped>.project-header { display: block; }</style>`,
      ],
      ['src/views/ProjectPage.figma.scss', '.internal-panel { margin-top: 12px; }'],
      [
        'src/views/StudioPage.vue',
        `<template><div class="studio-page" /></template>`,
      ],
      [
        'src/router/index.ts',
        `const routes = [
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/views/layout/MainLayout.vue'),
    children: [
      { path: '/standard', component: () => import('@/views/StandardPage.vue'), meta: { layoutMode: 'standard' } },
      { path: '/project', component: () => import('@/views/ProjectPage.vue'), meta: { layoutMode: 'project-workbench' } },
      { path: '/studio', component: () => import('@/views/StudioPage.vue'), meta: { layoutMode: 'studio' } },
      { path: '/legacy', redirect: '/standard', meta: { layoutMode: 'standard' } },
    ],
  },
]`,
      ],
    ]),
    standardPageFiles: new Set(['src/views/StandardPage.vue']),
    projectWorkbenchPageFiles: new Set(['src/views/ProjectPage.vue']),
    projectWorkbenchStyleFiles: new Map([
      ['src/views/ProjectPage.figma.scss', 'src/views/ProjectPage.vue'],
    ]),
    projectWorkbenchDirectComponentFiles: new Map([
      ['src/components/ProjectHeader.vue', 'src/views/ProjectPage.vue'],
    ]),
    exemptPageFiles: new Set(['src/views/StudioPage.vue']),
    listFillPageFiles: new Set(['src/views/StandardPage.vue', 'src/views/ProjectPage.vue']),
    expectedSetSizes: { standard: 1, projectWorkbench: 1, exempt: 1 },
    expectedListFillSize: 2,
  }
}

function mutateFile(fixture, relativePath, mutate) {
  const source = fixture.files.get(relativePath)
  fixture.files.set(relativePath, mutate(source))
  return fixture
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function compilerErrorMessage(error) {
  if (typeof error === 'string') return error
  return error?.message ?? String(error)
}

function parseSfc(relativePath, source, failures) {
  const result = parse(source, { filename: relativePath })
  for (const error of result.errors) {
    failures.push(`${relativePath} compiler error: ${compilerErrorMessage(error)}`)
  }
  return result.descriptor
}

function propertyName(node) {
  if (ts.isIdentifier(node) || ts.isStringLiteralLike(node)) return node.text
  return null
}

function objectProperty(object, name) {
  return object.properties.find(
    (property) => ts.isPropertyAssignment(property) && propertyName(property.name) === name,
  )
}

function importedComponentPath(node) {
  if (
    ts.isCallExpression(node) &&
    node.expression.kind === ts.SyntaxKind.ImportKeyword &&
    node.arguments.length === 1 &&
    ts.isStringLiteralLike(node.arguments[0])
  ) {
    const specifier = node.arguments[0].text
    return specifier.startsWith('@/') ? `src/${specifier.slice(2)}` : specifier
  }

  let result = null
  ts.forEachChild(node, (child) => {
    if (result === null) result = importedComponentPath(child)
  })
  return result
}

function parseRouteMetadata(source, failures) {
  const sourceFile = ts.createSourceFile(
    'src/router/index.ts',
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  for (const diagnostic of sourceFile.parseDiagnostics) {
    failures.push(
      `src/router/index.ts parser error: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')}`,
    )
  }

  let mainLayoutRoute = null
  function findMainLayoutRoute(node) {
    if (ts.isObjectLiteralExpression(node)) {
      const componentProperty = objectProperty(node, 'component')
      const component = componentProperty ? importedComponentPath(componentProperty.initializer) : null
      if (component === 'src/views/layout/MainLayout.vue') {
        mainLayoutRoute = node
        return
      }
    }
    if (mainLayoutRoute === null) ts.forEachChild(node, findMainLayoutRoute)
  }
  findMainLayoutRoute(sourceFile)
  if (mainLayoutRoute === null) {
    failures.push('src/router/index.ts is missing the MainLayout route')
    return []
  }

  const childrenProperty = objectProperty(mainLayoutRoute, 'children')
  if (!childrenProperty || !ts.isArrayLiteralExpression(childrenProperty.initializer)) {
    failures.push('src/router/index.ts MainLayout route is missing a children array')
    return []
  }

  const routes = []
  for (const child of childrenProperty.initializer.elements) {
    if (!ts.isObjectLiteralExpression(child)) continue
    const pathProperty = objectProperty(child, 'path')
    const routePath =
      pathProperty && ts.isStringLiteralLike(pathProperty.initializer)
        ? pathProperty.initializer.text
        : '<unknown>'
    const metaProperty = objectProperty(child, 'meta')
    const meta = metaProperty && ts.isObjectLiteralExpression(metaProperty.initializer)
      ? metaProperty.initializer
      : null
    const layoutModeProperty = meta ? objectProperty(meta, 'layoutMode') : null
    const layoutMode =
      layoutModeProperty && ts.isStringLiteralLike(layoutModeProperty.initializer)
        ? layoutModeProperty.initializer.text
        : null
    if (layoutMode === null) {
      failures.push(`src/router/index.ts MainLayout child ${routePath} is missing literal meta.layoutMode`)
    } else if (!REQUIRED_LAYOUT_MODES.has(layoutMode)) {
      failures.push(`src/router/index.ts MainLayout child ${routePath} has unsupported meta.layoutMode ${layoutMode}`)
    }

    const componentProperty = objectProperty(child, 'component')
    const component = componentProperty ? importedComponentPath(componentProperty.initializer) : null
    if (!component?.startsWith('src/views/') || component.startsWith('src/views/layout/')) continue
    routes.push({ component, layoutMode, path: routePath })
  }
  return routes
}

function cssRulesFromSource(source) {
  const rules = []
  const stack = []

  for (let index = 0; index < source.length; index += 1) {
    if (source[index] === '{') {
      let selectorStart = index - 1
      while (selectorStart >= 0 && !'{};'.includes(source[selectorStart])) selectorStart -= 1
      stack.push({ open: index, selector: source.slice(selectorStart + 1, index).trim() })
      continue
    }
    if (source[index] !== '}' || stack.length === 0) continue

    const block = stack.pop()
    const content = source.slice(block.open + 1, index)
    let depth = 0
    let declarations = ''
    for (const character of content) {
      if (character === '{') {
        depth += 1
      } else if (character === '}') {
        depth -= 1
      } else if (depth === 0) {
        declarations += character
      }
    }
    if (block.selector) rules.push({ selector: block.selector, declarations })
  }
  return rules
}

function cssRulesWithAncestors(source) {
  const rules = []
  const stack = []

  for (let index = 0; index < source.length; index += 1) {
    if (source[index] === '{') {
      let selectorStart = index - 1
      while (selectorStart >= 0 && !'{};'.includes(source[selectorStart])) selectorStart -= 1
      stack.push({ open: index, selector: source.slice(selectorStart + 1, index).trim() })
      continue
    }
    if (source[index] !== '}' || stack.length === 0) continue

    const block = stack.pop()
    const content = source.slice(block.open + 1, index)
    let depth = 0
    let declarations = ''
    for (const character of content) {
      if (character === '{') {
        depth += 1
      } else if (character === '}') {
        depth -= 1
      } else if (depth === 0) {
        declarations += character
      }
    }
    if (block.selector) {
      rules.push({
        selector: block.selector,
        declarations,
        ancestors: stack.map((ancestor) => ancestor.selector),
      })
    }
  }
  return rules
}

function layoutDeclarationScope(rule) {
  if (rule.selector.trim() === ':root' && rule.ancestors.length === 0) return 'base :root'
  return `${rule.ancestors.join(' ')} ${rule.selector}`.trim()
}

function cssRules(descriptor) {
  return descriptor.styles.flatMap((style) => cssRulesFromSource(style.content))
}

function rootClass(templateSource) {
  const root = templateSource.match(/<([A-Za-z][\w.-]*)\b([^>]*)>/)
  if (!root) return null
  const classAttribute = root[2].match(/\bclass\s*=\s*["']([^"']+)["']/)
  return classAttribute?.[1].trim().split(/\s+/)[0] ?? null
}

function findElementByTag(node, tag) {
  if (node?.type === 1 && node.tag === tag) return node
  for (const child of node?.children ?? []) {
    const match = findElementByTag(child, tag)
    if (match) return match
  }
  return null
}

function staticClassNames(element) {
  const classAttribute = element.props?.find(
    (property) => property.type === 6 && property.name === 'class' && property.value,
  )
  return classAttribute
    ? classAttribute.value.content.trim().split(/\s+/).filter(Boolean)
    : []
}

function staticAttributeValue(element, name) {
  const attribute = element?.props?.find(
    (property) => property.type === 6 && property.name === name && property.value,
  )
  return attribute?.value?.content
}

function templateRootElement(descriptor) {
  return descriptor.template?.ast.children.find((child) => child.type === 1) ?? null
}

function elementDirectChildren(element) {
  const children = []

  function collectEffectiveChildren(parent) {
    for (const child of parent?.children ?? []) {
      if (child.type !== 1) continue
      if (child.tag === 'template') {
        collectEffectiveChildren(child)
        continue
      }
      children.push(child)
    }
  }

  collectEffectiveChildren(element)
  return children
}

function directChildClasses(element) {
  const classes = new Set()
  for (const child of elementDirectChildren(element)) {
    for (const className of staticClassNames(child)) classes.add(className)
  }
  return classes
}

function workbenchDirectChildren(descriptor) {
  const workbenchPage = findElementByTag(descriptor.template?.ast, 'WorkbenchPage')
  const children = []

  function collectEffectiveChildren(parent) {
    for (const child of parent?.children ?? []) {
      if (child.type !== 1) continue
      if (child.tag === 'template') {
        collectEffectiveChildren(child)
        continue
      }
      children.push(child)
    }
  }

  collectEffectiveChildren(workbenchPage)
  return children
}

function workbenchDirectChildClasses(descriptor) {
  const classes = new Set()
  for (const child of workbenchDirectChildren(descriptor)) {
    for (const className of staticClassNames(child)) classes.add(className)
  }
  return classes
}

function elementProp(element, name) {
  return element.props?.find(
    (property) =>
      (property.type === 6 && property.name === name) ||
      (property.type === 7 &&
        property.name === 'bind' &&
        property.arg?.type === 4 &&
        property.arg.isStatic &&
        property.arg.content === name),
  )
}

function hasNonZeroElementProp(element, name) {
  const property = elementProp(element, name)
  if (!property) return false
  const source = property.type === 6 ? property.value?.content : property.exp?.content
  const value = source?.trim()
  if (!value) return true
  const numericValue = Number(value)
  return !Number.isFinite(numericValue) || numericValue !== 0
}

function matchingParenIndex(source, openIndex) {
  let depth = 0
  let quote = null
  let escaped = false
  for (let index = openIndex; index < source.length; index += 1) {
    const character = source[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
    } else if (character === '(') {
      depth += 1
    } else if (character === ')') {
      depth -= 1
      if (depth === 0) return index
    }
  }
  return -1
}

function splitSelectorList(selectorList) {
  const selectors = []
  let start = 0
  let roundDepth = 0
  let squareDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < selectorList.length; index += 1) {
    const character = selectorList[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
    } else if (character === '(') {
      roundDepth += 1
    } else if (character === ')') {
      roundDepth -= 1
    } else if (character === '[') {
      squareDepth += 1
    } else if (character === ']') {
      squareDepth -= 1
    } else if (character === ',' && roundDepth === 0 && squareDepth === 0) {
      selectors.push(selectorList.slice(start, index).trim())
      start = index + 1
    }
  }
  selectors.push(selectorList.slice(start).trim())
  return selectors.filter(Boolean)
}

function selectorSubjectCompound(selector) {
  let subjectStart = 0
  let roundDepth = 0
  let squareDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < selector.length; index += 1) {
    const character = selector[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
    } else if (character === '(') {
      roundDepth += 1
    } else if (character === ')') {
      roundDepth -= 1
    } else if (character === '[') {
      squareDepth += 1
    } else if (character === ']') {
      squareDepth -= 1
    } else if (
      roundDepth === 0 &&
      squareDepth === 0 &&
      (character === '>' || character === '+' || character === '~' || /\s/.test(character))
    ) {
      subjectStart = index + 1
    }
  }
  return selector.slice(subjectStart).trim()
}

function compoundTargetsClass(compound, className) {
  for (let index = 0; index < compound.length; index += 1) {
    if (compound[index] === '.') {
      const match = compound.slice(index + 1).match(/^[\w-]+/)
      if (match?.[0] === className) return true
      if (match) index += match[0].length
      continue
    }
    if (compound[index] !== ':') continue
    const pseudo = compound.slice(index + 1).match(/^[\w-]+/)?.[0]
    if (!pseudo) continue
    const openIndex = index + pseudo.length + 1
    if (compound[openIndex] !== '(') continue
    const closeIndex = matchingParenIndex(compound, openIndex)
    if (closeIndex === -1) return false
    if (['is', 'where', 'matches', 'global'].includes(pseudo)) {
      const argument = compound.slice(openIndex + 1, closeIndex)
      if (splitSelectorList(argument).some((selector) => selectorTargetsClass(selector, className))) {
        return true
      }
    }
    index = closeIndex
  }
  return false
}

function selectorTargetsClass(selector, className) {
  return compoundTargetsClass(selectorSubjectCompound(selector), className)
}

function selectorChainContainsClassOutsideFunctionalPseudos(selector, className) {
  let roundDepth = 0
  let squareDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < selector.length; index += 1) {
    const character = selector[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
    } else if (character === '(') {
      roundDepth += 1
    } else if (character === ')') {
      roundDepth -= 1
    } else if (character === '[') {
      squareDepth += 1
    } else if (character === ']') {
      squareDepth -= 1
    } else if (character === '.' && roundDepth === 0 && squareDepth === 0) {
      const match = selector.slice(index + 1).match(/^[\w-]+/)
      if (match?.[0] === className) return true
      if (match) index += match[0].length
    }
  }
  return false
}

function declarationEntries(declarations) {
  const entries = []
  const declaration = /(?:^|;)\s*([\w-]+)\s*:\s*([^;]+)/g
  for (const match of declarations.matchAll(declaration)) {
    entries.push({
      property: match[1],
      value: match[2].trim(),
    })
  }
  return entries
}

function hasRootPaddingOrGap(declarations) {
  return /(?:^|;)\s*(?:padding(?:-[\w-]+)?|(?:row-|column-)?gap)\s*:/i.test(declarations)
}

function selectorUsesMainLayoutHas(selector) {
  return /:global\(\s*\.main-layout\b/i.test(selector) && selector.includes(':has(')
}

function hasNegativeMargin(declarations) {
  const marginDeclaration = /(?:^|;)\s*margin(?:-[\w-]+)?\s*:\s*([^;]+)/gi
  for (const match of declarations.matchAll(marginDeclaration)) {
    if (/(?:^|[\s,(])-\s*(?:\d*\.?\d+|var\(|calc\()/i.test(match[1])) return true
  }
  return false
}

function hasNonZeroMargin(declarations) {
  const marginDeclaration = /(?:^|;)\s*margin(?:-[\w-]+)?\s*:\s*([^;]+)/gi
  const zeroValue = /^[+-]?(?:0+(?:\.0*)?|\.0+)(?:[a-z%]+)?$/i
  for (const match of declarations.matchAll(marginDeclaration)) {
    const value = match[1].replace(/\s*!important\s*$/i, '').trim()
    const parts = value.split(/\s+/).filter(Boolean)
    if (parts.length === 0 || parts.some((part) => !zeroValue.test(part))) return true
  }
  return false
}

function hasNonZeroPadding(declarations) {
  const paddingDeclaration = /(?:^|;)\s*padding(?:-[\w-]+)?\s*:\s*([^;]+)/gi
  const zeroValue = /^[+-]?(?:0+(?:\.0*)?|\.0+)(?:[a-z%]+)?$/i
  for (const match of declarations.matchAll(paddingDeclaration)) {
    const value = match[1].replace(/\s*!important\s*$/i, '').trim()
    const parts = value.split(/\s+/).filter(Boolean)
    if (parts.length === 0 || parts.some((part) => !zeroValue.test(part))) return true
  }
  return false
}

function hasImportantPadding(declarations) {
  return /(?:^|;)\s*padding(?:-[\w-]+)?\s*:[^;]*!important\b/i.test(declarations)
}

function selectorUsesAdjacentCards(selector) {
  const card = '(?:\\.el-card|\\.section-card|\\.card-grid)'
  return new RegExp(`${card}[^,{]*\\+[^,{]*${card}`).test(selector)
}

function unwrapTsExpression(node) {
  let current = node
  while (
    ts.isParenthesizedExpression(current) ||
    ts.isAsExpression(current) ||
    ts.isTypeAssertionExpression(current) ||
    ts.isNonNullExpression(current) ||
    ts.isSatisfiesExpression(current)
  ) {
    current = current.expression
  }
  return current
}

function bindingPropertyName(element) {
  const property = element.propertyName
  if (property === undefined && ts.isIdentifier(element.name)) return element.name.text
  if (property && (ts.isIdentifier(property) || ts.isStringLiteralLike(property))) {
    return property.text
  }
  if (
    property &&
    ts.isComputedPropertyName(property) &&
    ts.isStringLiteralLike(property.expression)
  ) {
    return property.expression.text
  }
  return null
}

function mainLayoutRouteProperties(descriptor, failures) {
  const scriptSource = [descriptor.script?.content, descriptor.scriptSetup?.content]
    .filter(Boolean)
    .join('\n')
  if (!scriptSource) return new Set()

  const sourceFile = ts.createSourceFile(
    'src/views/layout/MainLayout.vue.ts',
    scriptSource,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  for (const diagnostic of sourceFile.parseDiagnostics) {
    failures.push(
      `src/views/layout/MainLayout.vue script parser error: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')}`,
    )
  }

  const useRouteFactories = new Set()
  const vueRouterNamespaces = new Set()
  for (const statement of sourceFile.statements) {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteralLike(statement.moduleSpecifier) ||
      statement.moduleSpecifier.text !== 'vue-router'
    ) {
      continue
    }
    const bindings = statement.importClause?.namedBindings
    if (bindings && ts.isNamedImports(bindings)) {
      for (const element of bindings.elements) {
        const importedName = element.propertyName?.text ?? element.name.text
        if (importedName === 'useRoute') useRouteFactories.add(element.name.text)
      }
    } else if (bindings && ts.isNamespaceImport(bindings)) {
      vueRouterNamespaces.add(bindings.name.text)
    }
  }

  function isUseRouteCall(node) {
    const expression = unwrapTsExpression(node)
    if (!ts.isCallExpression(expression)) return false
    const callee = unwrapTsExpression(expression.expression)
    if (ts.isIdentifier(callee)) return useRouteFactories.has(callee.text)
    return (
      ts.isPropertyAccessExpression(callee) &&
      ts.isIdentifier(callee.expression) &&
      vueRouterNamespaces.has(callee.expression.text) &&
      callee.name.text === 'useRoute'
    )
  }

  const routeBindings = new Set()
  const forbiddenProperties = new Set()

  function isRouteExpression(node) {
    if (!node) return false
    const expression = unwrapTsExpression(node)
    return (
      isUseRouteCall(expression) ||
      (ts.isIdentifier(expression) && routeBindings.has(expression.text))
    )
  }

  function inspectRouteBinding(name, initializer) {
    if (!initializer || !isRouteExpression(initializer)) return false
    if (ts.isIdentifier(name)) {
      const previousSize = routeBindings.size
      routeBindings.add(name.text)
      return routeBindings.size !== previousSize
    }
    if (ts.isObjectBindingPattern(name)) {
      for (const element of name.elements) {
        const property = bindingPropertyName(element)
        if (property === 'name' || property === 'path') forbiddenProperties.add(property)
      }
    }
    return false
  }

  let changed = true
  while (changed) {
    changed = false
    function collectRouteBindings(node) {
      if (ts.isVariableDeclaration(node)) {
        if (inspectRouteBinding(node.name, node.initializer)) changed = true
      } else if (
        ts.isBinaryExpression(node) &&
        node.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
        ts.isIdentifier(node.left) &&
        isRouteExpression(node.right)
      ) {
        const previousSize = routeBindings.size
        routeBindings.add(node.left.text)
        if (routeBindings.size !== previousSize) changed = true
      }
      ts.forEachChild(node, collectRouteBindings)
    }
    collectRouteBindings(sourceFile)
  }

  function collectForbiddenAccesses(node) {
    if (
      ts.isPropertyAccessExpression(node) &&
      isRouteExpression(node.expression) &&
      (node.name.text === 'name' || node.name.text === 'path')
    ) {
      forbiddenProperties.add(node.name.text)
    } else if (ts.isElementAccessExpression(node) && isRouteExpression(node.expression)) {
      const argument = node.argumentExpression && unwrapTsExpression(node.argumentExpression)
      if (argument && ts.isStringLiteralLike(argument)) {
        if (argument.text === 'name' || argument.text === 'path') {
          forbiddenProperties.add(argument.text)
        }
      }
    }
    ts.forEachChild(node, collectForbiddenAccesses)
  }
  collectForbiddenAccesses(sourceFile)
  return forbiddenProperties
}

function selectorHasLayoutMode(selector, mode) {
  const escapedMode = escapeRegExp(mode)
  return new RegExp(
    `(?:layout[-_](?:mode[-_])?${escapedMode}|${escapedMode}[-_]layout|data-layout-mode[^\\]]*${escapedMode})`,
    'i',
  ).test(selector)
}

function validateContract(fixture) {
  const failures = []
  const tokenSource = fixture.files.get('src/styles/tokens/_layout.scss')
  if (tokenSource === undefined) {
    failures.push('missing src/styles/tokens/_layout.scss')
  } else {
    let compiledTokenSource = ''
    try {
      compiledTokenSource = compileString(tokenSource, { style: 'expanded' }).css
    } catch (error) {
      failures.push(
        `src/styles/tokens/_layout.scss compiler error: ${compilerErrorMessage(error)}`,
      )
    }

    const compiledLayoutRules = cssRulesWithAncestors(compiledTokenSource)
    for (const [token, expectedSequence] of REQUIRED_LAYOUT_TOKEN_SEQUENCES) {
      const actualSequence = compiledLayoutRules.flatMap((rule) =>
        declarationEntries(rule.declarations)
          .filter((declaration) => declaration.property === token)
          .map((declaration) => [layoutDeclarationScope(rule), declaration.value]),
      )
      if (JSON.stringify(actualSequence) !== JSON.stringify(expectedSequence)) {
        const formatSequence = (sequence) =>
          sequence.length === 0
            ? '(missing)'
            : sequence.map(([scope, value]) => `${scope}=${value}`).join(' -> ')
        failures.push(
          `${token} declaration sequence must be ${formatSequence(expectedSequence)}; found ${formatSequence(actualSequence)}`,
        )
      }
    }

    const projectRecipes = cssRulesFromSource(compiledTokenSource).filter(
      (rule) => rule.selector.trim() === '.project-workbench-page',
    )
    const projectRecipe = projectRecipes[0]
    if (projectRecipes.length === 0) {
      failures.push('src/styles/tokens/_layout.scss is missing the .project-workbench-page recipe')
    } else if (projectRecipes.length !== 1) {
      failures.push(
        `.project-workbench-page recipe must be declared exactly once, found ${projectRecipes.length}`,
      )
    } else {
      const recipeDeclarations = declarationEntries(projectRecipe.declarations)
      const requiredProperties = new Set(REQUIRED_PROJECT_WORKBENCH_RECIPE.keys())
      for (const declaration of recipeDeclarations) {
        if (!requiredProperties.has(declaration.property)) {
          failures.push(
            `.project-workbench-page recipe has unexpected declaration ${declaration.property}`,
          )
        }
      }
      for (const [property, expectedValue] of REQUIRED_PROJECT_WORKBENCH_RECIPE) {
        const matchingDeclarations = recipeDeclarations.filter(
          (declaration) => declaration.property === property,
        )
        if (matchingDeclarations.length !== 1) {
          failures.push(
            `.project-workbench-page recipe ${property} must appear exactly once, found ${matchingDeclarations.length}`,
          )
          continue
        }
        const actualValue = matchingDeclarations[0].value
        if (actualValue !== expectedValue) {
          failures.push(
            `.project-workbench-page recipe ${property} must be ${expectedValue}, found ${actualValue}`,
          )
        }
      }
      if (/(?:^|;)\s*(?:(?:min-|max-)?height|overflow(?:-[xy])?)\s*:/i.test(projectRecipe.declarations)) {
        failures.push('.project-workbench-page recipe must not own height or overflow')
      }
    }

    for (const [selector, expectedRecipe] of [
      ['.workbench-page--list', REQUIRED_LIST_PAGE_RECIPE],
      ['.workbench-page--list > .workbench-list-surface', REQUIRED_LIST_SURFACE_RECIPE],
    ]) {
      const matchingRules = cssRulesFromSource(compiledTokenSource).filter(
        (rule) => rule.selector.trim() === selector,
      )
      if (matchingRules.length !== 1) {
        failures.push(`${selector} recipe must be declared exactly once, found ${matchingRules.length}`)
        continue
      }
      const declarations = declarationEntries(matchingRules[0].declarations)
      for (const [property, expectedValue] of expectedRecipe) {
        const actual = declarations.filter((declaration) => declaration.property === property)
        if (actual.length !== 1 || actual[0].value !== expectedValue) {
          failures.push(
            `${selector} ${property} must be ${expectedValue}, found ${actual.map((item) => item.value).join(', ') || '(missing)'}`,
          )
        }
      }
      if (/(?:^|;)\s*(?:height|max-height|overflow(?:-[xy])?)\s*:/i.test(matchingRules[0].declarations)) {
        failures.push(`${selector} must not own fixed height or overflow`)
      }
    }
  }

  const workbenchPageSource = fixture.files.get('src/components/common/WorkbenchPage.vue')
  if (workbenchPageSource === undefined) {
    failures.push('missing src/components/common/WorkbenchPage.vue')
  } else {
    if (!workbenchPageSource.includes("type WorkbenchPageLayout = 'flow' | 'list'")) {
      failures.push('WorkbenchPage must expose the flow/list layout contract')
    }
    if (!workbenchPageSource.includes("'workbench-page--list': props.layout === 'list'")) {
      failures.push('WorkbenchPage must map layout=list to workbench-page--list')
    }
  }

  const globalStyleSource = fixture.files.get('src/styles/index.scss')
  if (globalStyleSource === undefined) {
    failures.push('missing src/styles/index.scss')
  } else {
    for (const rule of cssRulesFromSource(globalStyleSource)) {
      for (const selector of splitSelectorList(rule.selector)) {
        if (selectorTargetsClass(selector, 'page-container') && hasNonZeroPadding(rule.declarations)) {
          failures.push('src/styles/index.scss .page-container outer padding is forbidden')
        }
        for (const className of ['page-header', 'section-card']) {
          if (selectorTargetsClass(selector, className) && hasNonZeroMargin(rule.declarations)) {
            failures.push(`src/styles/index.scss .${className} outer margin is forbidden`)
          }
        }
        if (selector.trim() === '.filter-bar' && hasNonZeroMargin(rule.declarations)) {
          failures.push('src/styles/index.scss .filter-bar outer margin is forbidden')
        }
        if (
          selectorChainContainsClassOutsideFunctionalPseudos(selector, 'main-content') &&
          selector.includes(':has(')
        ) {
          failures.push('src/styles/index.scss .main-content parent :has() layout inference is forbidden')
        }
        if (
          selectorChainContainsClassOutsideFunctionalPseudos(selector, 'main-content') &&
          hasImportantPadding(rule.declarations)
        ) {
          failures.push('src/styles/index.scss .main-content business-page padding injection is forbidden')
        }
        if (selectorUsesAdjacentCards(selector) && hasNonZeroMargin(rule.declarations)) {
          failures.push('src/styles/index.scss adjacent-card outer margin is forbidden')
        }
      }
    }
  }

  for (const [relativePath, source] of fixture.files) {
    for (const token of FORBIDDEN_LEGACY_SPACING_TOKENS) {
      if (source.includes(token)) failures.push(`${relativePath} ${token} is forbidden`)
    }
  }

  const descriptors = new Map()
  for (const [relativePath, source] of fixture.files) {
    if (relativePath.endsWith('.vue')) {
      descriptors.set(relativePath, parseSfc(relativePath, source, failures))
    }
  }


  const listFillPageFiles = fixture.listFillPageFiles ?? LIST_FILL_PAGE_FILES
  const expectedListFillSize = fixture.expectedListFillSize ?? 23
  if (listFillPageFiles.size !== expectedListFillSize) {
    failures.push(
      `list-fill source-of-truth count must be ${expectedListFillSize}, found ${listFillPageFiles.size}`,
    )
  }
  for (const relativePath of listFillPageFiles) {
    const descriptor = descriptors.get(relativePath)
    if (!descriptor) {
      failures.push(`${relativePath} list-fill page is missing`)
      continue
    }
    const root = templateRootElement(descriptor)
    if (root?.tag === 'WorkbenchPage') {
      if (staticAttributeValue(root, 'layout') !== 'list') {
        failures.push(`${relativePath} list-fill WorkbenchPage must declare layout="list"`)
      }
    } else if (!staticClassNames(root).includes('workbench-page--list')) {
      failures.push(`${relativePath} list-fill project root must include workbench-page--list`)
    }
    const fillSurfaces = elementDirectChildren(root).filter((child) =>
      staticClassNames(child).includes('workbench-list-surface'),
    )
    if (fillSurfaces.length === 0) {
      failures.push(`${relativePath} list-fill page must expose a direct workbench-list-surface`)
    }
  }

  const mainLayoutDescriptor = descriptors.get('src/views/layout/MainLayout.vue')
  if (mainLayoutDescriptor === undefined) {
    failures.push('missing src/views/layout/MainLayout.vue')
  } else {
    for (const property of mainLayoutRouteProperties(mainLayoutDescriptor, failures)) {
      failures.push(
        `src/views/layout/MainLayout.vue MainLayout route.${property} shell inference is forbidden`,
      )
    }
    const mainContentOwnsVerticalScroll = cssRules(mainLayoutDescriptor).some((rule) =>
      splitSelectorList(rule.selector).some(
        (selector) =>
          selector.trim() === '.main-content' &&
          /(?:^|;)\s*overflow-y\s*:\s*auto\s*(?:;|$)/i.test(rule.declarations),
      ),
    )
    if (!mainContentOwnsVerticalScroll) {
      failures.push('src/views/layout/MainLayout.vue .main-content must own overflow-y: auto')
    }
  }

  const routerSource = fixture.files.get('src/router/index.ts')
  if (routerSource === undefined) {
    failures.push('missing src/router/index.ts')
  } else {
    const routes = parseRouteMetadata(routerSource, failures)
    const routeComponents = new Set(routes.map((route) => route.component))
    const sourceSets = [
      ['standard', fixture.standardPageFiles, fixture.expectedSetSizes.standard],
      ['project-workbench', fixture.projectWorkbenchPageFiles, fixture.expectedSetSizes.projectWorkbench],
      ['exempt', fixture.exemptPageFiles, fixture.expectedSetSizes.exempt],
    ]
    for (const [label, sourceSet, expectedSize] of sourceSets) {
      if (sourceSet.size !== expectedSize) {
        failures.push(
          `${label} source-of-truth count must be ${expectedSize}, found ${sourceSet.size}`,
        )
      }
    }

    const classifiedComponents = new Set(sourceSets.flatMap(([, sourceSet]) => [...sourceSet]))
    for (const component of classifiedComponents) {
      const classifications = sourceSets.filter(([, sourceSet]) => sourceSet.has(component)).length
      if (classifications > 1) {
        failures.push(`${component} appears in multiple page-rhythm source-of-truth sets`)
      }
      if (!routeComponents.has(component)) {
        failures.push(`${component} is not a MainLayout child component route`)
      }
    }

    for (const route of routes) {
      const classifications = [
        fixture.standardPageFiles.has(route.component),
        fixture.projectWorkbenchPageFiles.has(route.component),
        fixture.exemptPageFiles.has(route.component),
      ].filter(Boolean).length
      if (classifications === 0) {
        failures.push(`${route.component} is not classified by the page-rhythm source-of-truth sets`)
      }
      if (route.layoutMode === null || !REQUIRED_LAYOUT_MODES.has(route.layoutMode)) continue
      if (fixture.standardPageFiles.has(route.component) && route.layoutMode !== 'standard') {
        failures.push(`${route.component} must use meta.layoutMode standard`)
      }
      if (
        fixture.projectWorkbenchPageFiles.has(route.component) &&
        route.layoutMode !== 'project-workbench'
      ) {
        failures.push(`${route.component} must use meta.layoutMode project-workbench`)
      }
      if (
        fixture.exemptPageFiles.has(route.component) &&
        route.layoutMode !== 'edge-to-edge' &&
        route.layoutMode !== 'studio'
      ) {
        failures.push(`${route.component} exempt page must use meta.layoutMode edge-to-edge or studio`)
      }
    }
  }

  for (const relativePath of fixture.exemptPageFiles) {
    const descriptor = descriptors.get(relativePath)
    if (!descriptor) {
      failures.push(`${relativePath} is missing`)
      continue
    }
    const templateSource = descriptor.template?.content ?? ''
    const exemptRoot = templateRootElement(descriptor)
    if (/<WorkbenchPage\b/.test(templateSource)) {
      failures.push(`${relativePath} exempt page must not use WorkbenchPage`)
    }
    if (exemptRoot && staticClassNames(exemptRoot).includes('project-workbench-page')) {
      failures.push(`${relativePath} exempt page must not use project-workbench-page`)
    }
  }

  for (const relativePath of fixture.standardPageFiles) {
    const descriptor = descriptors.get(relativePath)
    if (!descriptor) {
      failures.push(`${relativePath} is missing`)
      continue
    }
    const templateSource = descriptor.template?.content ?? ''
    if (!/<WorkbenchPage\b/.test(templateSource)) {
      failures.push(`${relativePath} standard page must use WorkbenchPage`)
    }
    const pageRootClass = rootClass(templateSource)
    const directChildren = workbenchDirectChildren(descriptor)
    const directChildClasses = workbenchDirectChildClasses(descriptor)
    for (const child of directChildren) {
      if (child.tag === 'el-row' && hasNonZeroElementProp(child, 'gutter')) {
        failures.push(`${relativePath} WorkbenchPage direct child el-row gutter is forbidden`)
      }
    }
    for (const rule of cssRules(descriptor)) {
      if (
        pageRootClass &&
        splitSelectorList(rule.selector).some((selector) =>
          selectorTargetsClass(selector, pageRootClass),
        ) &&
        /(?:^|;)\s*padding(?:-[\w-]+)?\s*:/.test(rule.declarations)
      ) {
        failures.push(`${relativePath} standard page root padding is forbidden`)
      }
      if (hasNonZeroMargin(rule.declarations)) {
        for (const className of directChildClasses) {
          if (
            splitSelectorList(rule.selector).some((selector) =>
              selectorTargetsClass(selector, className),
            )
          ) {
            failures.push(`${relativePath} WorkbenchPage direct child .${className} margin is forbidden`)
          }
        }
      }
    }
  }

  for (const relativePath of fixture.projectWorkbenchPageFiles) {
    const descriptor = descriptors.get(relativePath)
    if (!descriptor) {
      failures.push(`${relativePath} is missing`)
      continue
    }
    const projectRoot = templateRootElement(descriptor)
    const projectRootClasses = staticClassNames(projectRoot)
    if (!projectRootClasses.includes('project-workbench-page')) {
      failures.push(`${relativePath} project page root must include project-workbench-page`)
    }

    const directChildren = elementDirectChildren(projectRoot)
    const directChildClassNames = directChildClasses(projectRoot)
    for (const child of directChildren) {
      if (child.tag === 'el-row' && hasNonZeroElementProp(child, 'gutter')) {
        failures.push(`${relativePath} project page direct child el-row gutter is forbidden`)
      }
    }

    const styleSources = [{ relativePath, rules: cssRules(descriptor) }]
    for (const [stylePath, pagePath] of fixture.projectWorkbenchStyleFiles ?? []) {
      if (pagePath !== relativePath) continue
      const source = fixture.files.get(stylePath)
      if (source === undefined) {
        failures.push(`${stylePath} is missing`)
      } else {
        styleSources.push({ relativePath: stylePath, rules: cssRulesFromSource(source) })
      }
    }
    for (const [componentPath, pagePath] of fixture.projectWorkbenchDirectComponentFiles ?? []) {
      if (pagePath !== relativePath) continue
      const componentDescriptor = descriptors.get(componentPath)
      if (!componentDescriptor) {
        failures.push(`${componentPath} is missing`)
        continue
      }
      const componentTag = path.basename(componentPath, '.vue')
      if (!directChildren.some((child) => child.tag === componentTag)) {
        failures.push(`${componentPath} must be a direct child component of ${relativePath}`)
        continue
      }
      const componentRoot = templateRootElement(componentDescriptor)
      if (!componentRoot) {
        failures.push(`${componentPath} is missing a template root`)
        continue
      }
      for (const className of staticClassNames(componentRoot)) directChildClassNames.add(className)
      styleSources.push({ relativePath: componentPath, rules: cssRules(componentDescriptor) })
    }

    for (const styleSource of styleSources) {
      for (const rule of styleSource.rules) {
        if (selectorUsesMainLayoutHas(rule.selector)) {
          failures.push(
            `${styleSource.relativePath} project page must not use :global(.main-layout ... :has()) parent control`,
          )
        }

        const targetsProjectRoot = splitSelectorList(rule.selector).some((selector) =>
          projectRootClasses.some((className) => selectorTargetsClass(selector, className)),
        )
        if (targetsProjectRoot && hasRootPaddingOrGap(rule.declarations)) {
          failures.push(`${styleSource.relativePath} project page root padding/gap is forbidden`)
        }
        if (targetsProjectRoot && hasNegativeMargin(rule.declarations)) {
          failures.push(`${styleSource.relativePath} has forbidden negative shell compensation`)
        }
        if (!hasNonZeroMargin(rule.declarations)) continue
        for (const className of directChildClassNames) {
          if (
            splitSelectorList(rule.selector).some((selector) =>
              selectorTargetsClass(selector, className),
            )
          ) {
            failures.push(
              `${styleSource.relativePath} project page direct child .${className} margin is forbidden`,
            )
          }
        }
      }
    }
  }

  for (const [relativePath, descriptor] of descriptors) {
    for (const rule of cssRules(descriptor)) {
      if (
        selectorHasLayoutMode(rule.selector, 'standard') &&
        selectorHasLayoutMode(rule.selector, 'studio')
      ) {
        failures.push(`${relativePath} standard selector must not match studio layout`)
      }
    }
  }

  return failures
}

const MUTATION_PROOFS = [
  {
    name: 'trailing duplicate layout token override',
    expected: '--layout-page-gap declaration sequence',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        `${source}\n:root { --layout-page-gap: 99px; }`,
      ),
  },
  {
    name: 'missing token',
    expected: '--layout-page-gap',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace('  --layout-page-gap: 10px;\n', ''),
      ),
  },
  {
    name: 'wrong token value',
    expected: '--layout-page-gap',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace('--layout-page-gap: 10px', '--layout-page-gap: 12px'),
      ),
  },
  {
    name: 'missing project-workbench recipe',
    expected: 'missing the .project-workbench-page recipe',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace(/\n\.project-workbench-page \{[\s\S]*?\n\}/, ''),
      ),
  },
  {
    name: 'project-workbench recipe owns height',
    expected: 'must not own height or overflow',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace('  gap: var(--layout-page-gap);', '  gap: var(--layout-page-gap);\n  min-height: 100%;'),
      ),
  },
  {
    name: 'project-workbench recipe has extra margin declaration',
    expected: 'unexpected declaration margin',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace('  gap: var(--layout-page-gap);', '  gap: var(--layout-page-gap);\n  margin: 0;'),
      ),
  },
  {
    name: 'project-workbench recipe has trailing duplicate gap override',
    expected: 'gap must appear exactly once',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace('  gap: var(--layout-page-gap);', '  gap: var(--layout-page-gap);\n  gap: 99px;'),
      ),
  },
  {
    name: 'missing list-page recipe',
    expected: '.workbench-page--list recipe must be declared exactly once, found 0',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/tokens/_layout.scss', (source) =>
        source.replace(/\n\.workbench-page--list \{[\s\S]*?\n\}/, ''),
      ),
  },
  {
    name: 'standard list page loses layout opt-in',
    expected: 'list-fill WorkbenchPage must declare layout="list"',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source.replace(' layout="list"', ''),
      ),
  },
  {
    name: 'project list page loses fill surface',
    expected: 'list-fill page must expose a direct workbench-list-surface',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('project-shell workbench-list-surface', 'project-shell'),
      ),
  },
  {
    name: 'illegal layout mode',
    expected: 'unsupported',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/router/index.ts', (source) =>
        source.replace("layoutMode: 'standard'", "layoutMode: 'unsupported'"),
      ),
  },
  {
    name: 'redirect route without layout mode',
    expected: 'MainLayout child /legacy is missing literal meta.layoutMode',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/router/index.ts', (source) =>
        source.replace(
          "{ path: '/legacy', redirect: '/standard', meta: { layoutMode: 'standard' } }",
          "{ path: '/legacy', redirect: '/standard' }",
        ),
      ),
  },
  {
    name: 'standard page without WorkbenchPage',
    expected: 'WorkbenchPage',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source.replace('<WorkbenchPage', '<main').replace('</WorkbenchPage>', '</main>'),
      ),
  },
  {
    name: 'global page-container owns non-zero outer padding',
    expected: '.page-container outer padding is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.page-container { padding: 24px; }`,
      ),
  },
  {
    name: 'global page-header owns outer margin',
    expected: '.page-header outer margin is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.page-header { margin-bottom: 24px; }`,
      ),
  },
  {
    name: 'global section-card owns outer margin',
    expected: '.section-card outer margin is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.section-card { margin-bottom: 20px; }`,
      ),
  },
  {
    name: 'global filter-bar owns outer margin',
    expected: '.filter-bar outer margin is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.filter-bar { margin-bottom: 20px; }`,
      ),
  },
  {
    name: 'main-content injects important business-page padding',
    expected: '.main-content business-page padding injection is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.main-content > .business-page { padding: 24px !important; }`,
      ),
  },
  {
    name: 'main-content uses parent has layout inference',
    expected: '.main-content parent :has() layout inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.main-content:has(.project-page) { padding: 0; }`,
      ),
  },
  {
    name: 'adjacent cards own outer margin',
    expected: 'adjacent-card outer margin is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.section-card + .section-card { margin-top: 18px; }`,
      ),
  },
  {
    name: 'MainLayout infers shell from route name',
    expected: 'MainLayout route.name shell inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
const shell = route.name
</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout infers shell from route path',
    expected: 'MainLayout route.path shell inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
const shell = route.path
</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout infers shell through a useRoute binding alias',
    expected: 'MainLayout route.name shell inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute as getRoute } from 'vue-router'
const activeRoute = getRoute()
const routeAlias = activeRoute
const shell = routeAlias.name
</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout infers shell through useRoute destructuring',
    expected: 'MainLayout route.path shell inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
const { path: shellPath } = useRoute()
</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout infers shell through element access',
    expected: 'MainLayout route.name shell inference is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
const shell = route['name']
</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout loses the shared vertical scroll container',
    expected: '.main-content must own overflow-y: auto',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace('overflow-y: auto', 'overflow-y: hidden'),
      ),
  },
  ...[
    '--reachai-workbench-page-padding',
    '--reachai-workbench-title-gap',
    '--reachai-workbench-breadcrumb-height',
    '--reachai-workbench-title-padding',
  ].map((token) => ({
    name: `legacy spacing token ${token}`,
    expected: `${token} is forbidden`,
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.figma.scss', (source) =>
        `${source}\n.legacy-spacing { padding: var(${token}); }`,
      ),
  })),
  {
    name: 'standard page root padding',
    expected: 'root padding',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source.replace('.standard-page { display: grid; }', '.standard-page { display: grid; padding: 24px; }'),
      ),
  },
  {
    name: 'direct page-section margin',
    expected: 'page-section margin',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source.replace('.page-section { display: block; }', '.page-section { display: block; margin-top: 12px; }'),
      ),
  },
  {
    name: 'WorkbenchPage direct child margin',
    expected: 'direct child .toolbar margin',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source
          .replace(
            '</WorkbenchPage>',
            '<div class="toolbar" /></WorkbenchPage>',
          )
          .replace('</style>', '.toolbar { margin-bottom: 14px; }\n</style>'),
      ),
  },
  {
    name: 'WorkbenchPage direct el-row gutter runtime margin',
    expected: 'direct child el-row gutter',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/StandardPage.vue', (source) =>
        source.replace(
          '<section class="page-section workbench-list-surface"><el-row :gutter="16"><el-col /></el-row><div class="nested-toolbar" /></section>',
          '<el-row :gutter="16"><el-col /></el-row>',
        ),
      ),
  },
  {
    name: 'project page missing public recipe class',
    expected: 'root must include project-workbench-page',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('project-page project-workbench-page', 'project-page'),
      ),
  },
  {
    name: 'project page root padding',
    expected: 'root padding/gap',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('.project-page { min-height: 0; }', '.project-page { min-height: 0; padding: 24px; }'),
      ),
  },
  {
    name: 'project page root gap',
    expected: 'root padding/gap',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('.project-page { min-height: 0; }', '.project-page { min-height: 0; gap: 12px; }'),
      ),
  },
  {
    name: 'project page MainLayout :has() parent control',
    expected: ':global(.main-layout ... :has())',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.figma.scss', (source) =>
        `${source}\n:global(.main-layout.registry-shell:has(.project-page) .main-content) { padding: 0; }`,
      ),
  },
  {
    name: 'negative shell compensation',
    expected: 'negative shell compensation',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('.project-page { min-height: 0; }', '.project-page { min-height: 0; margin-top: -10px; }'),
      ),
  },
  {
    name: 'project page direct child margin',
    expected: 'direct child .project-shell margin',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace('.project-shell { margin-top: 0; }', '.project-shell { margin-top: 12px; }'),
      ),
  },
  {
    name: 'project page direct child component root margin',
    expected: 'src/components/ProjectHeader.vue project page direct child .project-header margin is forbidden',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/components/ProjectHeader.vue', (source) =>
        source.replace('display: block;', 'display: block; margin-bottom: 16px;'),
      ),
  },
  {
    name: 'project page direct el-row gutter runtime margin',
    expected: 'project page direct child el-row gutter',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/ProjectPage.vue', (source) =>
        source.replace(
          '<section class="project-shell workbench-list-surface"><div class="internal-grid"><el-row :gutter="16"><el-col /></el-row></div></section>',
          '<el-row :gutter="16"><el-col /></el-row>',
        ),
      ),
  },
  {
    name: 'standard selector matching Studio',
    expected: 'studio',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '.layout-standard > .main-content',
          '.layout-standard > .main-content, .layout-studio > .main-content',
        ),
      ),
  },
  {
    name: 'exempt page adopts WorkbenchPage spacing',
    expected: 'exempt page must not use WorkbenchPage',
    mutate: (fixture) =>
      mutateFile(
        fixture,
        'src/views/StudioPage.vue',
        (source) => source.replace('<div class="studio-page" />', '<WorkbenchPage><div class="studio-page" /></WorkbenchPage>'),
      ),
  },
  {
    name: 'wrong source-of-truth count',
    expected: 'source-of-truth count',
    mutate: (fixture) => {
      fixture.standardPageFiles.add('src/views/ExtraPage.vue')
      return fixture
    },
  },
  {
    name: 'ghost source-of-truth member',
    expected: 'is not a MainLayout child component route',
    mutate: (fixture) => {
      fixture.standardPageFiles.delete('src/views/StandardPage.vue')
      fixture.standardPageFiles.add('src/views/GhostPage.vue')
      return fixture
    },
  },
  {
    name: 'overlapping source-of-truth member',
    expected: 'appears in multiple page-rhythm source-of-truth sets',
    mutate: (fixture) => {
      fixture.projectWorkbenchPageFiles.add('src/views/StandardPage.vue')
      return fixture
    },
  },
]

const SAFE_PROOFS = [
  {
    name: ':not() page-container argument is not the selector subject',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.shell:not(.page-container) { padding: 24px; }`,
      ),
  },
  {
    name: 'multi-argument :not() does not split into a false subject',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.shell:not(.other, .page-container) { padding: 24px; }`,
      ),
  },
  {
    name: ':has() page-container argument is not the selector subject',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.shell:has(.page-container) { padding: 24px; }`,
      ),
  },
  {
    name: ':has() main-content argument does not imply a main-content selector chain',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.shell:has(.main-content) { padding: 0; }`,
      ),
  },
  {
    name: ':not() main-content argument does not imply a main-content selector chain',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/styles/index.scss', (source) =>
        `${source}\n.shell:not(.main-content) { padding: 24px !important; }`,
      ),
  },
  {
    name: 'MainLayout route.path comment and string are inert',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<!-- route.path is documentation, not shell inference -->
<script setup lang="ts">const example = 'route.path'</script>`,
        ),
      ),
  },
  {
    name: 'MainLayout route.meta access remains allowed',
    mutate: (fixture) =>
      mutateFile(fixture, 'src/views/layout/MainLayout.vue', (source) =>
        source.replace(
          '</template>',
          `</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
const mode = route.meta.layoutMode
</script>`,
        ),
      ),
  },
]

function runSelfTests() {
  const baselineFailures = validateContract(createValidFixture())
  if (baselineFailures.length > 0) {
    console.error(`not ok - valid synthetic fixture: ${baselineFailures.join('; ')}`)
    return 1
  }

  for (const proof of SAFE_PROOFS) {
    const failures = validateContract(proof.mutate(createValidFixture()))
    if (failures.length > 0) {
      console.error(`not ok - safe ${proof.name}: ${failures.join('; ')}`)
      return 1
    }
    console.log(`ok - safe ${proof.name}`)
  }

  for (const proof of MUTATION_PROOFS) {
    const failures = validateContract(proof.mutate(createValidFixture()))
    if (!failures.some((failure) => failure.includes(proof.expected))) {
      console.error(`not ok - ${proof.name}: mutation was not rejected`)
      return 1
    }
    console.log(`ok - ${proof.name}`)
  }

  console.log(
    `All ${MUTATION_PROOFS.length} page-rhythm mutation proofs and ${SAFE_PROOFS.length} safe proofs passed.`,
  )
  return 0
}

function collectSourceFiles(directory, rootDirectory, files) {
  if (!fs.existsSync(directory)) return
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolutePath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      collectSourceFiles(absolutePath, rootDirectory, files)
    } else if (entry.isFile() && /\.(?:vue|scss|css|ts)$/.test(entry.name)) {
      const relativePath = path.relative(rootDirectory, absolutePath).replaceAll('\\', '/')
      files.set(relativePath, fs.readFileSync(absolutePath, 'utf8'))
    }
  }
}

function createRepositoryFixture() {
  const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
  const rootDirectory = path.resolve(scriptDirectory, '..')
  const files = new Map()
  for (const relativePath of [
    'src/styles/tokens/_layout.scss',
    'src/styles/index.scss',
    'src/components/common/WorkbenchPage.vue',
    'src/views/layout/MainLayout.vue',
    'src/router/index.ts',
    ...PROJECT_WORKBENCH_STYLE_FILES.keys(),
  ]) {
    const absolutePath = path.join(rootDirectory, ...relativePath.split('/'))
    if (fs.existsSync(absolutePath)) {
      files.set(relativePath, fs.readFileSync(absolutePath, 'utf8'))
    }
  }
  collectSourceFiles(path.join(rootDirectory, 'src'), rootDirectory, files)
  return {
    files,
    standardPageFiles: STANDARD_PAGE_FILES,
    projectWorkbenchPageFiles: PROJECT_WORKBENCH_PAGE_FILES,
    projectWorkbenchStyleFiles: PROJECT_WORKBENCH_STYLE_FILES,
    projectWorkbenchDirectComponentFiles: PROJECT_WORKBENCH_DIRECT_COMPONENT_FILES,
    exemptPageFiles: EXEMPT_PAGE_FILES,
    listFillPageFiles: LIST_FILL_PAGE_FILES,
    expectedSetSizes: { standard: 41, projectWorkbench: 8, exempt: 3 },
    expectedListFillSize: 23,
  }
}

function runRepositoryCheck() {
  const failures = validateContract(createRepositoryFixture())
  if (failures.length > 0) {
    console.error('ReachAI page-rhythm contract is not aligned:')
    for (const failure of failures) console.error(`- ${failure}`)
    return 1
  }
  console.log('ReachAI page-rhythm contract is aligned.')
  return 0
}

if (process.argv.includes('--self-test')) {
  process.exitCode = runSelfTests()
} else {
  process.exitCode = runRepositoryCheck()
}
