import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

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

帮我体检业务系统和 ReachAI 项目「{{projectCode}}」的接入。

1. 调用 reachai_doctor（projectCode="{{projectCode}}"），拿到服务端各项检查结果。
2. 如果当前目录是这个业务系统的代码仓库，再在本地检查：ReachAI SDK 依赖和版本、REACHAI_* 环境变量是否配置（只看有没有，不要打印值）、嵌入对话的地址和签名换票接口。
3. 如果能联网访问业务系统，按 reachai_doctor 返回的 localChecks 跑一遍（例如用 curl 请求 SDK 的健康检查地址）。
4. 用一张表列出：检查项、结果（正常 / 有问题）、怎么修。不要自动修改任何东西，先让我决定。

${PLATFORM_RULES}`,
}

export default scenario
