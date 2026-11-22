import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'knowledge-import',
  title: '把本地文档导入知识库',
  category: 'knowledge',
  audience: ['developer', 'manager'],
  connection: 'platform',
  summary: 'AI 上传你电脑里的文档、提交导入、申请入库，再做检索测试。',
  order: 10,
  variables: [{ name: 'knowledgeBaseCode', label: '知识库编码' }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="knowledge-import"）提供的操作指引，再按指引完成本次任务。

任务：把本地文档导入知识库
上下文：knowledgeBaseCode="{{knowledgeBaseCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
