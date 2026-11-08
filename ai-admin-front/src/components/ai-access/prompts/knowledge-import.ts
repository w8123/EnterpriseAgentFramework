import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

帮我把这些文档导入 ReachAI 知识库（知识库编码：{{knowledgeBaseCode}}；未指定时先用 reachai_knowledge_bases_list 让我选，或问我要不要用 reachai_knowledge_base_create 新建）。文档：<在这里写文件或文件夹路径>

1. 按 reachai_upload_get 的说明把每个文件 POST 到 /mcp/platform/uploads 拿到 uploadId（单个最大 20MB）。
2. 用 reachai_knowledge_import_job_submit 提交导入，reachai_knowledge_import_job_get 等解析完成。
3. 用 reachai_knowledge_import_job_request_commit 申请入库，告诉我去审核。
4. 入库后用 reachai_knowledge_retrieval_test 拿两三个问题测一下命中情况。

${PLATFORM_RULES}`,
}

export default scenario
