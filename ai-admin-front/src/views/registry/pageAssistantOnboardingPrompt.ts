export interface PageAssistantPromptProject {
  id?: number | string | null
  projectCode?: string | null
  name?: string | null
  appKey?: string | null
}

export interface PageAssistantPromptPage {
  pageKey?: string | null
  name?: string | null
  routePattern?: string | null
}

export interface PageAssistantPromptAction {
  actionKey?: string | null
  title?: string | null
  description?: string | null
  confirmRequired?: boolean | null
}

export interface PageAssistantPromptProgress {
  aiCodingAccessKey?: string | null
  authMode?: string | null
  authHeaderName?: string | null
  authKeyEnv?: string | null
  externalToolPath?: string | null
  platformSessionPath?: string | null
  appSecretEnv?: string | null
  sessionId?: string | null
  manifestUrl?: string | null
  latestSessionUrl?: string | null
  stepReportUrl?: string | null
  targetBindUrl?: string | null
  catalogSyncUrl?: string | null
  checksRunUrl?: string | null
  registerPageUrl?: string | null
  skillPackageUrl?: string | null
  scriptDownloadUrl?: string | null
  helperScriptPath?: string | null
  scaffoldCommand?: string | null
  verifyCommand?: string | null
  bridgeApiGlobal?: string | null
}

export interface PageAssistantOnboardingPromptContext {
  toolName: 'Cursor' | 'Codex' | 'Claude Code' | string
  platformUrl: string
  project: PageAssistantPromptProject
  page?: PageAssistantPromptPage | null
  actions?: PageAssistantPromptAction[]
  progress?: PageAssistantPromptProgress | null
}

const suggestedStepKeys = [
  'page-manifest',
  'route-detection',
  'page-structure',
  'action-design',
  'frontend-handler',
  'page-registry',
  'browser-verify-static',
  'browser-verify-runtime',
  'handoff-summary',
]

const mvpActions = [
  ['getPageState', '读取当前筛选条件、分页状态、表格数据和已选行'],
  ['setFilters', '设置筛选表单字段，不触发额外业务逻辑'],
  ['search', '点击或调用页面现有查询流程'],
  ['reset', '点击或调用页面现有重置流程'],
  ['readTable', '读取当前表格结果、列名、可见行和分页信息'],
  ['openRowAction', '可选：打开编辑、周期管理、立法库管理等行操作入口；需要按风险确认'],
]

export function buildPageAssistantOnboardingPrompt(context: PageAssistantOnboardingPromptContext) {
  const projectCode = clean(context.project.projectCode) || '当前 ReachAI 项目'
  const projectName = clean(context.project.name) || projectCode
  const projectId = clean(context.project.id) || '未提供'
  const appKey = clean(context.project.appKey) || '未配置'
  const pageName = clean(context.page?.name) || '待识别业务页面'
  const pageKey = clean(context.page?.pageKey) || '请根据真实页面命名'
  const routePattern = clean(context.page?.routePattern) || '请在业务前端中识别真实 route'
  const actions = (context.actions || []).filter((action) => clean(action.actionKey))
  const progress = context.progress || {}
  const rawAiCodingAccessKey = clean(progress.aiCodingAccessKey)
  const aiCodingAccessState = rawAiCodingAccessKey
    ? '已启用；仅通过 X-ReachAI-AiCoding-Key 请求头发送，不写入 URL'
    : '未启用或未生成；如需自动回传进度，请先在 ReachAI 平台启用 AI Coding 接入秘钥'
  const aiCodingHeader = rawAiCodingAccessKey
    ? `X-ReachAI-AiCoding-Key: ${rawAiCodingAccessKey}`
    : 'X-ReachAI-AiCoding-Key: <项目 AI Coding 秘钥>'
  const authHeaderName = clean(progress.authHeaderName) || 'X-ReachAI-AiCoding-Key'
  const authKeyEnv = clean(progress.authKeyEnv) || 'REACHAI_AI_CODING_KEY'
  const authMode = clean(progress.authMode) || 'ai-coding-key'
  const externalToolPath = clean(progress.externalToolPath) || '/api/ai-coding/projects/{projectId}/page-assistant/**'
  const platformSessionPath = clean(progress.platformSessionPath) || '/api/ai-assist/projects/{projectId}/page-assistant/**'
  const appSecretEnv = clean(progress.appSecretEnv) || 'REACHAI_REGISTRY_APP_SECRET'
  const actionCatalog = actions.length
    ? actions.map((action) => {
      const risk = action.confirmRequired ? '高风险/需确认' : '默认低风险'
      return `- ${clean(action.actionKey)}：${clean(action.title) || clean(action.description) || '页面动作'}；${risk}`
    }).join('\n')
    : '- 当前平台尚未选中动作，请按 MVP 动作清单在业务前端实现并回填页面动作目录。'
  const progressBlock = buildProgressBlock(progress)
  const helperScriptPath = clean(progress.helperScriptPath) || 'scripts/reachai-page-assistant.ps1'
  const scriptDownloadUrl = clean(progress.scriptDownloadUrl) || clean(progress.skillPackageUrl) || 'manifest.endpoints.scriptDownloadUrl 或 skillPackageUrl'
  const scaffoldCommand = clean(progress.scaffoldCommand) || `.\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl "<页面助手接入清单 URL>" -AiCodingKey $env:REACHAI_AI_CODING_KEY -Framework angular -OutputDir ".\\src\\app\\shared\\reachai"`
  const verifyCommand = clean(progress.verifyCommand) || `.\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl "<页面助手接入清单 URL>" -AiCodingKey $env:REACHAI_AI_CODING_KEY -FrontendUrl "<业务前端地址>" -Route "${routePattern}" -PageKey "${pageKey}"`
  const bridgeApiGlobal = clean(progress.bridgeApiGlobal) || 'window.__REACHAI_PAGE_BRIDGE__'

  return `你现在要在当前业务前端仓库中完成 ReachAI 页面助手接入，请使用 ${context.toolName || 'Cursor/Codex/Claude Code'} 执行。

第一步（helper script 落盘）：
- 如果业务仓库不存在 .\\${helperScriptPath}，先从 manifest.endpoints.scriptDownloadUrl 下载 helper script，或从 manifest.endpoints.skillPackageUrl / manifest.scaffold.skillPackageUrl 解压 skill 包后复制 scripts/reachai-page-assistant.ps1。
- 目标落盘路径固定为 .\\${helperScriptPath}；Windows 优先使用 PowerShell 5+ 执行。
- script 下载地址：${scriptDownloadUrl}
- 落盘完成后再执行 scaffold / verify；不要在没有 helper script 时直接假设 .\\scripts\\reachai-page-assistant.ps1 已存在。
- 外部 AI Coding 鉴权模式：${authMode}；调用 ${externalToolPath} 时统一发送请求头 ${authHeaderName}: ${rawAiCodingAccessKey || `<${authKeyEnv}>`}；不要把 aiCodingKey 拼进 URL。
- ${platformSessionPath} 是 platform-session 控制台登录态接口，只给 ReachAI 管理端页面使用；Cursor/Codex/helper 脚本不得拿它作为 ManifestUrl、stepReportUrl、registerPageUrl 或 checksRunUrl。
- 本地环境如果同时有 5200 前端代理和 18603 后端服务，复制给 AI Coding 的 manifest / scaffold / verify / step report / register / checks URL 必须保持同一个可访问 origin，优先使用当前页面 origin 下的 ${externalToolPath}。
- scaffold 命令：${scaffoldCommand}
- verify 命令：${verifyCommand}

任务边界：
- 当前任务是“页面助手接入”，目标是让 ReachAI Agent 通过当前业务页面的 Page Action 操作页面。
- 项目级 SDK / 网关 / embed token broker / 嵌入式对话框接入属于“项目 AI 快速接入”，不是本次任务；如果发现这些基础能力缺失，只列为前置条件或待办，不要在本次任务里扩大范围。
- 不要把 ReachAI 平台地址当成 Maven repository、npm registry 或通用 SDK 文件服务器；页面助手只允许使用上面给出的 scriptDownloadUrl / skillPackageUrl / page-assistant 专用接口。
- 禁止请求 /repository/**、/maven/**、/repository/maven/**、/api/embed/sdk 或 /npm/** 这类猜测路径；如果 SDK 或前端包缺失，只在总结里列为项目 AI 快速接入前置条件。
- 优先让 AI 操作当前页面已有 UI、组件状态、查询服务和路由，不要为了简单查询绕过页面去新增后端 API Tool。
- 不要重写页面，不要改无关业务逻辑，不要绕过页面权限。
- 不要要求用户提供真实密钥，也不要把真实密钥写入代码、日志、文档或最终总结。
- 如果本次只补 Page Action bridge，不要改造 Chat Embed 消息发送逻辑；若必须触碰已有聊天发送代码，ReachAI Chat Message API 固定为 POST /api/embed/chat/sessions/{sessionId}/messages，Body 必须是 { "message": "用户输入的自然语言问题" }。
- Do not send chat requests with content, text, or question as the top-level field；如果业务 UI 状态字段叫 content/text/question，请在调用 ReachAI API 边界映射为 message。
- ReachAI Chat Message API 返回的是 ApiResult 包装：顶层 code/message 只表示接口状态，顶层 message=success 绝对不能展示为助手回复；助手自然语言回复必须优先读取 data.answer。
- 如果需要兼容旧返回结构，回复字段兜底顺序只能是 data.answer -> data.reply -> data.message -> data.content；不得先读顶层 message/content。
- data.metadata.pageActionQueue 是优先级最高的前端页面动作队列；data.uiRequest 或 data.uiRequest.extension.pageActionRequest 是兼容的单动作指令。它们都不是聊天文本，必须按 Page Action 协议逐个执行并 POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result 回传结果。
- 修改或新增聊天框时，必须用一个样例响应 { "code": 200, "message": "success", "data": { "answer": "实际助手回复" } } 验证 UI 展示“实际助手回复”，而不是“success”。
- Page Action 返回结果里的 message 只是动作状态说明，不是 Chat Message API 的请求字段。

Agent / Workflow 架构说明：
- 业务系统里出现的统一 AI 按钮使用项目下的页面副驾驶 Agent；该 Agent 通常由 SDK 快速接入阶段通过 ReachAI provisioning 自动创建或复用。
- 本次页面助手接入不要创建 Agent，不要调用 /api/agents 管理接口，也不要恢复旧 Agent Studio。
- 你只需要通过 page-assistant 专用接口注册当前页面和 Page Action；随后 ReachAI“创建页面助手”向导会创建并发布 PAGE_ASSISTANT Workflow，把它加入项目页面副驾驶 Agent 的 Supervisor Workflow-as-Tool 白名单，并发布新版 Agent 配置。
- GraphSpec、画布、调试、发布、版本和回放都在 Workflow Studio 中闭环；Agent 页面只负责身份、入口、Supervisor 策略和 Workflow-as-Tool 目录。

ReachAI 平台上下文：
- 平台地址：${context.platformUrl || '请从当前环境确认'}
- 项目 ID：${projectId}
- 项目编码：${projectCode}
- 项目名称：${projectName}
- App Key：${appKey}
- AI Coding 接入状态：${aiCodingAccessState}
- AI Coding 请求头：${aiCodingHeader}
- App Secret 环境变量：${appSecretEnv}（只使用变量名，不读取、不打印、不提交真实值）
- 页面名称：${pageName}
- pageKey：${pageKey}
- routePattern：${routePattern}

本地执行要求：
- ReachAI 平台地址通常是 localhost，远端 WebFetch 往往无法访问；请使用业务前端所在机器的本机 PowerShell/curl 调用 manifest、registerPage 和 step 回传接口。
- 不要因为远端 fetch 访问 localhost 失败就判断 ReachAI 服务不可用，优先用本机 shell 验证。

页面助手进度会话：
${progressBlock}

AI Coding key 可写范围：
- 所有 page-assistant / ai-coding 请求必须使用 X-ReachAI-AiCoding-Key header；不要使用 aiCodingKey query。
- 可以读取页面助手接入清单、刷新最新 session、回传 step、绑定目标页、同步页面动作目录、运行页面助手自检。
- 优先使用“页面一键注册 URL”一次提交目标页、动作目录、文件证据和验证结果；只有该 URL 缺失时，才降级使用目标页绑定、目录同步和自检三个分散接口。
- 不要尝试调用需要平台登录态的后台管理接口；如果某接口返回 platform login required，请回到上面的 page-assistant 专用 URL。
- 绑定目标页时，向“目标页绑定 URL”PUT：{ "pageKey": "...", "routePattern": "...", "actionKeys": [...] }。
- 同步目录时，向“页面动作目录同步 URL”POST PageCatalogRegisterRequest，至少包含 pageKey、name、routePattern、replaceActions、actions；actions 内包含 actionKey、title、description、confirmRequired、inputSchema、outputSchema、sampleArgs、metadata。
- 一键注册时，向“页面一键注册 URL”POST：sessionId、toolName、pageKey、pageName、routePattern、framework、frameworkVersion、bridgeGlobal、replaceActions、files、actions、verification、handoffSummary。
- files 推荐对象数组：[{ "path": "src/app/list.component.ts", "role": "page-component" }]；也兼容字符串简写：["src/app/list.component.ts"]，后端会自动包装为 role=unknown。
- bridgeApi 以 manifest.pageActionContract.bridgeApi 为准，不要猜测 invoke 协议；global=${bridgeApiGlobal}，methods 包含 register / unregisterPage / execute / list。
- execute 返回 status=SUCCESS|WARN|ERROR；高风险动作可能返回 error.code=PENDING_CONFIRM 或 CONFIRM_REQUIRED。

目标页面确认规则：
- 如果平台上下文没有明确选中页面、pageKey/routePattern 为空，或业务前端仓库里出现多个可能的 route / 页面组件，必须先询问用户确认具体要改造哪一个业务页面，再修改代码。
- 询问时请列出你识别到的候选页面，包括 route、页面组件文件、菜单名称或页面标题，并说明你推荐的目标。
- 候选页面选择在当前 Cursor/Codex/Claude Code 对话里完成即可；不要把候选页面列表回传 ReachAI 平台。
- 如果平台已经明确给出 pageKey 和 routePattern，也要先校验它们是否能在业务前端仓库中找到；找不到时不要猜测落点，先向用户确认。

请先识别业务前端中的真实页面结构：
1. route：找到路由定义、菜单入口、权限守卫和真实访问路径。
2. 页面组件文件：定位主页面组件、子组件、表单组件、表格组件和状态管理文件。
3. 筛选表单字段：列出字段名、控件类型、默认值、字典/远程选项来源和与查询参数的映射。
4. 查询 / 重置按钮：找到已有 click handler、查询服务、loading 状态和错误处理。
5. 表格列：列出可见列、字段名、格式化逻辑、空值处理和行主键。
6. 分页：确认 page、pageSize、total、排序参数与现有查询函数的关系。
7. 行操作按钮：识别编辑、周期管理、立法库管理等入口，说明是否需要用户确认。

第一版 MVP 重点实现查询类页面动作：
${mvpActions.map(([key, desc]) => `- ${key}：${desc}`).join('\n')}

平台当前已选/已知页面动作：
${actionCatalog}

实现要求：
1. 优先复用业务前端现有组件、服务、状态管理、路由和权限判断。
2. 在真实页面内注册 Page Action handler，handler 只操作当前页面实例，不做全局旁路调用。
3. getPageState/readTable 必须是只读动作。
4. setFilters/search/reset 应复用现有表单和查询函数；不要复制一套查询逻辑。
5. 页面助手默认体验是“操作当前页面”：setFilters 必须把筛选条件真实写入当前页面可见筛选控件或其官方 form/query model，使用户能看到输入框/选择器被填充；search 必须触发原页面查询流程并刷新原表格；readTable 只读取刷新后的当前表格快照。
6. 不要让助手绕过页面直接调用业务后端查询接口来替代页面列表，也不要在聊天框里渲染完整业务列表；聊天框只做简短确认和摘要，例如“已按当前页面筛选条件完成查询，当前列表共 N 条”。
7. 如果页面组件库有官方 setValue/patchValue/setQuery/executeQuery API，优先使用官方 API；只有确认没有官方 API 时才直接修改内部 model，并必须触发变更检测和一次真实查询验证。
8. 如果页面使用 @zhongruigroup/ngx-query、zr-table 或类似查询组件库，必须追踪 executeQuery / 表格 data 事件 / loadOrders 最终读取的真实 rules/filter state；不要只写 queryTemplates[0].template.rules、默认模板、临时变量或 Page Action result。必要时同时更新当前渲染 rules 与 template rules，并触发 markForCheck/resetFieldTemplates/ChangeDetectorRef.detectChanges。
9. setFilters 完成后必须能通过浏览器验证看到筛选控件值变化；search 完成后必须能看到页面原表格刷新；readTable 返回的数据必须与当前可见表格一致。
10. openRowAction 仅作为可选动作；新增、编辑、删除、审批、导出等动作必须标记为高风险或需要用户确认。
11. 不要绕过页面权限、业务登录态、按钮权限、数据范围或后端鉴权。
12. 页面动作返回结构化结果，至少包含 status、message、filters、pagination、rows/rowCount、selectedRow 或 openedAction 等可验证信息。
13. 若当前页面尚未接入 ReachAI chat/page bridge，只补当前页面所需的前端 handler 和目录信息；项目级网关和 token broker 缺口放到待办。

官方 Angular scaffold：
- V1 官方模板固定使用 ${bridgeApiGlobal}。
- 如业务前端缺少 Page Action bridge，先确保 helper script 已落盘到 .\\${helperScriptPath}，再执行 scaffold 命令生成 Angular 模板，然后把模板接入目标页面。

页面动作目录建议：
- pageKey 使用稳定业务语义，例如 teamArchive.list。
- actionKey 使用稳定动作语义，例如 getPageState、setFilters、search、reset、readTable。
- 每个动作补充 title、description、inputSchema、outputSchema、sampleArgs、confirmRequired。
- 如平台已有手工声明动作，请让前端 handler 的 actionKey 与平台目录保持一致。

Page Action 注册一致性硬约束：
- 每一个要回填到 actions / catalog / actionKeys 的 actionKey，都必须先在业务页面运行时通过 ${bridgeApiGlobal}.registerAction(...) 注册同名 handler。
- 禁止只在 ReachAI 平台目录里声明 actionKey，却没有在业务前端 bridge 注册 handler；这会导致嵌入会话运行时报错：page action is not registered in current session。
- 如果声明 setFilters，必须能在当前页面实例运行时看到 registeredActions/list 里包含 "setFilters"，并且 handler 能把 args 写入现有筛选表单状态。
- setFilters 的验收不是“返回 SUCCESS”即可，必须在当前页面 DOM/组件状态中看到 inputSchema 对应的真实筛选项已填充；例如 schema 字段为 owner 且页面标签为“负责人”时，owner=靳圣辉 应让“负责人”输入框显示“靳圣辉”。
- setFilters 还必须把非空 args 写入页面真实查询状态，而不只是写入默认模板、临时变量或 Page Action result。验收时必须检查 getPageState/readTable 或 setFilters result 的 filters 中包含对应字段和值。
- 对 ngx-query/zr-table 页面，验收时还必须检查 setFilters 写入的是当前渲染 query rules/getQueryTerm 会读取的状态；如果输入框绑定 rule.datas[dataIndex] 而查询请求来自 event.page.filters[].term，就要保证 rule.datas[0]、rule.data、当前 operation rules 与 template rules 一致。
- search 的验收不是“调用接口成功”即可，必须证明原页面列表刷新为相应结果；如果查询接口返回数据但页面表格不变，不能回传 PASS。
- search 的验收必须查看真实业务查询请求。若 setFilters 传入了非空筛选字段，search 触发的业务接口请求体/query string 必须包含同名或业务页面既有映射后的查询参数；如果请求仍是未筛选参数，即使 handler 返回 SUCCESS 也必须判定为 FAIL。
- readTable 只能作为查询后的结果摘要来源，不能作为在聊天框中替代原页面表格展示的主输出。
- 如果当前页面短期无法实现 setFilters handler，就不要把 setFilters 写入 catalog/actionKeys，也不要让后续 Workflow 使用 PAGE_ACTION(setFilters)；查询链应改为只使用已注册的 search/readTable 等动作。
- registerPage / catalogSync / targetBind 里的 actions/actionKeys 必须与业务页面当前实际注册的 bridge actionDefinitions 保持一致，不得多报。
- 业务前端如果是在嵌入对话框 session 创建之后才注册或新增 action，需要刷新页面或重建 embed session；因为 session 创建时会快照 bridge.registeredActions。
- 最终回传 frontend-handler 或 browser-verify-runtime 为 PASS 前，必须附上当前页面 bridge registeredActions/list 的证据，并确认其中包含所有回填 actionKeys。

进度回传要求：
- 预留 stepKey：${suggestedStepKeys.join('、')}。
- 每完成或卡住一个 stepKey，都尽量向“步骤进度回传 URL”POST 状态，包含 status、message、files、evidence、reportedBy。
- status 只能使用 RUNNING、PASS、WARN、FAIL、SKIPPED。
- 不要调用项目 AI 快速接入的 SDK access session 接口；本任务只使用页面助手 page-assistant session。
- 当你识别出真实页面后，先调用“目标页绑定 URL”写回 pageKey/routePattern/actionKeys，避免平台自检继续按未选页判定。
- 如果平台一开始没有目标页，用户在当前 AI 对话里选定页面后，再调用“目标页绑定 URL”；平台会自动在创建页面助手页展示该页面的接入卡片。
- 当你生成或更新本地页面动作目录 JSON 后，调用“页面动作目录同步 URL”把 catalog 同步为 ACTIVE，再运行自检。
- 如果“页面一键注册 URL”存在，请优先在最终验证后调用它；它会合并目标页绑定、目录同步、自检和文件证据回传。
- 如果 AI Coding 接入秘钥未启用，跳过自动回传，在最终总结里列出每个 stepKey 的完成情况。

安全边界：
- aiCodingKey 只能作为 X-ReachAI-AiCoding-Key 请求头用于 AI 工具、本机 PowerShell/curl 或服务端接入阶段调用 page-assistant / ai-coding 接口；浏览器运行时代码不得调用这些接口，也不得把 aiCodingKey、provisionAgentUrl 或 appSecret 写入业务前端配置、环境变量或构建包。
- 默认先做只读 / 查询类动作。
- 新增、编辑、删除、审批、导出、批量处理、状态变更、跨页面跳转等动作必须需要用户确认或标记为高风险。
- 任何 handler 都不得绕过页面权限。
- 不要把业务登录 token 当成 ReachAI 页面动作结果或日志内容输出。
- 不要输出真实用户敏感数据；读取表格结果时只返回当前页面已展示的数据。

验证要求：
1. 运行业务前端最小构建 / 类型检查，例如 npm run build、npm run typecheck、npx vue-tsc --noEmit 或该仓库已有等价命令。
2. static 检查不等于 runtime 检查：browser-verify-static 只代表 catalog/route/action key 或静态文件证据（static only），不能当作浏览器运行时已通过。
3. runtime PASS 必须来自真实 bridge invoke（helper verify 或 checks/run 回传 runtimeVerification/browserRuntime，且 invokedActions + redactedResults 有 SUCCESS）；仅有 FrontendUrl 或 HTTP 200 只能是 WARN。
4. 没有 FrontendUrl、登录态、StorageState/Cookie 或 dev server 时，browser-verify-runtime 只能是 SKIPPED 或 WARN，不能 PASS。
5. 优先在本机执行：${verifyCommand}；需要 Node.js + playwright 才会尝试真实 bridge invoke。可选 -StorageStatePath 提供登录态；缺少登录时 message 会提示需要登录/StorageState/Cookie。
6. helper verify 默认只 invoke 只读动作 getPageState/readTable；不要自动执行 openRowAction 等高风险动作。只有显式 -ProbeMutatingActions 时才探测 setFilters/search/reset。
7. files 如只传字符串简写，平台会兼容但 validationStatus=HASH_MISSING；请优先运行 helper verify 生成本地 sha256，或在 register 时传对象数组含 exists/sha256。
8. 如可访问“页面助手自检 URL”，在完成 page-registry/frontend-handler 后 POST checks/run，并携带 runtimeVerification=verification.browserRuntime。
9. 平台自检只会追加 platformCheck，不会覆盖 Cursor 已回传的 evidence/reportedBy；你的 step 回传仍应保留原始 files/evidence。
10. 查询类页面动作最终必须给出一条端到端证据链：自然语言问题、PAGE_ACTION(setFilters) args、setFilters 后的页面 DOM/filters、search 触发的真实业务请求参数、readTable 当前表格摘要。缺少任一项只能 WARN/FAIL，不能 PASS。
11. 如果 action result 显示 SUCCESS，但真实业务请求仍是未筛选参数（例如只包含 pageIndex/pageSize 或 deptId/organId=null），必须继续修复 handler，不能把该轮标记为 PASS。
12. 最终输出修改文件、动作清单、static/runtime 验证结果、未完成项。

请先输出你识别到的 route、页面组件文件、表单字段、查询/重置按钮、表格列、分页和行操作清单，再开始最小改造。`
}

function buildProgressBlock(progress: PageAssistantPromptProgress) {
  const lines = [
    ['页面助手接入清单 URL', progress.manifestUrl],
    ['最新进度会话 URL', progress.latestSessionUrl],
    ['步骤进度回传 URL', progress.stepReportUrl],
    ['目标页绑定 URL', progress.targetBindUrl],
    ['页面动作目录同步 URL', progress.catalogSyncUrl],
    ['页面助手自检 URL', progress.checksRunUrl],
    ['页面一键注册 URL', progress.registerPageUrl],
    ['Skill 包下载 URL', progress.skillPackageUrl],
    ['Helper script 下载 URL', progress.scriptDownloadUrl],
    ['Helper script 落盘路径', progress.helperScriptPath || 'scripts/reachai-page-assistant.ps1'],
    ['AI 接入会话 ID', progress.sessionId],
    ['Angular scaffold 命令', progress.scaffoldCommand],
    ['本地 verify 命令', progress.verifyCommand],
    ['bridgeApi global', progress.bridgeApiGlobal],
  ]
  const available = lines.filter(([, value]) => clean(value))
  if (!available.length) {
    return '- 当前平台暂未生成页面助手专用 session；请继续按提示词完成接入，并在最终总结中按 stepKey 汇报。'
  }
  return available.map(([label, value]) => `- ${label}：${value}`).join('\n')
}

function clean(value: unknown) {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}
