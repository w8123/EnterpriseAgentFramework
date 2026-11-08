import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'workflow-draft',
  title: '用一句话建一个 Workflow',
  category: 'workflow',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 按你的描述建 Workflow 草稿、校验、只读试运行，最后申请发布。',
  order: 10,
  variables: [
    { name: 'projectCode', label: '项目编码' },
    { name: 'workflowKey', label: '已有 Workflow（可选）' },
  ],
  template: `${PLATFORM_PREAMBLE}

帮我在 ReachAI 里做一个 Workflow（项目：{{projectCode}}；已有 Workflow：{{workflowKey}}）。我要它做的事：<在这里写一句话，例如「每天查库存低于安全线的商品并汇总」>

1. 有已有 Workflow 就先 reachai_workflow_working_copy 读工作副本；没有就 reachai_workflow_create_draft 新建（先用 reachai_workflows_search 确认没有同名）。
2. 用 reachai_workflow_node_types 和 reachai_business_methods_list 选节点和能力。
3. 每次改动先 reachai_workflow_preview_patch 预览，再 reachai_workflow_save_patch 保存（用刚读到的 revision）。
4. reachai_workflow_validate_release 校验通过后，用 reachai_workflow_trial_read_only 只读试运行一次。
5. 把流程图用文字讲给我听，我同意后再 reachai_workflow_request_publish 申请发布。

${PLATFORM_RULES}`,
}

export default scenario
