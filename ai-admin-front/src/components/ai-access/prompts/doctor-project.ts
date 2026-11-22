import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'doctor-project',
  title: '一键体检业务系统接入',
  category: 'doctor',
  audience: ['developer', 'admin'],
  connection: 'platform',
  summary: '服务端查项目、密钥、SDK 实例、嵌入白名单和模型；本地查依赖和配置，最后列出问题和修法。',
  order: 10,
  variables: [{ name: 'projectCode', label: '项目编码', required: true }],
  template: `${PLATFORM_PREAMBLE}

请先读取 reachai_integration_guide（topic="doctor-project"，projectCode="{{projectCode}}"）提供的操作指引，再按指引完成本次任务。

任务：一键体检业务系统接入
上下文：projectCode="{{projectCode}}"
我的需求：<请在这里补充真实需求>`,
}

export default scenario
