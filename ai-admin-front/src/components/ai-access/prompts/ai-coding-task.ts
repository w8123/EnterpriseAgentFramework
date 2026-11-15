import type { AiPromptScenario } from '../aiPromptScenarios'
import { AI_CODING_TASK_RULES, PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

1. 调用 reachai_ai_coding_task_claim，taskId="{{taskId}}"，claimCode="{{claimCode}}"。领取码只能使用一次，不要输出或保存。
2. 调用 reachai_ai_coding_task_context 读取任务目标、范围、所需资源和验收契约，按任务上下文完成工作。
3. 用 reachai_ai_coding_task_event 写进度，遇到问题用 reachai_ai_coding_task_ask 提问，并在长时间操作前后调用 reachai_ai_coding_task_heartbeat。
4. 用 reachai_ai_coding_task_artifact 和任务要求的验证工具提交证据；如实说明任务状态，等待用户在 ReachAI 验收。

${AI_CODING_TASK_RULES}

${PLATFORM_RULES}`,
}

export default scenario
