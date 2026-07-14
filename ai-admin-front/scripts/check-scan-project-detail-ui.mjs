import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const scanDetailSource = readFileSync(join(process.cwd(), 'src/views/scan/ScanProjectDetail.vue'), 'utf8')
const scanDetailStyle = readFileSync(join(process.cwd(), 'src/views/scan/styles/ScanProjectDetail.scss'), 'utf8')
const headerSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectHeader.vue'),
  'utf8',
)
const addInterfaceDialogSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectAddInterfaceDialog.vue'),
  'utf8',
)
const opsDrawerSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectOpsDrawer.vue'),
  'utf8',
)
const overviewSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectOverviewCards.vue'),
  'utf8',
)
const metricIconBgSource = readFileSync(join(process.cwd(), 'src/components/common/MetricIconBg.vue'), 'utf8')
const modulesPanelSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectModulesPanel.vue'),
  'utf8',
)
const apiGraphPanelSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectApiGraphPanel.vue'),
  'utf8',
)
const scanProjectApiSource = readFileSync(join(process.cwd(), 'src/api/scanProject.ts'), 'utf8')
const uiStateSource = readFileSync(join(process.cwd(), 'src/views/scan/composables/useScanProjectUiState.ts'), 'utf8')
const summarySource = readFileSync(join(process.cwd(), 'src/views/scan/composables/useScanProjectSummary.ts'), 'utf8')

assert.match(
  scanDetailSource,
  /class="registry-workbench-page api-catalog-workbench project-workbench-page"/,
  'API catalog detail page should opt into the shared registry workbench shell',
)
assert.match(
  headerSource,
  /class="project-hero api-catalog-hero"/,
  'API catalog detail header should reuse the project-management hero shape',
)
assert.doesNotMatch(
  headerSource,
  /API 能力治理工作台/,
  'API catalog detail header should not render the extra workbench pill',
)
assert.match(
  headerSource,
  /将业务系统 API 接入 ReachAI/,
  'API catalog detail header should explain the API-to-Tool-to-Agent product loop',
)
assert.match(
  headerSource,
  /<h1>API 管理<\/h1>/,
  'API catalog detail header title should describe the page task rather than the current project',
)
assert.doesNotMatch(
  headerSource,
  /<h1>{{\s*project\?\.name/,
  'API catalog detail header should not use project name as the page title',
)
assert.match(
  headerSource,
  /project-context-tag[\s\S]*项目：/,
  'API catalog detail header should keep the project name as secondary context',
)
assert.match(
  headerSource,
  /@command="onMoreCommand"/,
  'API catalog detail header should collect secondary actions into a more-actions menu',
)
for (const label of ['AI 语义生成', '检查 Tool 关联', '维护动作']) {
  assert.match(headerSource, new RegExp(label), `Header more-actions menu should preserve ${label}`)
}
assert.doesNotMatch(
  headerSource,
  /强制重生成|扫描解析规则|运维动作|command="forceGenerate"|command="scanRules"|command="refresh"/,
  'Header more-actions menu should not keep generation, scan-rule, ops, or refresh legacy commands',
)
assert.match(
  headerSource,
  /stageAdvice\.secondaryAction === 'refresh'[\s\S]*:icon="Refresh"[\s\S]*aria-label="刷新同步状态"[\s\S]*circle/,
  'Header refresh secondary action should render as an icon-only refresh button',
)
assert.match(
  headerSource,
  /v-else-if="stageAdvice\.secondaryLabel && stageAdvice\.secondaryAction"[\s\S]*{{ stageAdvice\.secondaryLabel }}/,
  'Header non-refresh secondary actions should still render their labels',
)
assert.match(headerSource, /openImportDialog/, 'Header should expose the add-interface dialog action')
assert.match(headerSource, /添加接口/, 'Header should render the add-interface action')
assert.doesNotMatch(
  headerSource,
  /查看 SDK 同步指引/,
  'Header should not render the SDK sync guide as a top-level action',
)
assert.match(headerSource, /hero-copy/, 'API catalog detail header should use project-management hero copy layout')
assert.match(headerSource, /hero-accent/, 'API catalog detail header should use the project-management hero accent')
assert.doesNotMatch(headerSource, /project-meta/, 'API catalog detail header should not render the extra metadata row')
assert.doesNotMatch(headerSource, /stage-advice/, 'API catalog detail header should not render the extra advice row')
assert.doesNotMatch(
  headerSource,
  /asset-directory-bar|asset-identity/,
  'API catalog detail header should not keep the old plain asset bar classes',
)

assert.match(
  scanDetailSource,
  /<el-tab-pane label="模块管理" name="modules" \/>[\s\S]*?<el-tab-pane label="接口目录" name="tools" \/>[\s\S]*?<el-tab-pane label="接口图谱" name="apiGraph" \/>/,
  'API catalog detail tabs should be ordered modules, tools, api graph',
)
assert.match(uiStateSource, /ref<ScanWorkbenchTab>\('modules'\)/, 'API catalog detail should open on module management first')
assert.doesNotMatch(
  scanDetailSource,
  /<el-collapse v-model="detailPanelActive" class="scan-detail-sections">/,
  'API catalog detail tabs should not keep the extra top-level collapse shell',
)
assert.doesNotMatch(
  uiStateSource,
  /detailPanelActive/,
  'API catalog detail state should not keep obsolete top-level collapse state',
)
assert.doesNotMatch(
  modulesPanelSource,
  /<el-collapse-item class="scan-detail-top-item semantic-inline-collapse"/,
  'Module list should render as a static panel rather than a collapsible item',
)
assert.match(
  modulesPanelSource,
  /class="scan-detail-card-header ai-card-header"/,
  'Module list should keep its header actions in the static panel header',
)
assert.doesNotMatch(
  apiGraphPanelSource,
  /点击折叠卡/,
  'API graph panel should not reference the removed outer collapse card',
)
assert.match(
  scanDetailSource,
  /<ScanProjectAddInterfaceDialog[\s\S]*@scan-sdk="scanSdkCapabilities"/,
  'API catalog detail page should mount the add-interface source import dialog',
)
assert.match(
  scanDetailSource,
  /<ScanProjectAddInterfaceDialog[\s\S]*@sdk-guide="openSdkAccessGuide"/,
  'Add-interface dialog should delegate SDK guide navigation to the detail page',
)
assert.match(scanDetailSource, /triggerSdkCapabilityScan/, 'API catalog detail page should trigger SDK scanning')
assert.match(scanProjectApiSource, /sdk-sync\/scan/, 'Scan project API should expose explicit SDK scan trigger')

assert.match(addInterfaceDialogSource, /SDK 同步/, 'Add-interface dialog should include the SDK sync tab')
assert.match(addInterfaceDialogSource, /SDK 接入指引/, 'SDK sync tab should include the onboarding guide action')
assert.match(addInterfaceDialogSource, /ReachAI 将主动调用业务系统/, 'SDK sync tab should explain the server-to-server call direction')
assert.match(addInterfaceDialogSource, /\/reachai\/registry\/capabilities\/sync/, 'SDK sync tab should show the exact business callback path')
assert.match(addInterfaceDialogSource, /业务登录\/JWT与 CSRF 放行[\s\S]*Starter[\s\S]*签名/, 'SDK sync tab should explain auth bypass versus Starter signature verification')
assert.match(addInterfaceDialogSource, /当前回调地址使用 localhost/, 'SDK sync tab should warn when the registered target is loopback')
assert.match(addInterfaceDialogSource, /syncError[\s\S]*最近一次同步失败/, 'SDK sync tab should keep actionable failure diagnostics visible')
assert.match(scanDetailSource, /:sync-error="sdkScanError"/, 'API catalog detail should pass persistent SDK sync diagnostics into the dialog')
assert.match(addInterfaceDialogSource, /AI Coding 扫描/, 'Add-interface dialog should include the AI Coding scan tab')
assert.match(addInterfaceDialogSource, /扫描解析设置/, 'Add-interface dialog should include scan parsing settings')
assert.doesNotMatch(
  addInterfaceDialogSource,
  /add-interface-dialog-mark|source-panel-icon/,
  'Add-interface dialog should not render decorative icon blocks that look clickable',
)
assert.doesNotMatch(
  scanDetailStyle,
  /add-interface-dialog-mark|source-panel::after|source-panel-icon/,
  'Add-interface dialog styles should remove decorative icon containers and background grid noise',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog\)\s*\{[\s\S]*?background:\s*[\s\S]*?rgba\(255,\s*255,\s*255,\s*0\.52\)[\s\S]*?backdrop-filter:\s*blur\(30px\)\s+saturate\(1\.22\)/,
  'Add-interface dialog should use a visibly translucent glass surface',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.source-import-tabs \.el-tabs__nav-scroll\)\s*\{[\s\S]*?display:\s*inline-flex/,
  'Add-interface source tabs should hug their content instead of stretching visually',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.source-import-tabs \.el-tabs__nav\)\s*\{[\s\S]*?display:\s*grid[\s\S]*?width:\s*392px[\s\S]*?max-width:\s*100%[\s\S]*?grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/,
  'Add-interface source tabs should use two balanced glass segments without over-stretching',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.source-import-tabs \.el-tabs__item\)\s*\{[\s\S]*?width:\s*100%\s*!important[\s\S]*?padding:\s*0\s*!important[\s\S]*?justify-content:\s*center[\s\S]*?text-align:\s*center/,
  'Add-interface source tab text should be visually centered',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.source-panel\)\s*\{[\s\S]*?border:\s*1px\s+solid\s+rgb\(var\(--brand-primary-rgb\)\s*\/\s*0\.2\)[\s\S]*?background:\s*[\s\S]*?rgba\(255,\s*255,\s*255,\s*0\.38\)[\s\S]*?box-shadow:\s*[\s\S]*?0 0 0 1px rgba\(255,\s*255,\s*255,\s*0\.62\)[\s\S]*?0 0 34px -18px rgb\(var\(--brand-hover-rgb\)\s*\/\s*0\.68\)[\s\S]*?0 24px 62px -34px rgba\(15,\s*23,\s*42,\s*0\.34\)/,
  'Add-interface source cards should have visible glass outline, glow, and depth',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.source-panel::before\)\s*\{[\s\S]*?inset:\s*1px[\s\S]*?border:\s*1px\s+solid\s+rgba\(255,\s*255,\s*255,\s*0\.58\)[\s\S]*?background:\s*[\s\S]*?linear-gradient\(135deg,\s*rgba\(255,\s*255,\s*255,\s*0\.5\),\s*rgba\(255,\s*255,\s*255,\s*0\)\s*42%\)/,
  'Add-interface source cards should include an inner glass highlight layer',
)
assert.match(
  scanDetailStyle,
  /:global\(\.add-interface-dialog \.order-item\)\s*\{[\s\S]*?grid-template-columns:\s*28px\s+minmax\(180px,\s*300px\)\s+auto\s+minmax\(0,\s*1fr\)/,
  'Add-interface scan source controls should sit near the source label instead of at the far row edge',
)
assert.match(
  addInterfaceDialogSource,
  /<template #header>[\s\S]*class="add-interface-dialog-header"[\s\S]*设置/,
  'Scan parsing settings should be exposed from the add-interface dialog title bar',
)
assert.match(
  scanDetailSource,
  /v-model:settings-visible="addInterfaceSettingsVisible"/,
  'Scan parsing settings visibility should be controlled by the add-interface dialog',
)
assert.doesNotMatch(
  addInterfaceDialogSource,
  /<el-tab-pane label="扫描解析设置"|<el-tab-pane label="扫描解析规则"|name="scanRules"|AddInterfaceTab = 'sdk' \| 'aiCoding' \| 'scanRules'/,
  'Scan parsing settings should not be a source-import tab peer of SDK sync or AI Coding scan',
)
assert.match(addInterfaceDialogSource, /重新扫描/, 'Add-interface dialog should own the rescan action')
assert.match(addInterfaceDialogSource, /saveScanSettings/, 'Add-interface dialog should own saving scan settings')
assert.match(
  addInterfaceDialogSource,
  /同步在线业务系统/,
  'SDK sync tab should use concise action-oriented copy',
)
assert.match(
  addInterfaceDialogSource,
  /扫描并同步接口/,
  'SDK sync tab should trigger a live SDK scan before syncing APIs',
)
assert.match(
  addInterfaceDialogSource,
  /从代码仓库或 AI Coding 结果导入接口候选/,
  'AI Coding tab should use concise source-import copy',
)
assert.doesNotMatch(
  addInterfaceDialogSource,
  /source-import-heading|从来源导入接口资产|选择一个已接入来源|扫描并同步 SDK 接口|查看 SDK 同步指引|等待接入 AI Coding 工具扫描|这里后续承载/,
  'Add-interface dialog should avoid redundant intro copy and placeholder language',
)
assert.doesNotMatch(addInterfaceDialogSource, /手填|手动填写|HTTP API 表单/, 'Add-interface dialog should not support manual API creation')
assert.doesNotMatch(summarySource, /查看 SDK 同步指引/, 'SDK guide should not be the project-stage primary action')
assert.match(summarySource, /primaryLabel: '添加接口'/, 'SDK project without APIs should direct users to add-interface import')
assert.match(summarySource, /primaryAction: 'importApi'/, 'SDK project without APIs should open the add-interface dialog')
assert.doesNotMatch(scanDetailSource, /ScanProjectScanRulesDrawer/, 'Scan parsing rules should live in the add-interface dialog')
assert.match(summarySource, /检查 Tool 关联/, 'Tool reconciliation should be presented as a Tool-link check')

assert.match(opsDrawerSource, /title="维护动作"/, 'Ops drawer should be narrowed to maintenance actions')
assert.match(opsDrawerSource, /重建向量索引/, 'Maintenance actions should retain embedding index rebuild')
assert.doesNotMatch(
  opsDrawerSource,
  /重新扫描|保存扫描设置|保存 AI 设置|saveScanSettings|saveAiGenerationSettings/,
  'Maintenance actions should not keep import, scan-settings, or AI-settings actions',
)

assert.match(overviewSource, /class="governance-focus-panel"/)
assert.match(overviewSource, /MetricIconBg/, 'API catalog overview should reuse the project metric icon treatment')
for (const iconKey of ['api-discovery', 'ai-semantic', 'tool-publish', 'agent-ready']) {
  assert.match(overviewSource, new RegExp(iconKey), `API catalog overview should map stage icon ${iconKey}`)
  assert.match(metricIconBgSource, new RegExp(`iconKey === '${iconKey}'`), `MetricIconBg should draw ${iconKey}`)
}
assert.match(
  metricIconBgSource,
  /class="metric-icon-bg__svg metric-icon-bg__svg--agent-ready"/,
  'Agent-ready icon should have its own visual adjustment class',
)
assert.match(
  metricIconBgSource,
  /\.metric-icon-bg__svg--agent-ready\s*\{[\s\S]*?transform:\s*translateY\(1\.2px\) scale\(1\.08\)/,
  'Agent-ready icon should render slightly larger and lower than the other stage icons',
)
assert.doesNotMatch(
  metricIconBgSource,
  /M18\.4 14\.9 20\.2 13/,
  'AI semantic icon should not draw an extra upper-right lens stroke',
)
assert.match(
  metricIconBgSource,
  /M12 2\.8v3\.4/,
  'Tool publish icon should lift the publish arrow above the package top edge',
)
assert.match(
  metricIconBgSource,
  /m9\.8 4\.9 2\.2-2\.1 2\.2 2\.1/,
  'Tool publish icon should lift the publish arrow head with the stem',
)
assert.doesNotMatch(
  overviewSource,
  /discover: 'api'|semantic: 'scan'|tool: 'sdk'|agent: 'project'/,
  'API governance stages should not reuse generic project detail icons',
)
assert.match(overviewSource, /class="governance-stage-grid"/)
assert.match(overviewSource, /governance-stage-card/)
assert.match(overviewSource, /governance-stage-icon/)
assert.match(
  metricIconBgSource,
  /stroke-width:\s*var\(--metric-icon-stroke-width,\s*1\.55\)/,
  'MetricIconBg should expose a thinner configurable stroke for custom icons',
)
assert.match(
  scanDetailStyle,
  /--metric-icon-glyph-size:\s*30px/,
  'API governance stage icons should render larger glyphs than the shared default',
)
assert.match(
  scanDetailStyle,
  /--metric-icon-stroke-width:\s*1\.35/,
  'API governance stage icons should use a finer line weight',
)
assert.doesNotMatch(overviewSource, /当前关注/, 'Overview should not render the extra current-focus copy block')
assert.doesNotMatch(overviewSource, /governance-focus-copy/, 'Overview should not render the extra current-focus copy block')
assert.doesNotMatch(overviewSource, /governance-quick-stats/, 'Overview should not render the extra quick stats block')
assert.doesNotMatch(overviewSource, /class="metric-strip"/, 'Overview should not render a separate KPI row')
assert.doesNotMatch(overviewSource, /class="metric-segment"/, 'Overview should not render duplicate metric cards')
assert.doesNotMatch(overviewSource, /class="governance-stage-strip"/, 'Overview should not render five heavy stage cards')
assert.doesNotMatch(overviewSource, /governance-entry-dot/, 'Overview should not keep the old low-fidelity dot marker')
assert.doesNotMatch(overviewSource, /index \+ 1/, 'Governance entries should not imply numbered linear steps')
assert.doesNotMatch(overviewSource, /风险复核/, 'Risk review should not be rendered as a governance node')
for (const label of ['发现 API', '补全 AI 语义', '上架 Tool', '用于 Agent']) {
  assert.match(overviewSource, new RegExp(label), `Overview should render governance entry ${label}`)
}
assert.doesNotMatch(
  overviewSource,
  /class="kpi-item"/,
  'API catalog overview should not keep the old low-fidelity KPI item layout',
)

const toolsPanelSource = readFileSync(
  join(process.cwd(), 'src/views/scan/components/scan-project/ScanProjectToolsPanel.vue'),
  'utf8',
)
assert.match(toolsPanelSource, /还没有发现 API/, 'Tools panel should use an action-oriented empty state')
assert.doesNotMatch(
  toolsPanelSource,
  /当前还没有收到能力上报/,
  'Tools panel source should not keep the long no-report explanation',
)
assert.doesNotMatch(
  summarySource,
  /当前还没有收到能力上报/,
  'No-report copy should be removed from the scan project summary source',
)
assert.match(
  toolsPanelSource,
  /v-if="tools\.length > 0"[\s\S]*class="tools-actions"/,
  'Tools panel should hide table batch actions when the project has no APIs',
)
assert.match(toolsPanelSource, /emptyPrimaryAction/, 'Tools panel empty state should expose the stage primary action')
assert.match(toolsPanelSource, /emptySecondaryAction/, 'Tools panel empty state should expose the stage secondary action')

assert.doesNotMatch(
  scanDetailStyle,
  /:global\(\.main-layout[^)]*:has\(\.api-catalog-workbench\)/,
  'API catalog detail page must not infer or override MainLayout through :has()',
)
assert.doesNotMatch(
  scanDetailStyle,
  /--reachai-workbench-page-padding/,
  'API catalog detail page must consume the shared layout tokens instead of the removed legacy padding token',
)
assert.match(scanDetailStyle, /\.api-catalog-workbench :deep\(\.api-catalog-hero\)/)
assert.doesNotMatch(scanDetailStyle, /\.api-catalog-workbench :deep\(\.project-meta\)/)
assert.doesNotMatch(scanDetailStyle, /\.api-catalog-workbench :deep\(\.stage-pill\)/)
assert.doesNotMatch(scanDetailStyle, /\.api-catalog-workbench :deep\(\.stage-advice\)/)
assert.match(scanDetailStyle, /\.api-catalog-workbench :deep\(\.governance-focus-panel\)/)
assert.match(scanDetailStyle, /\.api-catalog-workbench :deep\(\.governance-stage-grid\)/)
assert.match(scanDetailStyle, /\.api-catalog-workbench :deep\(\.governance-stage-card\)/)
assert.match(scanDetailStyle, /\.api-catalog-workbench :deep\(\.governance-stage-icon\)/)
assert.doesNotMatch(scanDetailStyle, /governance-entry-dot/, 'Overview styles should drop the old dot marker')
assert.doesNotMatch(scanDetailStyle, /governance-focus-copy/, 'Overview styles should drop the extra focus copy column')
assert.doesNotMatch(scanDetailStyle, /governance-quick-stats/, 'Overview styles should drop the extra quick stats column')
assert.doesNotMatch(scanDetailStyle, /reachai-project-hero-bg\.png/, 'This workbench should avoid decorative hero artwork')
assert.match(scanDetailStyle, /\.api-catalog-workbench \.scan-detail-sections/)
assert.match(scanDetailStyle, /\.scan-detail-sections :deep\(\.scan-detail-top-item\)[\s\S]*?border-radius:\s*16px/)
assert.match(scanDetailStyle, /\.scan-detail-sections :deep\(\.scan-detail-card-header\)/)
assert.match(scanDetailStyle, /\.scan-detail-sections :deep\(\.scan-detail-card-content\)/)
assert.match(scanDetailStyle, /:deep\(\.tool-groups-collapse th\.el-table__cell\)/)

console.log('scan project detail ui assertions passed')
