import type { AiPromptScenario } from '../aiPromptScenarios'

const scenario: AiPromptScenario = {
  key: 'employee-business',
  title: '用 AI 办业务',
  category: 'employee',
  audience: ['employee'],
  connection: 'employee',
  summary: '让 AI 以你的业务系统身份查询和办理业务，写操作先提交审批。',
  order: 10,
  variables: [{ name: 'systemName', label: '业务系统' }],
  template: `请使用你已经接好的 ReachAI 员工连接，以我的身份在「{{systemName}}」里办事。

我要办的事：<在这里写，例如「查一下 A 商品的库存」或「新建客户张三」>

规则：
- 先列出你能用的工具，确认能办再动手。
- 写操作（新建、修改、删除）会先提交审批，告诉我审批编号，用 reachai_approval_status 查进度；不要换别的方式绕过。
- 只报告系统真实返回的结果。`,
  tryAfter: '查一下仓库里库存最少的 5 个商品',
}

export default scenario
