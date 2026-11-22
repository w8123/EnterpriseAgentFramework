import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'mcp-publication',
  title: '把能力开放给员工的 AI 工具',
  category: 'api',
  audience: ['developer', 'admin'],
  connection: 'platform',
  summary: '新建 MCP 发布草稿，挑选业务方法 / HTTP API / Workflow，做发布前检查后申请发布。',
  order: 30,
  variables: [{ name: 'projectCode', label: '项目编码' }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="mcp-publication"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：把能力开放给员工的 AI 工具
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
