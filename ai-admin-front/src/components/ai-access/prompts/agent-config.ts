import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'agent-config',
  title: '调整一个智能体的配置',
  category: 'agent',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 读智能体草稿，按你的要求改提示词、工具和知识库，保存草稿后申请发布。',
  order: 10,
  variables: [{ name: 'agentId', label: '智能体' }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="agent-config"）提供的操作指引，再按指引完成本次任务。

任务：调整一个智能体的配置
上下文：agentId="{{agentId}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
