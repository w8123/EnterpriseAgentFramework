import type { AiPromptScenario } from './aiPromptScenarios'

/** 所有平台场景共用的开头：只用已经接好的那一个 ReachAI 连接。 */
export const PLATFORM_PREAMBLE = '请使用你已经接好的 ReachAI 连接（MCP 服务名以 reachai_platform_ 开头）完成下面的事。如果还没有这个连接，先停下来告诉我去 ReachAI 的「AI 工具接入」页接入。'

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

请先调用 reachai_integration_guide，参数 topic="${input.topic}"、projectCode="{{projectCode}}"，读取接入教程及操作规则，再按指引完成本次任务。`,
  }
}
