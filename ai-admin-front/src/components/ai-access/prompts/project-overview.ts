import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'project-overview',
  title: '看看项目接入到哪一步了',
  category: 'onboarding',
  audience: ['developer', 'manager', 'admin'],
  connection: 'platform',
  summary: '让 AI 汇总一个项目的接入状态、已接入的业务方法和还缺的步骤。',
  order: 5,
  variables: [{ name: 'projectCode', label: '项目编码' }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="project-overview"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：看看项目接入到哪一步了
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
