import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

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

请先读取 reachai_integration_guide（topic="project-workbench-onboarding"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：接入业务系统（项目接入工作台）
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
