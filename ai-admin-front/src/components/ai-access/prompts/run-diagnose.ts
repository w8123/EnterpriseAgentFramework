import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'run-diagnose',
  title: '分析最近失败的运行',
  category: 'doctor',
  audience: ['developer', 'manager', 'admin'],
  connection: 'platform',
  summary: '让 AI 按失败原因归类最近的运行，并找出最该先修的问题。',
  order: 20,
  variables: [{ name: 'projectCode', label: '项目编码' }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="run-diagnose"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：分析最近失败的运行
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
