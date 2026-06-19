import {
  buildPageAssistantFlowPlan,
  type PageActionFlowInput,
  type PageAssistantFlowPlan,
} from './pageAssistantDraftRequirement'

export interface PageAssistantWorkflowAiCodingPromptProject {
  id?: number | string | null
  projectCode?: string | null
  name?: string | null
  registryAppKey?: string | null
}

export interface PageAssistantWorkflowAiCodingPromptPage {
  pageKey?: string | null
  pageName?: string | null
  routePattern?: string | null
}

export interface PageAssistantWorkflowAiCodingPromptAction {
  actionKey?: string | null
  title?: string | null
  description?: string | null
  confirmRequired?: boolean | null
}

export interface PageAssistantWorkflowAiCodingPromptAccess {
  enabled?: boolean | null
  accessKey?: string | null
  stateLabel?: string | null
}

export interface PageAssistantWorkflowAiCodingPromptContext {
  toolName: 'Cursor' | 'Codex' | 'Claude Code' | string
  platformUrl: string
  project: PageAssistantWorkflowAiCodingPromptProject
  aiCodingAccess?: PageAssistantWorkflowAiCodingPromptAccess | null
  sessionId?: string | null
  reportUrl?: string | null
  page: PageAssistantWorkflowAiCodingPromptPage
  actions: PageAssistantWorkflowAiCodingPromptAction[]
  requirement: string
  workflowName: string
  workflowKeySlug: string
  modelInstanceId?: string | null
  skillPackageUrl?: string | null
}

function line(value: unknown, fallback = '未提供') {
  const text = value == null ? '' : String(value).trim()
  return text || fallback
}

function formatActions(actions: PageAssistantWorkflowAiCodingPromptAction[]) {
  if (!actions.length) return '- （无）'
  return actions.map((action) => {
    const parts = [
      `- actionKey: ${line(action.actionKey)}`,
      `title: ${line(action.title, action.actionKey || '未命名')}`,
    ]
    if (action.description?.trim()) parts.push(`description: ${action.description.trim()}`)
    if (action.confirmRequired) parts.push('confirmRequired: true')
    return parts.join(' | ')
  }).join('\n')
}

function formatFlowPlan(plan: PageAssistantFlowPlan) {
  const lines = [
    `- flowMode: ${plan.flowMode}`,
    `- selectedActionKeys: ${plan.selectedActionKeys.join('、') || '无'}`,
    `- recommendedFlow: ${plan.recommendedFlow.join(' -> ')}`,
    `- requiredGraphFlow: START -> ${plan.recommendedFlow.join(' -> ')} -> END`,
  ]
  if (plan.intentClasses.length) {
    lines.push('- intentClasses:')
    lines.push(JSON.stringify(plan.intentClasses, null, 2))
  }
  if (plan.safetyNotes.length) {
    lines.push('- safetyNotes:')
    plan.safetyNotes.forEach((note) => lines.push(`  - ${note}`))
  }
  return lines.join('\n')
}

function buildCreateBodyExample(context: PageAssistantWorkflowAiCodingPromptContext, plan: PageAssistantFlowPlan) {
  const actionKeys = context.actions
    .map((action) => action.actionKey)
    .filter((key): key is string => Boolean(key))
  return {
    name: context.workflowName,
    keySlug: context.workflowKeySlug,
    projectId: context.project.id,
    projectCode: context.project.projectCode,
    workflowType: 'PAGE_ASSISTANT',
    runtimeType: 'LANGGRAPH4J',
    defaultModelInstanceId: context.modelInstanceId || undefined,
    description: context.requirement,
    extra: {
      pageAssistant: {
        source: 'PAGE_ASSISTANT_WIZARD',
        pageKey: context.page.pageKey,
        pageName: context.page.pageName,
        routePattern: context.page.routePattern,
        actionKeys,
        flowMode: plan.flowMode,
      },
    },
    reason: 'Create PAGE_ASSISTANT workflow draft from Page Assistant Wizard AI Coding prompt',
  }
}

export function buildPageAssistantWorkflowAiCodingPrompt(context: PageAssistantWorkflowAiCodingPromptContext) {
  const toolName = line(context.toolName, 'Cursor')
  const platformUrl = line(context.platformUrl, 'http://localhost:8080')
  const access = context.aiCodingAccess || {}
  const accessKey = access.enabled && access.accessKey?.trim() ? access.accessKey.trim() : ''
  const accessState = line(access.stateLabel, access.enabled ? '已启用' : '未启用')
  const skillPackageUrl = line(
    context.skillPackageUrl,
    `${platformUrl}/api/ai-assist/skills/workflow-ai-coding/latest.zip`,
  )
  const flowActions: PageActionFlowInput[] = context.actions
    .filter((action) => Boolean(action.actionKey))
    .map((action) => ({
      actionKey: String(action.actionKey),
      title: action.title || undefined,
      confirmRequired: Boolean(action.confirmRequired),
    }))
  const flowPlan = buildPageAssistantFlowPlan(flowActions)
  const createBody = buildCreateBodyExample(context, flowPlan)
  const sessionId = line(context.sessionId, '未提供（请先在 ReachAI 页面助手向导打开 AI Coding 弹窗以创建 session）')
  const reportUrl = line(
    context.reportUrl,
    `${platformUrl}/api/ai-assist/projects/${line(context.project.id)}/page-assistant/sessions/{sessionId}/workflow-ai-coding-result?aiCodingKey=<项目 AI Coding 秘钥>`,
  )
  const graphRules = flowPlan.flowMode === 'LINEAR_QUERY'
    ? `- 当前 flowMode=LINEAR_QUERY：不要创建 INTENT_CLASSIFIER。
- 按 recommendedFlow 串联 USER_INPUT ->（可选 LLM 提取 setFilters）-> PAGE_ACTION(setFilters/search/readTable...) -> ANSWER。
- 标准查询链只包含 setFilters/search/readTable 的子集；不要线性串联 reset/getPageState/openRowAction 等互斥动作。`
    : `- 当前 flowMode=INTENT_ROUTER：必须创建 INTENT_CLASSIFIER，strategy=HYBRID，inputExpression=input，defaultRoute=else。
- 每个 route:<classId> 只执行对应 intentClass.actionKeys 链路；互斥动作禁止线性串联。
- route:else 必须连接 ANSWER，提示用户说明要查询、重置、读取状态还是执行操作。
- confirmRequired=true 的操作型动作，在对应 route 分支里先 INTERACTION(confirm_action)，再 PAGE_ACTION。`

  return `# 使用 Workflow AI Coding 创建 PAGE_ASSISTANT Workflow（${toolName}）

你是 ${toolName}。当前任务是通过 ReachAI **Workflow AI Coding REST API** 创建并完善 PAGE_ASSISTANT Workflow 草稿。

## 重要边界

- 只做 Workflow AI Coding：创建 Workflow、patch GraphSpec、validate、可选 dry-run / smoke-test。
- **不要**修改业务前端页面动作接入代码，**不要**走 SDK 快速接入，**不要**调用 page-assistant onboarding / registerPage / bridge scaffold。
- **不要**直接改数据库，**不要**调用 publish；Workflow AI Coding **不允许 publish**，最后只汇报 workflowId、校验结果和剩余问题，让用户回到 Studio 人工确认/发布。
- GraphSpec 是运行语义；canvas 只是布局。

## 项目与认证

- projectId: ${line(context.project.id)}
- projectCode: ${line(context.project.projectCode)}
- 项目名称: ${line(context.project.name)}
- registryAppKey: ${line(context.project.registryAppKey)}
- page-assistant sessionId: ${sessionId}
- 平台地址: ${platformUrl}
- AI Coding 接入状态: ${accessState}
${accessKey ? `- AI Coding 接入秘钥: ${accessKey}` : '- AI Coding 接入秘钥: 未启用或未生成；请先在 ReachAI 项目详情启用后再调用 API'}
- 认证方式（每个 Workflow AI Coding 请求都必须带）:
  - query param: \`aiCodingKey=<项目 AI Coding 秘钥>\`
  - 或 header: \`X-ReachAI-AiCoding-Key: <项目 AI Coding 秘钥>\`
- 不要使用平台 Bearer / 登录 Cookie 调用 \`/api/workflows/**/ai-coding/**\`。
- Workflow AI Coding 结果回传 URL（创建/validate 完成后必须调用）:
  - \`POST ${reportUrl}\`
  - 认证与上面相同：query \`aiCodingKey=...\` 或 header \`X-ReachAI-AiCoding-Key\`

## Windows / PowerShell UTF-8 硬约束

如果在 Windows 上调用 ReachAI Workflow AI Coding API，必须避免 PowerShell 默认编码导致中文变成 ????：

1. 优先使用 PowerShell 7+（pwsh）。
2. 脚本开头显式设置：

       [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
       [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
       $OutputEncoding = [System.Text.UTF8Encoding]::new($false)

3. 所有复杂 JSON body 必须先写入独立 .json 文件，文件编码为 UTF-8。
4. 使用 curl.exe，不要使用 PowerShell alias curl：

       curl.exe -X POST $url -H "Content-Type: application/json; charset=utf-8" --data-binary "@request.json"

5. 如果用 Invoke-RestMethod，不要直接传普通字符串 body；必须转 UTF-8 bytes：

       $json = Get-Content .\\request.json -Raw -Encoding utf8
       $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
       Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json; charset=utf-8" -Body $bodyBytes

6. 生成脚本文件时保存为 UTF-8；PowerShell 5.1 下写文件必须显式指定 UTF-8，避免 UTF-16/ANSI。
7. 禁止把包含中文的 JSON 直接内联拼在命令行里发送。

## Skill

1. 先安装/阅读 workflow-ai-coding skill。
2. Skill 包下载: ${skillPackageUrl}

## 当前页面助手上下文

- pageKey: ${line(context.page.pageKey)}
- pageName: ${line(context.page.pageName)}
- routePattern: ${line(context.page.routePattern)}
- Workflow 名称: ${line(context.workflowName)}
- Workflow keySlug: ${line(context.workflowKeySlug)}
- defaultModelInstanceId: ${line(context.modelInstanceId, '未选择，创建后从 context.availableModels 选择')}

### 已选 pageActions

${formatActions(context.actions)}

### 生成要求（requirement）

${context.requirement.trim() || '（未填写）'}

### Flow 规划（来自向导判定，不要自行重写）

${formatFlowPlan(flowPlan)}

## 推荐执行步骤

1. \`POST ${platformUrl}/api/workflows/ai-coding/workflows?aiCodingKey=...\`
   - Body 示例:

\`\`\`json
${JSON.stringify(createBody, null, 2)}
\`\`\`

2. \`GET ${platformUrl}/api/workflows/{workflowId}/ai-coding/context?aiCodingKey=...\`
   - 读取 nodeTypes、availableModels、availableTools、validation、pageAssistantContext。

3. 根据 flowMode patch GraphSpec（先 dryRun=true）:
   - \`POST ${platformUrl}/api/workflows/{workflowId}/ai-coding/patch?aiCodingKey=...\`
   - 只使用已选 actionKeys 绑定 PAGE_ACTION；不要发明未选中的 actionKey。
   - ${graphRules}

4. 校验:
   - \`POST ${platformUrl}/api/workflows/{workflowId}/ai-coding/validate?aiCodingKey=...\`
   - \`POST ${platformUrl}/api/workflows/{workflowId}/ai-coding/page-assistant/validate?aiCodingKey=...\`

5. Business page runtime verification (required when the business frontend is reachable):
   - Platform validate / page-assistant validate passing does **not** prove real page execution.
   - If you can run the business frontend, open the target page with a browser or Playwright, verify the page bridge exists, and verify the selected actionKeys are registered.
   - For safe actions, invoke the handler and observe page state changes. For confirmRequired=true or high-risk actions, verify confirm/NEED_CONFIRM/action presence only; do not submit dangerous side effects.
   - If real browser verification is unavailable, report \`runtimeVerification.browserRuntime.status="WARN"\` with the reason. Do not claim real page PASS.

6. 可选调试（安全优先）:
   - \`POST ${platformUrl}/api/workflows/{workflowId}/ai-coding/run?aiCodingKey=...\`（可先 dryRun=true）
   - \`POST ${platformUrl}/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test?aiCodingKey=...\`

7. 保存 patch（dryRun=false，baseRevision 对齐 context.workflow.updatedAt）后停止。

8. 回传结果到页面助手向导（**必须执行，且仍然不要 publish**）:
   - \`POST ${reportUrl}\`
   - Body 示例:

\`\`\`json
{
  "workflowId": "<创建得到的 workflowId>",
  "keySlug": "${line(context.workflowKeySlug)}",
  "workflowName": "${line(context.workflowName)}",
  "status": "WARN",
  "message": "PAGE_ASSISTANT Workflow 草稿已通过 validate 与 page-assistant/validate；真实业务页面浏览器验证未执行",
  "validation": {
    "overallStatus": "PASS",
    "errors": [],
    "warnings": []
  },
  "pageAssistantValidation": {
    "overallStatus": "PASS",
    "matchedActions": [],
    "missingActions": [],
    "warnings": []
  },
  "runtimeVerification": {
    "browserRuntime": {
      "status": "WARN",
      "message": "Use PASS only after real business frontend browser verification. If browser verification is unavailable, keep WARN and explain why.",
      "pageUrl": "",
      "checkedActions": []
    }
  },
  "studioUrl": "/workflows/<workflowId>/studio"
}
\`\`\`

   - \`status\` 取 validate、page-assistant/validate 与 runtimeVerification 的综合结果（PASS/WARN/FAIL）；无法做真实业务页面浏览器验证时不要回传 PASS。
   - 回传成功后，用户在 ReachAI 页面助手向导可看到 workflowId 并继续挂载智能体。

## GraphSpec 关键规则

- START/END 是虚拟端点，不是 nodeTypes 里的节点；不要把 START 或 END 放进 nodes。
- 必须创建真实 USER_INPUT 节点，并设置 graphSpec.entry 为该 USER_INPUT 节点 id。
- 必须添加 START -> <USER_INPUT节点id> 连线，condition=always；否则 Studio 会显示缺少开始节点。
- 每个终态分支最后都必须连接到 END；也就是每条 route 最后一个 ANSWER/PAGE_ACTION/INTERACTION 后必须有 <lastNode> -> END 连线。
- 不要创建普通 id=end/type=END 节点；结束只能通过虚拟端点 END 表达，Studio 会映射为结束节点。
- 需要从自然语言提取筛选/查询参数时，使用 PARAMETER_EXTRACT，且 config.extractMode=llm，modelInstanceId 使用 defaultModelInstanceId 或 context.availableModels 中的 ACTIVE LLM。
- LINEAR_QUERY：不要创建 INTENT_CLASSIFIER。
- INTENT_ROUTER：必须创建 INTENT_CLASSIFIER；strategy=HYBRID；classes 含 id/label/description/keywords；defaultRoute=else。
- 连线使用 \`route:<classId>\` 或 \`route:else\`。
- PAGE_ACTION config 必须绑定真实 actionKey（来自 pageActions / catalog）。

## 最终汇报

只返回：
- workflowId / keySlug
- validate 与 page-assistant/validate 结果
- runtimeVerification.browserRuntime summary; explicitly say whether real business page browser verification was performed
- smoke-test / run 摘要（如执行）
- 剩余 placeholder / 配置问题
- **是否已成功回传** \`workflow-ai-coding-result\`（含 HTTP 状态）
- 明确说明：**Workflow AI Coding 不允许 publish**；请用户回到 Workflow Studio 人工确认并发布，或在页面助手向导点击「使用该 Workflow 继续」进入挂载流程。`
}
