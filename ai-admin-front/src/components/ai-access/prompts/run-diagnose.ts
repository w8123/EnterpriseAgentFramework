import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

帮我分析 ReachAI 最近失败的运行（项目：{{projectCode}}；未指定就看我能看的全部）。

1. 用 reachai_run_diagnostics 按失败原因聚类，比较各版本成功率。
2. 每类挑一两条，用 reachai_run_get / reachai_trace_get 看具体在哪一步失败。
3. 告诉我：失败最多的三类原因、影响了哪些 Agent 或 Workflow、建议怎么修。只读，不重放、不修改。

${PLATFORM_RULES}`,
}

export default scenario
