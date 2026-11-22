import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

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

请先读取 reachai_integration_guide（topic="business-method-try"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：检查并试用已接入的业务方法
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
