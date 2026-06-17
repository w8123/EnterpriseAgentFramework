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
const source = readFileSync(modulePath, 'utf8')
const wizardSource = readFileSync(join(process.cwd(), 'src/views/registry/PageAssistantWizard.vue'), 'utf8')
const compiled = ts.transpileModule(source, {
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

const { buildPageAssistantOnboardingPrompt } = sandbox.exports
assert.equal(typeof buildPageAssistantOnboardingPrompt, 'function')

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
    manifestUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/onboarding-manifest?aiCodingKey=rai_test_key',
    latestSessionUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/sessions/latest?aiCodingKey=rai_test_key',
    stepReportUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/sessions/rai_page_123/steps/{stepKey}/report?aiCodingKey=rai_test_key',
    targetBindUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/sessions/rai_page_123/target?aiCodingKey=rai_test_key',
    catalogSyncUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/sessions/rai_page_123/catalog/sync?aiCodingKey=rai_test_key',
    checksRunUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/sessions/rai_page_123/checks/run?aiCodingKey=rai_test_key',
    registerPageUrl: 'http://localhost:18603/api/ai-assist/projects/7/page-assistant/pages/register?aiCodingKey=rai_test_key',
    skillPackageUrl: 'http://localhost:18603/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip',
    scriptDownloadUrl: 'http://localhost:18603/api/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1',
    helperScriptPath: 'scripts/reachai-page-assistant.ps1',
    scaffoldCommand: '.\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl "http://localhost:18603/api/ai-assist/projects/7/page-assistant/onboarding-manifest?aiCodingKey=rai_test_key" -Framework angular -OutputDir ".\\src\\app\\shared\\reachai"',
    verifyCommand: '.\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl "http://localhost:18603/api/ai-assist/projects/7/page-assistant/onboarding-manifest?aiCodingKey=rai_test_key" -FrontendUrl "http://localhost:9200" -Route "/teams/archive" -PageKey "teamArchive.list"',
    bridgeApiGlobal: 'window.__REACHAI_PAGE_BRIDGE__',
  },
})

assert.match(prompt, /当前任务是“页面助手接入”/)
assert.match(prompt, /项目级 SDK \/ 网关 \/ embed token broker/)
assert.match(prompt, /不要为了简单查询绕过页面去新增后端 API Tool/)
assert.match(prompt, /必须先询问用户确认具体要改造哪一个业务页面/)
assert.match(prompt, /不要把候选页面列表回传 ReachAI 平台/)
assert.match(prompt, /App Key：bzjs3/)
assert.match(prompt, /AI Coding 接入秘钥：rai_test_key/)
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
assert.match(prompt, /openRowAction/)
assert.match(prompt, /新增、编辑、删除、审批、导出/)
assert.match(prompt, /不要绕过页面权限/)
assert.match(prompt, /最小构建 \/ 类型检查/)
assert.match(prompt, /page-manifest/)
assert.match(prompt, /route-detection/)
assert.match(prompt, /handoff-summary/)
assert.doesNotMatch(prompt, /secret-must-not-leak/)
assert.doesNotMatch(prompt, /App Secret：/)

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
    'ai-agent-service/src/main/resources/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1',
  ),
]

function extractRuntimeProbeNodeScript(ps1Source) {
  const match = ps1Source.match(/function Get-RuntimeProbeNodeScript \{\s*@'([\s\S]*?)'@\s*\}/)
  assert.ok(match, 'Get-RuntimeProbeNodeScript heredoc not found in helper script')
  return match[1]
}

for (const scriptPath of helperScriptPaths) {
  const helperSource = readFileSync(scriptPath, 'utf8')
  assert.match(
    helperSource,
    /reachai-runtime-probe-[\s\S]*?\.cjs/,
    `${scriptPath} should write runtime probe temp script as .cjs`,
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
