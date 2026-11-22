import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'capability-retrofit',
  title: '用 AI 改造业务能力',
  category: 'retrofit',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 扫描业务代码列出候选能力，你勾选后它加 SDK 声明、同步到平台，评审接受后发布给员工。',
  order: 10,
  variables: [
    { name: 'projectCode', label: '项目编码', required: true },
    { name: 'objective', label: '改造目标' },
  ],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="capability-retrofit"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：用 AI 改造业务能力
上下文：projectCode="{{projectCode}}"，objective="{{objective}}"
我的需求：<请在这里补充真实需求>`,
  tryAfter: '帮我把查询应收款开放给 AI',
}

export default scenario
