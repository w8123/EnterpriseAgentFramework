import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'page-workbench',
  title: '让业务页面支持 AI 操作（业务页面工作台）',
  category: 'page',
  audience: ['developer'],
  connection: 'platform',
  summary: '把页面接入任务交给 AI：给业务页面登记页面动作、接上嵌入对话，结果回到页面工作台验收。',
  order: 10,
  variables: [
    { name: 'projectCode', label: '项目编码', required: true },
    { name: 'taskId', label: '任务编号', required: true },
    { name: 'claimCode', label: '领取码', required: true },
  ],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="page-workbench"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：让业务页面支持 AI 操作（业务页面工作台）
上下文：projectCode="{{projectCode}}"，taskId="{{taskId}}"，claimCode="{{claimCode}}"
以任务上下文中的真实目标和验收要求为准。`,
}

export default scenario
