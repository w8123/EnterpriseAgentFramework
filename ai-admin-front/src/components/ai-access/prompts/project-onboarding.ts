import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

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

请先读取 reachai_integration_guide（topic="project-onboarding"）提供的操作指引，再按指引完成本次任务。

任务：从当前仓库接入 ReachAI
我的需求：<请在这里补充真实需求>`,
  tryAfter: '帮我接入 ReachAI',
}

export default scenario
