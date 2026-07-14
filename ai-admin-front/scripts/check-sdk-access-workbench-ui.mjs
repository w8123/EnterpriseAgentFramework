import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const wizardSource = readFileSync(join(process.cwd(), 'src/views/registry/SdkAccessWizard.vue'), 'utf8')
const wizardStyle = readFileSync(join(process.cwd(), 'src/views/registry/styles/SdkAccessWizard.scss'), 'utf8')
const aiCodingFigmaStyle = readFileSync(join(process.cwd(), 'src/views/registry/styles/SdkAccessWizard.ai-coding.figma.scss'), 'utf8')
const mainLayoutSource = readFileSync(join(process.cwd(), 'src/views/layout/MainLayout.vue'), 'utf8')
const progressSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardProgress.ts'), 'utf8')
const snippetSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardSnippets.ts'), 'utf8')
const uiStateSource = readFileSync(join(process.cwd(), 'src/views/registry/composables/useSdkAccessWizardUiState.ts'), 'utf8')
const codeSnippetBlockSource = readFileSync(join(process.cwd(), 'src/components/common/CodeSnippetBlock.vue'), 'utf8')
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
assert.equal(
  (wizardSource.match(/<CodeSnippetBlock/g) || []).length,
  4,
  'SdkAccessWizard should render all Starter, gateway, and frontend snippets through the shared code snippet block',
)
assert.match(wizardSource, /AI Coding 接入/, 'AI Coding should be visible as a primary access mode')
assert.match(wizardSource, /手动接入/, 'Manual access should be visible as a primary access mode')
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
assert.match(progressSource, /index:\s*1,[\s\S]*?key:\s*'starter'[\s\S]*?title:\s*'后端 Starter'/, 'Manual steps should start with backend Starter')
assert.match(progressSource, /index:\s*5,[\s\S]*?key:\s*'self-check'[\s\S]*?title:\s*'最终自检'/, 'Manual steps should end at step 5 after removing project identification')
assert.doesNotMatch(progressSource, /确认实例与旧接口资产/, 'SDK onboarding should not require legacy interface assets to complete backend service validation')
assert.doesNotMatch(progressSource, /legacyAssetSelectionRequired/, 'Backend service validation should complete on SDK heartbeat without legacy interface sync')
assert.match(progressSource, /确认 SDK 实例心跳/, 'Backend service validation should be framed around SDK instance heartbeat')
assert.match(uiStateSource, /activeStep\s*=\s*ref<SdkAccessWizardStepKey>\('starter'\)/, 'Manual wizard should default to the first actionable step')

const aiCodingPane = wizardSource.match(/<section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">([\s\S]*?)<\/main>/)?.[1] || ''
assert.ok(aiCodingPane, 'AI Coding access pane should be present')
assert.doesNotMatch(aiCodingPane, /<span class="step-kicker">AI Coding 接入<\/span>/, 'AI Coding pane should not repeat the mode name above the main heading')
assert.doesNotMatch(aiCodingPane, /ai-coding-key-panel/, 'AI Coding pane should not render the access key card inline')
assert.doesNotMatch(aiCodingPane, /AI Coding 接入秘钥/, 'AI Coding pane should not render the access key card title inline')
assert.match(aiCodingPane, /将接入任务交给AI 编程工具/, 'AI Coding pane should use the shorter main heading')
assert.doesNotMatch(aiCodingPane, /把 SDK 接入任务交给外部 AI 编程工具/, 'AI Coding pane should not use the old verbose heading')
assert.doesNotMatch(aiCodingPane, /切换工具后复制同一套接入任务，AI 会按步骤向平台回传进度。/, 'AI Coding prompt card should not render the explanatory sentence')
assert.doesNotMatch(aiCodingPane, /打开完整提示词/, 'AI Coding pane should not render the full prompt dialog button')

assert.match(
  wizardSource,
  /class="sdk-access-page project-workbench-page"/,
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
  /\.manual-access-pane\s*\{[\s\S]*?flex:\s*1 1 auto[\s\S]*?\.ai-coding-access-pane\s*\{[\s\S]*?flex:\s*0 0 auto/,
  'AI Coding pane should size to its cards instead of stretching into a large empty bottom area',
)
assert.match(
  wizardStyle,
  /\.ai-coding-main[\s\S]*?align-self:\s*start/,
  'AI Coding main panel should not stretch taller than its content',
)
assert.doesNotMatch(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-main\s*\{\s*min-height:\s*786px/,
  'AI Coding main panel should not keep the old fixed 786px min-height',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-side\.access-progress--refined\s*\{[\s\S]*?position:\s*relative\s*!important[\s\S]*?top:\s*0\s*!important[\s\S]*?height:\s*580px\s*!important/,
  'AI Coding progress card should override the sticky rail offset and align with the right workbench card',
)
assert.match(
  aiCodingFigmaStyle,
  /\.sdk-access-page \.ai-coding-side\.access-progress--refined \.progress-step\s*\{[\s\S]*?min-height:\s*64px\s*!important[\s\S]*?padding-top:\s*9px\s*!important[\s\S]*?padding-bottom:\s*9px\s*!important/,
  'AI Coding progress node cards should stay tall enough to read comfortably',
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
assert.match(snippetSource, /API 管理[\s\S]*手动触发/, 'AI Coding prompt should say SDK interface scanning is manually triggered from API management')
assert.match(onboardingSkillSource, /API 管理[\s\S]*手动触发/, 'Packaged onboarding skill should say SDK interface scanning is manually triggered from API management')
assert.match(javaSdkAccessSource, /API 管理[\s\S]*手动触发/, 'Java SDK reference should say SDK interface scanning is manually triggered from API management')
assert.match(
  snippetSource,
  /\/reachai\/registry\/capabilities\/sync[\s\S]*业务登录\/JWT[\s\S]*CSRF[\s\S]*Starter[\s\S]*签名校验/,
  'AI Coding prompt should prepare the signed inbound SDK sync callback without weakening Starter authentication',
)
assert.match(
  snippetSource,
  /Path=\/reachai\/capabilities\/\*\*,\/reachai\/registry\/\*\*/,
  'Gateway example should route both capability invocation and registry callback traffic',
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
  '后端 Starter',
  '网关路由',
  '业务服务校验',
  '前端 Embed Token',
  '最终自检',
  'aiPromptDialogVisible',
  'accessSession',
  'readiness-list',
]) {
  assert.match(wizardSource, new RegExp(retainedCopy), `SdkAccessWizard lost existing content: ${retainedCopy}`)
}
