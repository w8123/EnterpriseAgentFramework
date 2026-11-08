import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const wizardSource = readFileSync(join(process.cwd(), 'src/views/registry/SdkAccessWizard.vue'), 'utf8')
const enrollmentSource = readFileSync(join(process.cwd(), 'src/views/registry/RegistryProjectList.vue'), 'utf8')
const installSource = readFileSync(join(process.cwd(), 'src/views/registry/components/SdkArtifactInstall.vue'), 'utf8')
const wizardStyle = readFileSync(join(process.cwd(), 'src/views/registry/styles/SdkAccessWizard.scss'), 'utf8')
const aiCodingFigmaStyle = readFileSync(join(process.cwd(), 'src/views/registry/styles/SdkAccessWizard.ai-coding.figma.scss'), 'utf8')
const mainLayoutSource = readFileSync(join(process.cwd(), 'src/views/layout/MainLayout.vue'), 'utf8')
const progressSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardProgress.ts'), 'utf8')
const snippetSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardSnippets.ts'), 'utf8')
const dataSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardData.ts'), 'utf8')
const uiStateSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardUiState.ts'), 'utf8')
const codeSnippetBlockSource = readFileSync(join(process.cwd(), 'src/components/common/CodeSnippetBlock.vue'), 'utf8')
const providerSelectorSource = readFileSync(join(process.cwd(), 'src/components/ai-coding/AiCodingProviderSelector.vue'), 'utf8')
const loadErrorStateSource = readFileSync(
  join(process.cwd(), 'src/views/registry/components/ProjectWorkbenchLoadErrorState.vue'),
  'utf8',
)
const skillRoot = join(process.cwd(), '..', 'reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding')
const onboardingSkillSource = readFileSync(join(skillRoot, 'SKILL.md'), 'utf8')
const onboardingTemplateSource = readFileSync(join(skillRoot, 'templates/application-reachai.yml'), 'utf8')
const javaSdkAccessSource = readFileSync(join(skillRoot, 'references/java-sdk-access.md'), 'utf8')
const platformApisSource = readFileSync(join(skillRoot, 'references/platform-apis.md'), 'utf8')
const starterRoot = join(process.cwd(), '..', 'reachai-spring-boot2-starter/src/main/java/com/enterprise/ai/reach/spring')
const starterPropertiesSource = readFileSync(join(starterRoot, 'ReachAiRegistryProperties.java'), 'utf8')
const starterClientSource = readFileSync(join(starterRoot, 'ReachAiRegistryClient.java'), 'utf8')
const capabilityPropertiesSource = starterPropertiesSource.match(/public static class Capability \{([\s\S]*?)\n    }\n\n    public static class Embed/)?.[1] || ''

assert.match(wizardSource, /项目接入工作台/, 'SdkAccessWizard should be framed as the project access workbench')
assert.match(wizardSource, /accessMode/, 'SdkAccessWizard should expose a manual vs AI Coding access mode')
assert.match(wizardSource, /manual-access-pane/, 'Manual SDK access pane should remain addressable')
assert.match(wizardSource, /ai-coding-access-pane/, 'AI Coding access pane should be a first-class page pane')
assert.match(wizardSource, /import CodeSnippetBlock from '@\/components\/common\/CodeSnippetBlock\.vue'/, 'SdkAccessWizard should reuse the shared code snippet block')
assert.match(installSource, /<CodeSnippetBlock/, 'SDK installation should use the shared snippet block')
assert.match(wizardSource, /<SdkArtifactInstall[\s\S]*?:language="sdkLanguage"/, 'The workbench should install the selected SDK')
assert.match(enrollmentSource, /<SdkArtifactInstall[\s\S]*?:language="enrollmentLanguage"/, 'The same SDK instructions must be available before registration')
assert.match(installSource, /javaReady[\s\S]*?integritySha256[\s\S]*?pomIntegritySha256/, 'Java installers must require both artifact digests')
assert.match(installSource, /packageArtifact[\s\S]*?item.downloadUrl[\s\S]*?sha256.test\(item.integritySha256/, 'Other language installers must require a verified platform artifact')
assert.match(wizardSource, /aria-label="业务系统语言"/, 'The SDK language selector must have an accessible name')
assert.match(wizardSource, /v-if="accessMode === 'manual'" v-model="sdkLanguage"/, 'Only manual access should show the language selector')
assert.match(wizardSource, /sdk-detected-language[\s\S]*?等待 AI 识别技术栈/, 'AI access should display its detected language or a pending state')
assert.match(snippetSource, /method: 'POST',[\s\S]*?body: JSON.stringify/, 'The browser example must use the SDK Token Broker POST contract')
assert.match(wizardSource, /AI Coding 接入/, 'AI Coding should be visible as a primary access mode')
assert.match(wizardSource, /手动接入/, 'Manual access should be visible as a primary access mode')
assert.match(wizardSource, /ProjectWorkbenchLoadErrorState/, 'SdkAccessWizard should reuse the shared blocking load-error state')
assert.match(wizardSource, /v-else-if="loadError"/, 'SdkAccessWizard should distinguish load failures from missing projects and valid content')
assert.match(wizardSource, /@retry="loadAll"/, 'SdkAccessWizard load failure should offer a real retry')
assert.match(dataSource, /const loadError = ref\(''\)/, 'SDK access data should expose truthful initial-load failure state')
assert.match(dataSource, /clearWorkbenchData\(\)/, 'SDK access data should clear stale project data before retrying')
assert.match(loadErrorStateSource, /重新加载/, 'Shared project-workbench load failure state should expose retry')
assert.doesNotMatch(wizardSource, /class="header-actions"/, 'SdkAccessWizard header should not render legacy action buttons above the tabs')
assert.doesNotMatch(wizardSource, /使用 AI 快速接入/, 'SdkAccessWizard header should not render the legacy quick AI access button')
assert.doesNotMatch(wizardSource, /刷新状态/, 'SdkAccessWizard header should not render the legacy refresh button')
assert.doesNotMatch(wizardSource, /class="access-mode-header"/, 'SdkAccessWizard should not render a separate tab switch card below the title card')

const pageHeader = wizardSource.match(/<PageHeader\b[\s\S]*?<\/PageHeader>/)?.[0] || ''
assert.match(pageHeader, /variant="workbench"/, 'SdkAccessWizard should use the shared Workbench title role')
assert.match(pageHeader, /domain="project"/, 'SdkAccessWizard should use the project title domain')
assert.match(pageHeader, /<template #mode>[\s\S]*?<HeaderModeSwitch/, 'Manual and AI Coding modes should live in the shared title action dock')
assert.doesNotMatch(pageHeader, /返回项目详情/, 'SdkAccessWizard title card should not duplicate the global breadcrumb back action')

const manualPane = wizardSource.match(/<section v-show="accessMode === 'manual'" class="manual-access-pane wizard-shell">([\s\S]*?)<section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">/)?.[1] || ''
assert.ok(manualPane, 'Manual access pane should be present')
assert.doesNotMatch(manualPane, /ai-session-card/, 'Manual access pane should not render the AI access session card')
assert.doesNotMatch(manualPane, /manual-status-strip/, 'Manual access pane should not render the redundant pre-access status card')
assert.doesNotMatch(manualPane, /<pre class="code-panel">/, 'Manual access pane should not render raw code panels directly')
assert.doesNotMatch(manualPane, /接入前状态/, 'Manual access pane should not render the redundant pre-access status heading')
assert.doesNotMatch(manualPane, /activeStep === 'overview'/, 'Manual access pane should not render project identification as a wizard step')
assert.doesNotMatch(manualPane, /<h2>项目识别<\/h2>/, 'Manual access pane should not render project identification as the first step title')

assert.doesNotMatch(progressSource, /key:\s*'overview'/, 'Manual step model should not include the project identification step')
assert.doesNotMatch(progressSource, /title:\s*'项目识别'/, 'Manual step model should not include project identification as a step title')
assert.match(progressSource, /key:\s*'starter'[\s\S]*?title:\s*'后端 SDK'/, 'Manual steps should start with backend SDK')
assert.match(progressSource, /key:\s*'capabilities'[\s\S]*?key:\s*'identity'[\s\S]*?key:\s*'gateway'[\s\S]*?key:\s*'backend-check'[\s\S]*?key:\s*'frontend'[\s\S]*?key:\s*'acceptance'/,
  'The workbench must guide capability declaration, identity, routing, sync, embedding and real acceptance in order')
assert.match(manualPane, /注册凭据、心跳与签名回调证据/, 'Manual self-check scope should stay within platform-observed SDK facts')
assert.doesNotMatch(progressSource, /确认实例与旧接口资产/, 'SDK onboarding should not require legacy interface assets to complete backend service validation')
assert.match(progressSource, /done: canConfirmSync.value && checks.sync/, 'A heartbeat alone must not complete capability sync')
assert.match(manualPane, /同步与发现 → 扫描并同步接口/, 'Manual access should name the actual SDK sync action')
assert.match(uiStateSource, /activeStep\s*=\s*ref<SdkAccessWizardStepKey>\('starter'\)/, 'Manual wizard should default to the first actionable step')

const aiCodingPane = wizardSource.match(/<section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">([\s\S]*?)<\/main>/)?.[1] || ''
assert.ok(aiCodingPane, 'AI Coding access pane should be present')
assert.doesNotMatch(aiCodingPane, /<span class="step-kicker">AI Coding 接入<\/span>/, 'AI Coding pane should not repeat the mode name above the main heading')
assert.doesNotMatch(aiCodingPane, /ai-coding-key-panel/, 'AI Coding pane should not render the access key card inline')
assert.doesNotMatch(aiCodingPane, /AI Coding 接入秘钥/, 'AI Coding pane should not render the access key card title inline')
assert.match(aiCodingPane, /当前接入状态[\s\S]*?taskGuidance\.title[\s\S]*?taskGuidance\.description/, 'AI Coding pane should explain the current task state and next action')
assert.match(aiCodingPane, /scenario="project-workbench-onboarding"[\s\S]*?:context="\{ projectCode:/, 'Project onboarding should use the current project and the MCP scenario')
assert.doesNotMatch(wizardSource, /sdk-windows-handoff|Windows 备用接入|ai-onboarding-dialog/, 'Project onboarding should not expose the legacy Windows handoff')
assert.doesNotMatch(dataSource, /prepareAiOnboardingTask|reissueOnboardingHandoff|backupSdkLanguage/, 'Project onboarding should not generate or reissue handoffs')
assert.match(aiCodingPane, /class="ai-progress-steps" role="list"/, 'AI Coding progress should be a status list')
assert.match(aiCodingPane, /class="progress-step ai-progress-step"[\s\S]*?role="listitem"/, 'AI Coding progress rows should not masquerade as buttons')
assert.match(manualPane, /activeStep === 'acceptance'[\s\S]*?在真实业务系统中验证/, 'Manual access must separate browser and business acceptance from platform self-check')
assert.match(aiCodingPane, /接入报告[\s\S]*?aiReportAvailable[\s\S]*?平台接入检查/, 'AI reports and platform observations should be presented separately')
assert.match(aiCodingPane, /readinessSource\(item\)/, 'Platform observations should expose their evidence scope and available time')
assert.doesNotMatch(wizardSource, /可选 API 调用|参数 JSON/, 'SDK self-check must not render request fields ignored by the backend')
assert.doesNotMatch(aiCodingPane, /把 SDK 接入任务交给外部 AI 编程工具/, 'AI Coding pane should not use the old verbose heading')
assert.doesNotMatch(aiCodingPane, /切换工具后复制同一套接入任务，AI 会按步骤向平台回传进度。/, 'AI Coding prompt card should not render the explanatory sentence')
assert.doesNotMatch(aiCodingPane, /打开完整提示词/, 'AI Coding pane should not render the full prompt dialog button')
assert.doesNotMatch(wizardSource, /AiCodingProviderSelector/, 'Project onboarding should use the connected MCP tool')
assert.doesNotMatch(aiCodingPane, /\{\{\s*option\.label\s*\}\}/, 'AI Coding provider selector should not render text tabs')
assert.doesNotMatch(wizardSource, /ai-tool-tabs-track/, 'SDK access should remove the legacy text-tab underline')
assert.match(providerSelectorSource, /<el-tooltip[\s\S]*?:content="option\.label"/, 'Each AI Coding provider icon should expose its software name on hover')
assert.match(providerSelectorSource, /role="tab"[\s\S]*?:aria-label="option\.label"[\s\S]*?:aria-selected=/, 'Provider icon buttons should preserve accessible labels and selection state')
for (const iconName of ['codex', 'cursor', 'trae', 'claude-code']) {
  assert.match(
    providerSelectorSource,
    new RegExp(`@/assets/ai-coding-providers/${iconName}\\.svg`),
    `AI Coding provider selector should use the local ${iconName} icon`,
  )
}
assert.match(
  dataSource,
  /loadOnboardingTask\(detail\.id\)\.catch\(\(\) => undefined\)/,
  'AI Coding task-state failure must not make the manual SDK workbench unavailable',
)
assert.match(wizardSource, /AiCodingTaskDetailPanel/, 'SdkAccessWizard should reuse the common task detail panel')
assert.match(wizardSource, /<AiCodingTaskDetailPanel[\s\S]*?:handoff-enabled="false"/, 'Project task details should not expose legacy handoff actions')
assert.doesNotMatch(wizardSource, /@reissue=/, 'Project task details should not reissue handoffs')

assert.match(
  wizardSource,
  /class="sdk-access-page project-workbench-page(?: workbench-page--list)?"/,
  'SdkAccessWizard should consume the shared project-workbench page rhythm',
)
assert.doesNotMatch(
  wizardStyle,
  /:global\(\.main-layout[^)]*:has\(\.sdk-access-page\)/,
  'SdkAccessWizard must not infer or override its MainLayout shell through :has()',
)
assert.match(
  mainLayoutSource,
  /\.main-content\s*\{[\s\S]*?overflow-y:\s*auto/,
  'MainLayout main content must remain the shared vertical scroll container for long workbench pages',
)
assert.match(
  wizardStyle,
  /\.sdk-access-page,[\s\S]*?\.sdk-access-page\.is-dark-skin[\s\S]*?height:\s*auto/,
  'SdkAccessWizard page should grow with content instead of clipping scroll',
)
assert.match(
  wizardStyle,
  /\.sdk-access-page,[\s\S]*?\.sdk-access-page\.is-dark-skin[\s\S]*?min-height:\s*100%/,
  'SdkAccessWizard background should reach the bottom of the registry content area',
)
assert.match(
  wizardStyle,
  /\.manual-access-pane\.wizard-shell[\s\S]*?align-items:\s*start/,
  'Manual progress rail should not stretch to the height of the step content',
)
assert.match(
  wizardStyle,
  /\.step-progress[\s\S]*?height:\s*440px/,
  'Manual progress rail should use a compact fixed height for five steps',
)
assert.match(
  wizardStyle,
  /\.manual-access-pane\s*\{[\s\S]*?flex:\s*1 1 auto[\s\S]*?\.ai-coding-access-pane\s*\{[\s\S]*?flex:\s*1 1 auto/,
  'AI Coding pane should grow with the workbench so the progress card can reach the sidebar bottom',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-main\s*\{\s*align-self:\s*stretch/,
  'AI Coding main panel should align with the bottom of the progress card',
)
assert.doesNotMatch(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-main\s*\{\s*min-height:\s*786px/,
  'AI Coding main panel should not keep the old fixed 786px min-height',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-side\.access-progress--refined\s*\{[\s\S]*?position:\s*relative\s*!important[\s\S]*?top:\s*0\s*!important[\s\S]*?min-height:\s*100%\s*!important/,
  'AI Coding progress card should stretch to the workbench bottom instead of using a fixed height',
)
assert.match(
  wizardSource,
  /workbench-page--list/,
  'SdkAccessWizard should use the list-fill bottom inset so the progress card aligns with the sidebar',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-side\.access-progress--refined \.progress-step\s*\{[\s\S]*?min-height:\s*64px\s*!important[\s\S]*?padding-top:\s*9px\s*!important[\s\S]*?padding-bottom:\s*9px\s*!important/,
  'AI Coding progress node cards should stay tall enough to read comfortably',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-progress-meta\s*\{[\s\S]*?display:\s*grid[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s*auto[\s\S]*?:deep\(\.el-tag\)\s*\{[\s\S]*?grid-column:\s*1\s*\/\s*-1[\s\S]*?max-width:\s*100%[\s\S]*?white-space:\s*normal/,
  'AI Coding session status should use its own full-width row and remain readable without clipping',
)
assert.doesNotMatch(
  aiCodingFigmaStyle,
  /:global\(\.main-layout[^)]*:has\(\.sdk-access-page\)/,
  'SdkAccessWizard visual skin must not infer or override MainLayout through :has()',
)
assert.doesNotMatch(wizardStyle, /\.manual-status-strip\b/, 'Manual pre-access status card styles should be removed')
assert.doesNotMatch(wizardStyle, /\.code-shell\b/, 'Code snippet shell styles should live in the shared component')
assert.match(codeSnippetBlockSource, /defineProps<[\s\S]*?title:\s*string[\s\S]*?code:\s*string[\s\S]*?highlightedCode\?:\s*string/, 'Shared code snippet block should accept title, code, and optional highlighted HTML')
assert.match(codeSnippetBlockSource, /defineEmits<[\s\S]*?copy:\s*\[code:\s*string\]/, 'Shared code snippet block should emit the copied code')
assert.match(codeSnippetBlockSource, /\.code-shell/, 'Shared code snippet block should own the dark code-shell styling')
assert.match(
  codeSnippetBlockSource,
  /\.code-panel\s*\{[\s\S]*?padding:\s*18px 0 0;/,
  'Shared code snippet block should only keep top padding in the code panel',
)
assert.doesNotMatch(
  codeSnippetBlockSource,
  /padding:\s*18px 20px;/,
  'Shared code snippet block should not keep left, right, or bottom padding around code content',
)

for (const [name, source] of [
  ['SDK access prompt snippets', snippetSource],
  ['ReachAI onboarding skill', onboardingSkillSource],
  ['ReachAI onboarding application template', onboardingTemplateSource],
  ['ReachAI Java SDK reference', javaSdkAccessSource],
  ['ReachAI platform API reference', platformApisSource],
]) {
  assert.doesNotMatch(source, /sync-on-startup/, `${name} should not expose the removed capability startup sync setting`)
  assert.doesNotMatch(source, /capability-scan/, `${name} should not report SDK onboarding progress as capability-scan`)
}

assert.doesNotMatch(capabilityPropertiesSource, /syncOnStartup|isSyncOnStartup|setSyncOnStartup/, 'ReachAi capability properties should not keep the removed startup sync setting')
assert.doesNotMatch(starterClientSource, /getCapability\(\)\.isSyncOnStartup|syncOnStartup/, 'ReachAiRegistryClient should not check startup sync during registration')
assert.match(
  starterPropertiesSource,
  /enum ScanMode[\s\S]*ALL_CONTROLLERS[\s\S]*ANNOTATED_ONLY/,
  'ReachAi Starter should expose explicit all-controller and annotated-only scan modes',
)
for (const [name, source] of [
  ['SDK access prompt snippets', snippetSource],
  ['ReachAI onboarding application template', onboardingTemplateSource],
  ['ReachAI Java SDK reference', javaSdkAccessSource],
]) {
  assert.match(
    source,
    /scan-mode:\s*ANNOTATED_ONLY/,
    `${name} should recommend the curated annotated-only capability surface`,
  )
}
assert.match(snippetSource, /启动只注册项目与实例，同步在项目内手动触发/, 'AI Coding prompt should say SDK interface sync is manually triggered inside the project')
assert.match(onboardingSkillSource, /Console users trigger SDK sync from the project's \*{0,2}业务能力 → 接入与变更/, 'Packaged onboarding skill should say console SDK sync is triggered from 业务能力 → 接入与变更')
assert.match(javaSdkAccessSource, /Console users trigger SDK sync from the project's \*{0,2}业务能力 → 接入与变更/, 'Java SDK reference should say console SDK sync is triggered from 业务能力 → 接入与变更')
assert.match(
  onboardingSkillSource,
  /\/reachai\/registry\/capabilities\/sync[\s\S]*normal business login\/JWT filters and CSRF[\s\S]*Starter must still validate the ReachAI registry signature/,
  'The onboarding task guidance should prepare the signed inbound SDK sync callback without weakening Starter authentication',
)
assert.match(
  snippetSource,
  /Path=\/reachai\/capabilities\/\*\*,\/reachai\/registry\/\*\*/,
  'Gateway example should route both capability invocation and registry callback traffic',
)
assert.match(
  snippetSource,
  /Path=\/api\/reachai\/embed\/\*\*[\s\S]*?RewritePath=\/api\/reachai\/embed\/\?\(\?<segment>\.\*\), \/api\/embed\/\$\\\\\{segment\}/,
  'Gateway example should proxy the browser Embed API to ReachAI without changing its token',
)
assert.match(
  snippetSource,
  /Path=\/reachai\/embed\/token[\s\S]*?Method=POST/,
  'Gateway example should keep the authenticated business Token Broker separately addressable',
)
assert.match(
  snippetSource,
  /Starter validates the registry signature or invocation token/,
  'Gateway example should keep Starter-owned callback and invocation authentication explicit',
)
assert.match(
  snippetSource,
  /\/reachai\/embed\/token remains on the normal business login and CSRF chain/,
  'Gateway example should not whitelist the business-authenticated Token Broker',
)
assert.match(
  snippetSource,
  /embedPathPrefix: '\$\{embedPathPrefix\}'/,
  'Browser SDK example should declare the business gateway Embed prefix explicitly',
)
assert.match(
  snippetSource,
  /credentials: 'include'/,
  'Generic Token Broker fetch should preserve cookie-based business identity',
)
assert.match(
  snippetSource,
  /base-url[\s\S]*ReachAI 服务端实际可访问[\s\S]*localhost/,
  'AI Coding prompt should explain that the registered business base URL is server-reachable rather than blindly localhost',
)
for (const [name, source] of [
  ['ReachAI onboarding skill', onboardingSkillSource],
  ['ReachAI Java SDK reference', javaSdkAccessSource],
  ['ReachAI platform API reference', platformApisSource],
]) {
  assert.match(source, /\/reachai\/registry\/capabilities\/sync/, `${name} should document the inbound SDK sync callback`)
  assert.match(source, /X-ReachAI-App-Key[\s\S]*X-ReachAI-Timestamp[\s\S]*X-ReachAI-Nonce[\s\S]*X-ReachAI-Signature/, `${name} should preserve registry signature headers`)
  assert.match(source, /CSRF/, `${name} should cover the business CSRF boundary`)
}
assert.match(
  onboardingTemplateSource,
  /ReachAI server must be able to call this address[\s\S]*Loopback/,
  'Onboarding application template should warn that project base URL must be reachable from ReachAI',
)

for (const retainedCopy of [
  'onboardingTask',
  'AiCodingTaskDetailPanel',
  'selfCheckReadiness',
]) {
  assert.match(wizardSource, new RegExp(retainedCopy), `SdkAccessWizard lost existing content: ${retainedCopy}`)
}
