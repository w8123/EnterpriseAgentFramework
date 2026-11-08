import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'project-workbench-onboarding',
  title: '接入业务系统（项目接入工作台）',
  category: 'onboarding',
  audience: ['developer'],
  connection: 'platform',
  summary: '在当前业务仓库通过 MCP 发起或继续项目接入，进度和结果回到 ReachAI。',
  order: 10,
  variables: [{ name: 'projectCode', label: '项目编码', required: true }],
  template: `${PLATFORM_PREAMBLE}

帮我在当前业务系统代码仓库接入 ReachAI 项目「{{projectCode}}」。

1. 检查仓库的业务服务、技术栈和现有 ReachAI 配置，确认当前仓库对应项目「{{projectCode}}」。如果项目或技术栈有歧义，先问我。
2. 用 reachai_projects_list 检查项目与访问权限。找不到或没有权限时先说明情况，让我核对项目和授权。首次注册的新项目，先用 reachai_integration_guide 读取对应技术栈教程，使用 reachai_onboarding_resources 获取制品，并通过 reachai_onboarding_enrollment_request 申请本人审核。批准后调用 reachai_onboarding_enrollment_execute，将返回的一次性令牌放入业务服务的受保护环境，完成首次注册后继续。令牌不写入仓库、日志、任务报告或聊天。
3. 调用 reachai_project_onboarding_start，projectCode="{{projectCode}}"，sdkLanguage 填写识别出的 java、csharp、python、node 或 go。Codex、Cursor、Trae、Claude Code 的 executorProvider 分别填 CODEX、CURSOR、TRAE、CLAUDE_CODE，其他 MCP 编程工具填 OTHER。按返回结果创建或续接任务；任务被其他连接占用、等待验收或返回错误时，如实说明并按提示处理。返回 projectAgentProvisioning 时，如状态不是 READY，说明项目页面助手的待处理情况；UNKNOWN 时先检查状态，不自动重试。
4. 任务已绑定当前连接时，用返回的任务编号调用 reachai_ai_coding_task_context，先读取所有 requiredBeforeEditing=true 的资源。READY/RUNNING 时按上下文用 reachai_ai_coding_task_event 写 STARTED；WAITING_USER 时先用 reachai_ai_coding_task_questions 查看问题和回答，未全部回答时不要写 STARTED 或 RESUMED。
5. 使用 reachai_ai_coding_task_event、reachai_ai_coding_task_ask 和 reachai_ai_coding_task_heartbeat 回报真实进度、提问并保持长时间任务在线。超过两分钟的构建或测试前后调用 heartbeat。完成后用 reachai_ai_coding_task_artifact 提交报告，需要平台校验时按上下文调用 reachai_ai_coding_task_verify。
6. 说明真实测试和浏览器验证结果。返回 ACCEPTANCE_READY 或 COMPLETED 时告诉我回到项目接入工作台验收，其他状态如实说明。

${PLATFORM_RULES}`,
}

export default scenario
