import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'business-method-try',
  title: '检查并试用已接入的业务方法',
  category: 'api',
  audience: ['developer'],
  connection: 'platform',
  summary: '列出项目的业务方法，检查说明是否清楚，并试调用只读方法。',
  order: 20,
  variables: [{ name: 'projectCode', label: '项目编码', required: true }],
  template: `${PLATFORM_PREAMBLE}

帮我检查 ReachAI 项目「{{projectCode}}」已接入的业务方法。

1. 用 reachai_business_methods_list 列出全部，标出缺少中文说明、参数说明不清或读写类型可疑的。
2. 对只读方法：先 reachai_business_method_invocation_context 固定契约，再 reachai_business_method_invoke_read_only 试调用，记录结果。
3. 说明需要补的地方用 reachai_business_capability_semantic_generate 生成草稿给我看，不要直接保存。
4. 最后给我一张表：方法、读写、试调用结果、建议。

${PLATFORM_RULES}`,
}

export default scenario
