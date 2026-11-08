import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

帮我调整 ReachAI 里的智能体（{{agentId}}；未指定时先用 reachai_agents_list 让我选）。我想改的是：<在这里写，例如「回答更简短，并且能查库存」>

1. 用 reachai_agent_draft_context 读可编辑配置和 revision。
2. 需要新工具或知识库时，用 reachai_tools_list、reachai_knowledge_bases_list、reachai_skills_list 找候选给我确认。
3. 用 reachai_agent_save_draft 按 revision 保存草稿；冲突时重新读取再合并。
4. 把改动前后对比讲给我听，我同意后用 reachai_agent_request_publish 申请发布。

${PLATFORM_RULES}`,
}

export default scenario
