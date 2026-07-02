import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'

const require = createRequire(import.meta.url)
const ts = require('typescript')

const modulePath = join(process.cwd(), 'src/views/registry/pageAssistantOnboardingPrompt.ts')
const draftRequirementPath = join(process.cwd(), 'src/views/registry/pageAssistantDraftRequirement.ts')
const workflowAiCodingPromptPath = join(process.cwd(), 'src/views/registry/pageAssistantWorkflowAiCodingPrompt.ts')
const source = readFileSync(modulePath, 'utf8')
const draftRequirementSource = readFileSync(draftRequirementPath, 'utf8')
const workflowAiCodingPromptSource = readFileSync(workflowAiCodingPromptPath, 'utf8')
const wizardSource = readFileSync(join(process.cwd(), 'src/views/registry/PageAssistantWizard.vue'), 'utf8')
const sdkAccessWizardSource = readFileSync(join(process.cwd(), 'src/views/registry/SdkAccessWizard.vue'), 'utf8')
const scanProjectApiSource = readFileSync(join(process.cwd(), 'src/api/scanProject.ts'), 'utf8')
const scanProjectTypesSource = readFileSync(join(process.cwd(), 'src/types/scanProject.ts'), 'utf8')
const pageAssistantSkillSource = readFileSync(
  join(
    process.cwd(),
    '../reachai-control-service/src/main/resources/ai-assist/skills/reachai-page-assistant-onboarding/SKILL.md',
  ),
  'utf8',
)
const onboardingSecuritySource = readFileSync(
  join(
    process.cwd(),
    '../reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references/security.md',
  ),
  'utf8',
)
const onboardingSkillSource = readFileSync(
  join(
    process.cwd(),
    '../reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/SKILL.md',
  ),
  'utf8',
)
const onboardingJavaSdkAccessSource = readFileSync(
  join(
    process.cwd(),
    '../reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references/java-sdk-access.md',
  ),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  },
})
const compiledDraftRequirement = ts.transpileModule(draftRequirementSource, {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  },
})
const compiledWorkflowAiCodingPrompt = ts.transpileModule(workflowAiCodingPromptSource, {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  },
})

const sandbox = {
  exports: {},
  require,
  console,
}

vm.runInNewContext(compiled.outputText, sandbox, { filename: modulePath })

const draftSandbox = {
  exports: {},
  require,
  console,
}
vm.runInNewContext(compiledDraftRequirement.outputText, draftSandbox, { filename: draftRequirementPath })

const workflowAiCodingSandbox = {
  exports: {},
  require: (id) => {
    if (id === './pageAssistantDraftRequirement') {
      return draftSandbox.exports
    }
    return require(id)
  },
  console,
}
vm.runInNewContext(compiledWorkflowAiCodingPrompt.outputText, workflowAiCodingSandbox, {
  filename: workflowAiCodingPromptPath,
})

const { buildPageAssistantOnboardingPrompt } = sandbox.exports
const { buildPageAssistantDraftRequirement, decidePageAssistantFlowMode, buildIntentClasses } = draftSandbox.exports
const { buildPageAssistantWorkflowAiCodingPrompt } = workflowAiCodingSandbox.exports
assert.equal(typeof buildPageAssistantOnboardingPrompt, 'function')
assert.equal(typeof buildPageAssistantDraftRequirement, 'function')
assert.equal(typeof decidePageAssistantFlowMode, 'function')
assert.equal(typeof buildIntentClasses, 'function')
assert.equal(typeof buildPageAssistantWorkflowAiCodingPrompt, 'function')

assert.match(wizardSource, /使用 AI Coding 生成/)
assert.match(wizardSource, /buildPageAssistantWorkflowAiCodingPrompt/)
assert.match(wizardSource, /workflowAiCodingPromptDialogVisible/)
assert.match(wizardSource, /pageAssistantDraftRequirement/)

const linearRequirement = buildPageAssistantDraftRequirement({
  pageName: '班组档案',
  assistantGoal: 'query',
  actions: [
    { actionKey: 'setFilters', title: '设置筛选' },
    { actionKey: 'search', title: '执行查询' },
    { actionKey: 'readTable', title: '读取表格' },
  ],
})
assert.match(linearRequirement, /flowMode=LINEAR_QUERY/)
assert.match(linearRequirement, /setFilters/)
assert.match(linearRequirement, /search/)
assert.match(linearRequirement, /readTable/)
assert.doesNotMatch(linearRequirement, /flowMode=INTENT_ROUTER/)
assert.doesNotMatch(linearRequirement, /INTENT_CLASSIFIER\(strategy=HYBRID/)

const routerRequirement = buildPageAssistantDraftRequirement({
  pageName: '班组档案',
  assistantGoal: 'query',
  actions: [
    { actionKey: 'search', title: '执行查询' },
    { actionKey: 'reset', title: '重置筛选' },
    { actionKey: 'getPageState', title: '读取页面状态' },
  ],
})
assert.match(routerRequirement, /flowMode=INTENT_ROUTER/)
assert.match(routerRequirement, /INTENT_CLASSIFIER\(strategy=HYBRID/)
assert.match(routerRequirement, /reset_intent/)
assert.match(routerRequirement, /page_state_intent/)
assert.equal(
  decidePageAssistantFlowMode([
    { actionKey: 'setFilters' },
    { actionKey: 'search' },
    { actionKey: 'readTable' },
  ]),
  'LINEAR_QUERY',
)
assert.equal(
  decidePageAssistantFlowMode([
    { actionKey: 'search' },
    { actionKey: 'reset' },
    { actionKey: 'getPageState' },
  ]),
  'INTENT_ROUTER',
)

const mergedResetClasses = buildIntentClasses([
  { actionKey: 'resetTable', title: '重置表格' },
  { actionKey: 'resetAll', title: '重置全部' },
])
const resetIntent = mergedResetClasses.find((item) => item.id === 'reset_intent')
assert.ok(resetIntent)
assert.equal(resetIntent.actionKeys.length, 2)
assert.ok(resetIntent.actionKeys.includes('resetTable'))
assert.ok(resetIntent.actionKeys.includes('resetAll'))

const mergedOpenRowClasses = buildIntentClasses([
  { actionKey: 'openRowAction', title: '打开A', confirmRequired: true },
  { actionKey: 'openRowDetail', title: '打开B' },
])
assert.equal(mergedOpenRowClasses.filter((item) => item.id === 'row_action_intent').length, 1)
const rowIntent = mergedOpenRowClasses.find((item) => item.id === 'row_action_intent')
assert.ok(rowIntent)
assert.equal(rowIntent.actionKeys.length, 2)
assert.ok(rowIntent.actionKeys.includes('openRowAction'))
assert.ok(rowIntent.actionKeys.includes('openRowDetail'))

const workflowAiCodingPrompt = buildPageAssistantWorkflowAiCodingPrompt({
  toolName: 'Cursor',
  platformUrl: 'http://localhost:18603',
  project: {
    id: 7,
    projectCode: 'bzjs3',
    name: '班组系统',
    registryAppKey: 'bzjs3',
  },
  aiCodingAccess: {
    enabled: true,
    accessKey: 'rac_test_key',
    stateLabel: '已启用',
  },
  sessionId: 'rai_page_123',
  reportUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/rai_page_123/workflow-ai-coding-result',
  page: {
    pageKey: 'teamArchive.list',
    pageName: '班组档案',
    routePattern: '/teams/archive',
  },
  actions: [
    { actionKey: 'search', title: '执行查询' },
    { actionKey: 'reset', title: '重置筛选' },
    { actionKey: 'getPageState', title: '读取页面状态' },
  ],
  requirement: routerRequirement,
  workflowName: '班组档案页面助手',
  workflowKeySlug: 'bzjs3-teamarchive-list-page-assistant',
  modelInstanceId: 'model-1',
  skillPackageUrl: 'http://localhost:18603/api/ai-assist/skills/workflow-ai-coding/latest.zip',
})
assert.match(workflowAiCodingPrompt, /\/api\/workflows\/ai-coding\/workflows/)
assert.match(workflowAiCodingPrompt, /workflowType:\s*PAGE_ASSISTANT|"workflowType": "PAGE_ASSISTANT"/)
assert.match(workflowAiCodingPrompt, /INTENT_CLASSIFIER/)
assert.match(workflowAiCodingPrompt, /strategy=HYBRID/)
assert.match(workflowAiCodingPrompt, /LINEAR_QUERY/)
assert.match(workflowAiCodingPrompt, /INTENT_ROUTER/)
assert.match(workflowAiCodingPrompt, /page-assistant\/validate/)
assert.match(workflowAiCodingPrompt, /Workflow AI Coding 允许发布/)
assert.match(workflowAiCodingPrompt, /\/api\/workflows\/\{workflowId\}\/ai-coding\/publish/)
assert.match(workflowAiCodingPrompt, /不要.*SDK 快速接入/)
assert.match(workflowAiCodingPrompt, /不要.*registerPage/)
assert.match(workflowAiCodingPrompt, /\/api\/ai-assist\/skills\/workflow-ai-coding\/latest\.zip/)
assert.match(workflowAiCodingPrompt, /workflow-ai-coding-result/)
assert.match(workflowAiCodingPrompt, /X-ReachAI-AiCoding-Key/)
assert.doesNotMatch(workflowAiCodingPrompt, /\?aiCodingKey=/)
assert.doesNotMatch(workflowAiCodingPrompt, /query param:\s*`aiCodingKey=/)
assert.match(workflowAiCodingPrompt, /回传.*workflowId|workflowId.*回传/)
assert.match(workflowAiCodingPrompt, /是否已成功回传/)
assert.match(workflowAiCodingPrompt, /runtimeVerification/)
assert.match(workflowAiCodingPrompt, /browserRuntime/)
assert.match(workflowAiCodingPrompt, /browserRuntime\.status=WARN|real page PASS|真实页面执行/)
assert.match(workflowAiCodingPrompt, /Windows \/ PowerShell UTF-8/)
assert.match(workflowAiCodingPrompt, /charset=utf-8/)
assert.match(workflowAiCodingPrompt, /UTF8\.GetBytes/)
assert.match(workflowAiCodingPrompt, /curl\.exe/)
assert.match(workflowAiCodingPrompt, /--data-binary/)
assert.match(workflowAiCodingPrompt, /START ->/)
assert.match(workflowAiCodingPrompt, /-> END/)
assert.match(workflowAiCodingPrompt, /START\/END 是虚拟端点/)
assert.match(workflowAiCodingPrompt, /不要把 START 或 END 放进 nodes/)
assert.match(workflowAiCodingPrompt, /PARAMETER_EXTRACT/)
assert.match(workflowAiCodingPrompt, /extractMode=llm/)
assert.match(workflowAiCodingPrompt, /真实字段名/)
assert.match(workflowAiCodingPrompt, /title\/label\/description\/aliases/)
assert.match(workflowAiCodingPrompt, /全 null\/空字符串/)
assert.match(workflowAiCodingPrompt, /readTable/)
assert.match(workflowAiCodingPrompt, /正在查询/)
assert.match(workflowAiCodingPrompt, /操作当前业务页面/)
assert.match(workflowAiCodingPrompt, /填充当前页面筛选控件/)
assert.match(workflowAiCodingPrompt, /真实业务接口请求参数|real business query request/)
assert.match(workflowAiCodingPrompt, /PAGE_ACTION result status=SUCCESS/)
assert.match(workflowAiCodingPrompt, /business page handler\/query-state binding/)
assert.match(workflowAiCodingPrompt, /不要把 readTable 结果在聊天框里完整渲染成业务列表/)
assert.match(workflowAiCodingPrompt, /不要设计绕过页面的后端 API Tool 查询链/)
assert.doesNotMatch(workflowAiCodingPrompt, /reachai-page-assistant-onboarding\/latest\.zip/)

assert.match(wizardSource, /WORKFLOW_AI_CODING_DRAFT_STEP_KEY|workflow-ai-coding-draft/)
assert.match(wizardSource, /打开 Studio/)
assert.match(wizardSource, /使用该 Workflow 继续/)
assert.match(wizardSource, /useAiCodingWorkflowDraft/)
assert.match(wizardSource, /openAiCodingWorkflowStudio/)
assert.match(wizardSource, /pageAssistantToolUrl/)
assert.match(wizardSource, /-AiCodingKey \$env:REACHAI_AI_CODING_KEY/)
assert.doesNotMatch(wizardSource, /scaffold\?\.scaffoldCommand/)
assert.doesNotMatch(wizardSource, /scaffold\?\.verifyCommand/)
assert.doesNotMatch(wizardSource, /function withAiCodingKey/)
assert.doesNotMatch(wizardSource, /return `\$\{value\}\$\{separator\}aiCodingKey=/)
assert.match(sdkAccessWizardSource, /Workflow AI Coding 允许发布/)
assert.match(sdkAccessWizardSource, /\/api\/workflows\/\{workflowId\}\/ai-coding\/publish/)
assert.match(sdkAccessWizardSource, /首次发布/)

assert.match(wizardSource, /draftSource|DraftSource/)
assert.match(wizardSource, /AI_CODING_RETURNED/)
assert.match(wizardSource, /PLATFORM_GENERATED/)
assert.match(wizardSource, /confirmSwitchToPlatformGeneration/)
assert.match(wizardSource, /改用平台生成/)
assert.match(wizardSource, /不会删除 AI Coding 已创建的 Workflow/)
assert.match(wizardSource, /生成 \/ 选择 Workflow 草稿/)

assert.match(wizardSource, /draftSource\.value = 'AI_CODING_RETURNED'/)
assert.match(wizardSource, /useAiCodingWorkflowDraft[\s\S]*selectStep\('bind'\)/)
assert.doesNotMatch(
  wizardSource.match(/async function useAiCodingWorkflowDraft\(\) \{[\s\S]*?^}/m)?.[0] || 'async function useAiCodingWorkflowDraft() {}',
  /draftPreview\.value = data/,
)

assert.match(wizardSource, /async function generateDraft[\s\S]*confirmSwitchToPlatformGeneration/)
assert.match(wizardSource, /function requiredStepComplete[\s\S]*AI_CODING_RETURNED/)

const workflowAiCodingPromptBlock = wizardSource.match(
  /const workflowAiCodingPrompt = computed\([\s\S]*?\n\}\)\)/,
)?.[0] || ''
assert.ok(workflowAiCodingPromptBlock, 'workflowAiCodingPrompt computed block not found')
assert.match(
  workflowAiCodingPromptBlock,
  /skillPackageUrl:\s*`\$\{window\.location\.origin\}\/api\/ai-assist\/skills\/workflow-ai-coding\/latest\.zip`/,
)
assert.doesNotMatch(
  workflowAiCodingPromptBlock,
  /pageAssistantManifest\.value\?\.(?:endpoints|scaffold)\.skillPackageUrl/,
)

const prompt = buildPageAssistantOnboardingPrompt({
  toolName: 'Cursor',
  platformUrl: 'http://localhost:18603',
  project: {
    id: 7,
    projectCode: 'bzjs3',
    name: '班组系统',
    appKey: 'bzjs3',
  },
  page: {
    pageKey: 'teamArchive.list',
    name: '班组档案',
    routePattern: '/teams/archive',
  },
  actions: [
    {
      actionKey: 'getPageState',
      title: '读取页面状态',
      description: '读取当前筛选条件、分页、表格数据',
      confirmRequired: false,
    },
    {
      actionKey: 'openRowAction',
      title: '打开周期管理',
      description: '打开行内周期管理入口',
      confirmRequired: true,
    },
  ],
  progress: {
    aiCodingAccessKey: 'rai_test_key',
    appSecretEnv: 'REACHAI_REGISTRY_APP_SECRET',
    sessionId: 'rai_page_123',
    manifestUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/onboarding-manifest',
    latestSessionUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/latest',
    stepReportUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/rai_page_123/steps/{stepKey}/report',
    targetBindUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/rai_page_123/target',
    catalogSyncUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/rai_page_123/catalog/sync',
    checksRunUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/sessions/rai_page_123/checks/run',
    registerPageUrl: 'http://localhost:18603/api/ai-coding/projects/7/page-assistant/pages/register',
    skillPackageUrl: 'http://localhost:18603/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip',
    scriptDownloadUrl: 'http://localhost:18603/api/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1',
    helperScriptPath: 'scripts/reachai-page-assistant.ps1',
    scaffoldCommand: '.\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl "http://localhost:18603/api/ai-coding/projects/7/page-assistant/onboarding-manifest" -AiCodingKey $env:REACHAI_AI_CODING_KEY -Framework angular -OutputDir ".\\src\\app\\shared\\reachai"',
    verifyCommand: '.\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl "http://localhost:18603/api/ai-coding/projects/7/page-assistant/onboarding-manifest" -AiCodingKey $env:REACHAI_AI_CODING_KEY -FrontendUrl "http://localhost:9200" -Route "/teams/archive" -PageKey "teamArchive.list"',
    bridgeApiGlobal: 'window.__REACHAI_PAGE_BRIDGE__',
  },
})

assert.match(prompt, /当前任务是“页面助手接入”/)
assert.match(prompt, /项目级 SDK \/ 网关 \/ embed token broker/)
assert.match(prompt, /不要为了简单查询绕过页面去新增后端 API Tool/)
assert.match(prompt, /必须先询问用户确认具体要改造哪一个业务页面/)
assert.match(prompt, /不要把候选页面列表回传 ReachAI 平台/)
assert.match(prompt, /App Key：bzjs3/)
assert.match(prompt, /AI Coding 接入状态：已启用/)
assert.match(prompt, /AI Coding 请求头：X-ReachAI-AiCoding-Key: rai_test_key/)
assert.match(prompt, /外部 AI Coding 鉴权模式/)
assert.match(prompt, /platform-session/)
assert.match(prompt, /同一个可访问 origin/)
assert.match(prompt, /不要把 aiCodingKey 拼进 URL/)
assert.match(prompt, /-AiCodingKey \$env:REACHAI_AI_CODING_KEY/)
assert.doesNotMatch(prompt, /\?aiCodingKey=/)
assert.doesNotMatch(
  source,
  /\/api\/ai-assist\/\*\*|page-assistant \/ ai-assist/,
  'Page Assistant external-tool prompt source must not describe project APIs as /api/ai-assist/**',
)
assert.doesNotMatch(
  prompt,
  /\/api\/ai-assist\/\*\*|page-assistant \/ ai-assist/,
  'Page Assistant external-tool prompt must use ai-coding project gateway wording',
)
assert.match(
  source,
  /\/api\/ai-coding\/projects\/\{projectId\}\/page-assistant\/\*\*/,
  'Page Assistant external-tool prompt source should name the project-scoped ai-coding gateway path',
)
assert.doesNotMatch(
  pageAssistantSkillSource,
  /`\/api\/ai-assist\/\*\*` page-assistant|Page Assistant `\/api\/ai-assist\/\*\*`/,
  'Packaged Page Assistant skill must not describe external project APIs as /api/ai-assist/**',
)
assert.match(
  pageAssistantSkillSource,
  /\/api\/ai-coding\/projects\/\{projectId\}\/page-assistant\/\*\*/,
  'Packaged Page Assistant skill should name the project-scoped ai-coding gateway path',
)
assert.match(prompt, /App Secret 环境变量：REACHAI_REGISTRY_APP_SECRET/)
assert.match(prompt, /页面助手接入清单 URL/)
assert.match(prompt, /步骤进度回传 URL/)
assert.match(prompt, /目标页绑定 URL/)
assert.match(prompt, /页面动作目录同步 URL/)
assert.match(prompt, /页面助手自检 URL/)
assert.match(prompt, /页面一键注册 URL/)
assert.match(prompt, /registerPage/)
assert.match(prompt, /本机 PowerShell\/curl/)
assert.match(prompt, /远端 WebFetch/)
assert.match(prompt, /第一步（helper script 落盘）/)
assert.match(prompt, /scriptDownloadUrl/)
assert.match(prompt, /skillPackageUrl/)
assert.match(prompt, /scripts\/reachai-page-assistant\.ps1/)
assert.match(prompt, /bridgeApi/)
assert.match(prompt, /register \/ unregisterPage \/ execute \/ list/)
assert.match(prompt, /files 推荐对象数组/)
assert.match(prompt, /字符串简写/)
assert.match(prompt, /browser-verify-static/)
assert.match(prompt, /browser-verify-runtime/)
assert.match(prompt, /static 检查不等于 runtime 检查/)
assert.match(prompt, /static only/)
assert.match(prompt, /runtime PASS 必须来自真实 bridge invoke/)
assert.match(prompt, /HASH_MISSING/)
assert.match(prompt, /ProbeMutatingActions/)
assert.match(prompt, /StorageState/)
assert.match(prompt, /platformCheck/)
assert.match(prompt, /redactedResults/)
assert.match(prompt, /getPageState\/readTable/)
assert.match(prompt, /不要自动执行 openRowAction/)
assert.match(prompt, /Skill 包下载 URL/)
assert.match(prompt, /Helper script 下载 URL/)
assert.match(prompt, /第一步/)
assert.match(prompt, /reachai-page-assistant\.ps1 scaffold/)
assert.match(prompt, /reachai-page-assistant\.ps1 verify/)
assert.match(prompt, /window\.__REACHAI_PAGE_BRIDGE__/)
assert.match(prompt, /\/repository\/\*\*/)
assert.match(prompt, /\/npm\/\*\*/)
assert.match(prompt, /Maven repository/)
assert.match(prompt, /绑定目标页时/)
assert.match(prompt, /同步目录时/)
assert.match(prompt, /page-assistant/)
assert.match(prompt, /PAGE_COPILOT/)
assert.match(prompt, /PAGE_ASSISTANT Workflow/)
assert.match(prompt, /ai_agent_workflow_binding/)
assert.match(prompt, /Workflow Studio/)
assert.match(prompt, /POST \/api\/embed\/chat\/sessions\/\{sessionId\}\/messages/)
assert.match(prompt, /"message": "用户输入的自然语言问题"/)
assert.match(prompt, /Do not send chat requests with content, text, or question as the top-level field/)
assert.match(prompt, /顶层 message=success/)
assert.match(prompt, /data\.answer/)
assert.match(prompt, /data\.metadata\.pageActionQueue/)
assert.match(prompt, /data\.uiRequest/)
assert.match(prompt, /\/page-actions\/\{requestId\}\/result/)
assert.match(prompt, /实际助手回复/)
assert.match(prompt, /route/)
assert.match(prompt, /页面组件文件/)
assert.match(prompt, /筛选表单字段/)
assert.match(prompt, /查询 \/ 重置按钮/)
assert.match(prompt, /表格列/)
assert.match(prompt, /分页/)
assert.match(prompt, /行操作按钮/)
assert.match(prompt, /getPageState/)
assert.match(prompt, /setFilters/)
assert.match(prompt, /search/)
assert.match(prompt, /reset/)
assert.match(prompt, /readTable/)
assert.match(prompt, /操作当前页面/)
assert.match(prompt, /真实写入当前页面可见筛选控件/)
assert.match(prompt, /@zhongruigroup\/ngx-query/)
assert.match(prompt, /zr-table/)
assert.match(prompt, /queryTemplates\[0\]\.template\.rules/)
assert.match(prompt, /event\.page\.filters\[\]\.term/)
assert.match(prompt, /不要让助手绕过页面直接调用业务后端查询接口/)
assert.match(prompt, /聊天框只做简短确认和摘要/)
assert.match(prompt, /当前页面筛选条件/)
assert.match(prompt, /页面表格不变，不能回传 PASS/)
assert.match(prompt, /真实业务查询请求/)
assert.match(prompt, /handler 返回 SUCCESS 也必须判定为 FAIL/)
assert.match(prompt, /真实业务请求仍是未筛选参数/)
assert.match(prompt, /PAGE_ACTION\(setFilters\) args/)
assert.match(prompt, /setFilters 后的页面 DOM\/filters/)
assert.match(prompt, /search 触发的真实业务请求参数/)
assert.match(prompt, /openRowAction/)
assert.match(prompt, /新增、编辑、删除、审批、导出/)
assert.match(prompt, /不要绕过页面权限/)
assert.match(prompt, /最小构建 \/ 类型检查/)
assert.match(prompt, /page-manifest/)
assert.match(prompt, /route-detection/)
assert.match(prompt, /handoff-summary/)
assert.match(prompt, /aiCodingKey 只能作为 X-ReachAI-AiCoding-Key 请求头用于 AI 工具/)
assert.match(prompt, /浏览器运行时代码不得调用这些接口/)
assert.doesNotMatch(prompt, /secret-must-not-leak/)
assert.doesNotMatch(prompt, /App Secret：/)

const frontendSnippetBlock = sdkAccessWizardSource.match(
  /const frontendSnippet = computed\(\(\) => \{[\s\S]*?return `([\s\S]*?)`\s*\n\}\)/,
)?.[1] || ''
assert.ok(frontendSnippetBlock, 'SdkAccessWizard frontendSnippet template not found')
assert.match(frontendSnippetBlock, /Browser runtime must NOT call \/api\/ai-coding\/projects\/\*\*/)
assert.match(frontendSnippetBlock, /provisionedAgentKeySlug/)
assert.match(frontendSnippetBlock, /tokenProvider/)
assert.doesNotMatch(frontendSnippetBlock, /aiCodingKey=/)
assert.doesNotMatch(frontendSnippetBlock, /provisionAgentUrl/)
assert.doesNotMatch(frontendSnippetBlock, /\/agents\/provision/)
assert.doesNotMatch(frontendSnippetBlock, /fetch\([^)]*ai-assist/)
assert.doesNotMatch(
  sdkAccessWizardSource,
  /Browser runtime must NOT call \/api\/ai-assist\/\*\*|fetch \/api\/ai-assist\/\*\*（含 agents\/provision/,
  'SdkAccessWizard browser runtime safety text must not describe external project APIs as /api/ai-assist/**',
)
assert.match(
  sdkAccessWizardSource,
  /\/api\/ai-coding\/projects\/\*\*/,
  'SdkAccessWizard browser runtime safety text should name the project-scoped ai-coding gateway path',
)
assert.doesNotMatch(
  onboardingSecuritySource,
  /Browser runtime must not call `\/api\/ai-assist\/\*\*` provisioning/,
  'Packaged SDK onboarding security reference must not describe external project APIs as /api/ai-assist/**',
)
assert.match(
  onboardingSecuritySource,
  /\/api\/ai-coding\/projects\/\{projectId\}\/(?:onboarding-manifest|agents\/provision|access-sessions)/,
  'Packaged SDK onboarding security reference should name project-scoped ai-coding gateway paths',
)
assert.match(sdkAccessWizardSource, /运行时浏览器不得调用 provisioning API/)
assert.match(sdkAccessWizardSource, /浏览器运行时代码不得 fetch \/api\/ai-coding\/projects\/\*\*/)
assert.match(sdkAccessWizardSource, /@ReachOutput\s*只用于返回 DTO 字段/)
assert.match(sdkAccessWizardSource, /CODE_READY[\s\S]*RUNTIME_READY[\s\S]*E2E_READY/)
assert.match(sdkAccessWizardSource, /网关接入必查 5 项/)
assert.match(sdkAccessWizardSource, /前端 :9200[\s\S]*网关 :8080[\s\S]*ReachAI :18603/)
assert.match(onboardingSkillSource, /`?@ReachOutput`?\s+is field-only/)
assert.match(onboardingSkillSource, /CODE_READY[\s\S]*RUNTIME_READY[\s\S]*E2E_READY/)
assert.match(onboardingJavaSdkAccessSource, /Gateway checklist/)
assert.match(onboardingJavaSdkAccessSource, /Local dev topology/)

assert.doesNotMatch(
  sdkAccessWizardSource,
  /appendQuery\(platformManifestUrl,\s*'aiCodingKey'/,
  'SdkAccessWizard onboarding prompt must not put aiCodingKey into the manifest URL',
)
assert.doesNotMatch(
  sdkAccessWizardSource,
  /appendQuery\(fallbackProvisionAgentUrl,\s*'aiCodingKey'/,
  'SdkAccessWizard onboarding prompt must not put aiCodingKey into the provisioning URL',
)
assert.doesNotMatch(
  sdkAccessWizardSource,
  /reportUrlPatternWithKey|sessionCheckUrlWithKey/,
  'SdkAccessWizard onboarding prompt must use header-auth progress/check URLs',
)
assert.doesNotMatch(
  sdkAccessWizardSource,
  /latestSessionUrl\s*=[\s\S]*appendQuery\([^)]*access-sessions\/latest[\s\S]*?'aiCodingKey'/,
  'SdkAccessWizard onboarding prompt must not put aiCodingKey into latest-session URL',
)
assert.match(
  sdkAccessWizardSource,
  /X-ReachAI-AiCoding-Key/,
  'SdkAccessWizard onboarding prompt must instruct external tools to send the AI Coding key as a header',
)
assert.match(
  sdkAccessWizardSource,
  /externalProjectRoot\s*=\s*`\$\{platformUrl\}\/api\/ai-coding\/projects\/\$\{projectId\}`/,
  'SdkAccessWizard external tool manifest URL should use the AI Coding gateway project alias',
)
assert.match(
  sdkAccessWizardSource,
  /`\$\{externalProjectRoot\}\/access-sessions\/\$\{sessionId\}\/checks\/run`/,
  'SdkAccessWizard external tool progress/check URLs should use the AI Coding gateway project alias',
)
assert.match(
  wizardSource,
  /\/api\/ai-coding\/projects\/\$\{pageAssistantProjectIdForTool\.value\}\/page-assistant/,
  'PageAssistantWizard external tool URLs should use the AI Coding gateway project alias',
)
assert.match(
  wizardSource,
  /\/api\/ai-coding\/projects\/\$\{project\.value\.id\}\/page-assistant\/sessions\/\$\{sessionId\}\/workflow-ai-coding-result/,
  'PageAssistant workflow AI Coding report URL should use the AI Coding gateway project alias',
)
assert.doesNotMatch(
  scanProjectApiSource,
  /aiCodingKey\?:/,
  'Front-end API helpers must not expose aiCodingKey query parameters for external AI Coding calls',
)
assert.doesNotMatch(
  scanProjectApiSource,
  /params:\s*aiCodingKey\s*\?/,
  'Front-end API helpers must not pass aiCodingKey as request query params',
)
assert.doesNotMatch(
  scanProjectTypesSource,
  /queryParam:\s*string/,
  'AI Coding Gateway manifest type must not expose queryParam auth discovery',
)
assert.match(
  scanProjectTypesSource,
  /externalToolPath\?:\s*string/,
  'Page Assistant manifest type should expose the external AI Coding tool path',
)
assert.match(
  scanProjectTypesSource,
  /platformSessionPath\?:\s*string/,
  'Page Assistant manifest type should expose the platform-session console path',
)
assert.doesNotMatch(
  sdkAccessWizardSource,
  /stripQueryParam\([^)]*aiCodingKey|function stripQueryParam/,
  'SdkAccessWizard must not keep legacy aiCodingKey URL stripping helpers',
)
assert.doesNotMatch(
  wizardSource,
  /stripAiCodingKeyQuery/,
  'PageAssistantWizard must not keep legacy aiCodingKey URL stripping helpers',
)
assert.doesNotMatch(
  workflowAiCodingPromptSource,
  /stripAiCodingKeyQuery/,
  'PageAssistant workflow AI Coding prompt must not keep legacy aiCodingKey URL stripping helpers',
)

assert.doesNotMatch(wizardSource, /Agent Studio/)
assert.doesNotMatch(wizardSource, /\/agent\/new\/studio/)
assert.doesNotMatch(wizardSource, /page-assistant-draft/)
assert.match(wizardSource, /确认草稿/)
assert.match(wizardSource, /挂载智能体/)
assert.match(wizardSource, /bindPageAssistantWorkflow/)
assert.match(wizardSource, /Workflow Studio/)
assert.match(wizardSource, /\/workflows\/\$\{workflowId\}\/studio/)

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../..')
const helperScriptPaths = [
  join(repoRoot, 'scripts/reachai-page-assistant.ps1'),
  join(
    repoRoot,
    'reachai-control-service/src/main/resources/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1',
  ),
]

function extractRuntimeProbeNodeScript(ps1Source) {
  const match = ps1Source.match(/function Get-RuntimeProbeNodeScript \{\s*@'([\s\S]*?)'@\s*\}/)
  assert.ok(match, 'Get-RuntimeProbeNodeScript heredoc not found in helper script')
  return match[1]
}

for (const scriptPath of helperScriptPaths) {
  const helperSource = readFileSync(scriptPath, 'utf8')
  assert.doesNotMatch(
    helperSource,
    /event\.source\?\.postMessage/,
    `${scriptPath} should not use event.source?.postMessage because Angular 12 DOM types can reject MessageEventSource`,
  )
  assert.match(
    helperSource,
    /const target = event\.source as \{ postMessage: \(message: unknown, targetOrigin: string\) => void \} \| null;/,
    `${scriptPath} should cast MessageEvent.source to a postMessage-capable target`,
  )
  assert.match(
    helperSource,
    /reachai-runtime-probe-[\s\S]*?\.cjs/,
    `${scriptPath} should write runtime probe temp script as .cjs`,
  )
  assert.match(
    helperSource,
    /\[string\] \$AiCodingKey = \$env:REACHAI_AI_CODING_KEY/,
    `${scriptPath} should accept AiCodingKey from REACHAI_AI_CODING_KEY`,
  )
  assert.match(
    helperSource,
    /X-ReachAI-AiCoding-Key/,
    `${scriptPath} should send the AI Coding key as a header`,
  )
  assert.match(
    helperSource,
    /failureCode/,
    `${scriptPath} should return structured runtime failureCode values`,
  )
  assert.match(
    helperSource,
    /JSON_PARSE_ERROR/,
    `${scriptPath} should classify non-JSON probe output as JSON_PARSE_ERROR`,
  )
  assert.match(
    helperSource,
    /LOGIN_REQUIRED/,
    `${scriptPath} should classify likely login/session blockers as LOGIN_REQUIRED`,
  )
  assert.match(
    helperSource,
    /FRONTEND_UNREACHABLE/,
    `${scriptPath} should classify unreachable frontend probes as FRONTEND_UNREACHABLE`,
  )
  assert.match(
    helperSource,
    /rawOutput/,
    `${scriptPath} should include bounded rawOutput for probe parse failures`,
  )
  assert.doesNotMatch(
    helperSource,
    /reachai-runtime-probe-[\s\S]*?\.mjs/,
    `${scriptPath} must not write runtime probe temp script as .mjs`,
  )
}

const probeScript = extractRuntimeProbeNodeScript(readFileSync(helperScriptPaths[0], 'utf8'))
const probeDir = mkdtempSync(join(tmpdir(), 'reachai-runtime-probe-'))
const probeScriptPath = join(probeDir, 'probe.cjs')
writeFileSync(probeScriptPath, probeScript, 'utf8')

const probeArgs = JSON.stringify({
  targetUrl: 'http://127.0.0.1:1',
  pageKey: 'smoke.test',
  bridgeGlobal: '__REACHAI_PAGE_BRIDGE__',
  readonlyActions: ['getPageState'],
  timeoutMs: 500,
})

let probeStdout = ''
let probeStderr = ''
try {
  probeStdout = execFileSync('node', [probeScriptPath, probeArgs], {
    encoding: 'utf8',
    timeout: 30000,
  })
} catch (error) {
  probeStdout = error.stdout?.toString() || ''
  probeStderr = error.stderr?.toString() || ''
  if (!probeStdout.trim()) {
    throw error
  }
}

const probeCombined = `${probeStdout}${probeStderr}`
assert.doesNotMatch(
  probeCombined,
  /require is not defined/i,
  'runtime probe CommonJS script must not fail with ESM require error when saved as .cjs',
)

const probeResult = JSON.parse(probeStdout.trim())
assert.ok(
  probeResult.code === 'PLAYWRIGHT_MISSING'
    || typeof probeResult.message === 'string'
    || typeof probeResult.ok === 'boolean',
  'runtime probe should return JSON (PLAYWRIGHT_MISSING or probe result), not module format errors',
)

const brokenProbePath = join(probeDir, 'probe.mjs')
writeFileSync(brokenProbePath, probeScript, 'utf8')
let brokenProbeOutput = ''
try {
  brokenProbeOutput = execFileSync('node', [brokenProbePath, probeArgs], {
    encoding: 'utf8',
    timeout: 5000,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
} catch (error) {
  brokenProbeOutput = `${error.stdout?.toString() || ''}${error.stderr?.toString() || ''}`
}
assert.match(
  brokenProbeOutput,
  /require is not defined/i,
  'sanity check: same CommonJS probe script saved as .mjs should reproduce the original failure mode',
)

rmSync(probeDir, { recursive: true, force: true })

console.log('page assistant onboarding prompt assertions passed')
