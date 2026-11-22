import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'ai-coding-task',
  title: '继续 AI 编程任务',
  category: 'workflow',
  audience: ['developer'],
  connection: 'platform',
  summary: '领取现有任务，读取任务上下文与要求，并把进度和结果返回 ReachAI。',
  variables: [
    { name: 'taskId', label: '任务编号', required: true },
    { name: 'claimCode', label: '领取码', required: true },
  ],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="ai-coding-task"）提供的操作指引，再按指引完成本次任务。

任务：继续 AI 编程任务
上下文：taskId="{{taskId}}"，claimCode="{{claimCode}}"
以任务上下文中的真实目标和验收要求为准。`,
}

export default scenario
