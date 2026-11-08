import type { AiPromptScenario } from './aiPromptScenarios'

/** 所有平台场景共用的开头：只用已经接好的那一个 ReachAI 连接。 */
export const PLATFORM_PREAMBLE = '请使用你已经接好的 ReachAI 连接（MCP 服务名以 reachai_platform_ 开头）完成下面的事。如果还没有这个连接，先停下来告诉我去 ReachAI 的「AI 工具接入」页接入。'

/** 通用规则：写到 ReachAI 的内容用中文；需要审核的只提交申请；结果未知不要重试。 */
export const PLATFORM_RULES = [
  '规则：',
  '- 写进 ReachAI 的名称、说明、进度和问题一律用简体中文；工具名、代码、路径保留英文。',
  '- 需要审核的操作只提交申请，告诉我去 ReachAI 的「AI 操作审核」里批准；不要自己绕过。',
  '- 工具返回「结果未知」时先按提示查询，不要直接重试。',
  '- 只报告真实结果，失败就说失败并给出原因。',
].join('\n')

export interface GuidePromptInput {
  key: string
  topic: string
  title: string
  summary: string
  category: AiPromptScenario['category']
  order: number
  goal: string
}

/** 按框架的接入教程：AI 先取带真实地址的教程，再在当前仓库里动手。 */
export function guideScenario(input: GuidePromptInput): AiPromptScenario {
  return {
    key: input.key,
    title: input.title,
    category: input.category,
    audience: ['developer'],
    connection: 'platform',
    summary: input.summary,
    order: input.order,
    variables: [{ name: 'projectCode', label: '项目编码', required: true }],
    template: `${PLATFORM_PREAMBLE}

目标：${input.goal}（ReachAI 项目编码：{{projectCode}}）。

1. 调用 reachai_integration_guide，参数 topic="${input.topic}"、projectCode="{{projectCode}}"，读取接入教程。教程里的地址、项目编码和白名单已经是这个项目的真实值。
2. 先看当前仓库的框架和版本，跟教程的「适用范围」对不上时先告诉我，不要硬改。
3. 按教程改代码。密钥只从环境变量或密钥管理器读取，不写进代码和配置文件。
4. 改完后在本地跑起来，按教程的「自检」一节逐项检查；再调用 reachai_doctor（projectCode="{{projectCode}}"）确认 ReachAI 那边也看到了。
5. 最后告诉我改了哪些文件、怎么验证的、还有什么没做。

${PLATFORM_RULES}`,
  }
}
