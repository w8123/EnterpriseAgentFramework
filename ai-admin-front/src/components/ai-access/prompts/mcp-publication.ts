import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

帮我把 ReachAI 项目「{{projectCode}}」的常用能力开放给员工的 AI 工具（Codex、Claude Code 等）。项目未指定时先用 reachai_projects_list 列出让我选。

1. 用 reachai_mcp_publications_list 看已有的发布，避免重复。
2. 列出候选能力（reachai_business_methods_list、reachai_http_apis_list、reachai_workflows_list），按只读 / 写操作分组给我选。
3. 我确认后：reachai_mcp_publication_create 建草稿，reachai_mcp_publication_add_item 逐个添加。
4. 用 reachai_mcp_publication_precheck 做发布前检查，再用 reachai_mcp_publication_request_change 申请发布，告诉我去审核。

${PLATFORM_RULES}`,
}

export default scenario
