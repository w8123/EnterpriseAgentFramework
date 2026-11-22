import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'http-api-import',
  title: '从代码里整理 HTTP 接口导入 ReachAI',
  category: 'api',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 读业务系统代码，整理出 HTTP 接口上传到项目，接纳后试调用只读接口。',
  order: 10,
  variables: [{ name: 'projectCode', label: '项目编码', required: true }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="http-api-import"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：从代码里整理 HTTP 接口导入 ReachAI
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
