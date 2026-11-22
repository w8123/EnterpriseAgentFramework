import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

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

请先读取 reachai_integration_guide（topic="workflow-draft"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：用一句话建一个 Workflow
上下文：projectCode="{{projectCode}}"，workflowKey="{{workflowKey}}"
我的需求：<请在这里补充真实需求>`,
  examples: [
    {
      title: '低库存商品汇总',
      description: '帮我建一个流程，查询低于安全库存的商品，按仓库汇总库存与缺口。',
    },
    {
      title: '销售订单统计',
      description: '帮我统计本月各客户的订单数和销售金额，列出销售额前 10 名。',
    },
    {
      title: '描述自己的流程',
      description: '帮我创建或调整一个 Workflow，实现「在这里写下你的需求」。',
    },
  ],
}

export default scenario
