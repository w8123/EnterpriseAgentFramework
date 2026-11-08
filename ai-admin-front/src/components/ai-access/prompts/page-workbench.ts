import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

在当前业务系统前端仓库里完成 ReachAI 项目「{{projectCode}}」的业务页面接入任务。

1. 先领取任务：调用 reachai_ai_coding_task_claim，taskId="{{taskId}}"，claimCode="{{claimCode}}"。
2. 调用 reachai_ai_coding_task_context 读取任务上下文，再用 reachai_page_workbench_read（projectCode="{{projectCode}}"）看页面列表、就绪度和分析发现。
3. 按上下文的范围改页面代码，只动任务涉及的页面；不扫描无关模块。
4. 执行中用 reachai_ai_coding_task_event 写真实进度，有歧义用 reachai_ai_coding_task_ask 提问，长时间构建前后调用 reachai_ai_coding_task_heartbeat。
5. 在真实浏览器里自检页面动作，再用 reachai_ai_coding_task_artifact 提交结果。
6. 返回 ACCEPTANCE_READY 或 COMPLETED 时告诉我「现在可以回到 ReachAI 验收」；其他状态如实说明。

${PLATFORM_RULES}`,
}

export default scenario
