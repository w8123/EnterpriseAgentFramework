import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'project-onboarding',
  title: '从当前仓库接入 ReachAI',
  category: 'onboarding',
  audience: ['developer'],
  connection: 'platform',
  summary: '在业务仓库里发起接入，进度和验收记录在项目接入工作台。',
  order: 5,
  variables: [],
  template: `${PLATFORM_PREAMBLE}

帮我把当前业务系统接入 ReachAI。

先检查当前仓库，识别业务服务、技术栈和已有 ReachAI 配置。用 reachai_projects_list 查看有权访问的项目，确认仓库与项目的对应关系；无法确定时先问我，不要猜测项目编码。
已有项目时，调用 reachai_project_onboarding_start，传入确认的 projectCode、sdkLanguage 和所用 executorProvider，按返回结果创建或继续任务。Codex、Cursor、Trae、Claude Code 分别填写 CODEX、CURSOR、TRAE、CLAUDE_CODE，其他 MCP 编码工具填写 OTHER。返回 projectAgentProvisioning 时，如状态不是 READY，向我说明项目页面助手的待处理情况；UNKNOWN 时先检查状态，不自动重试。
新项目先确认项目编码，调用 reachai_integration_guide 读取对应技术栈的接入指南，并调用 reachai_onboarding_resources 获取 SDK 接入资源。用 reachai_onboarding_enrollment_request 申请首次注册授权，等我在「AI 操作审核」批准后调用 reachai_onboarding_enrollment_execute。把返回的一次性令牌通过受保护环境提供给业务服务，完成注册后再启动项目接入任务。不要把令牌写入仓库、日志、任务报告或聊天。
领取任务后用 reachai_ai_coding_task_context 读取上下文，按其中的必读资源和要求实施；用任务工具回报真实进度、问题和结果，按上下文执行平台校验。请在真实环境可用时验证业务登录、调用及页面对话，告诉我进度和需要我在项目接入工作台验收的事项。

${PLATFORM_RULES}`,
  tryAfter: '帮我接入 ReachAI',
}

export default scenario
