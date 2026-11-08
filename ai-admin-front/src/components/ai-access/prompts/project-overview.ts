import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'project-overview',
  title: '看看项目接入到哪一步了',
  category: 'onboarding',
  audience: ['developer', 'manager', 'admin'],
  connection: 'platform',
  summary: '让 AI 汇总一个项目的接入状态、已接入的业务方法和还缺的步骤。',
  order: 5,
  variables: [{ name: 'projectCode', label: '项目编码' }],
  template: `${PLATFORM_PREAMBLE}

帮我看看 ReachAI 项目「{{projectCode}}」现在接入到哪一步了（未指定时先用 reachai_projects_list 列出我能看的项目让我选）。

1. 用 reachai_business_system_get 读项目配置和接入状态，用 reachai_registry_instances 看 SDK 实例是否在线。
2. 用 reachai_business_methods_list 统计已接入的业务方法，按只读 / 写操作分开。
3. 用 reachai_page_workbench_read 看业务页面接入情况。
4. 用 reachai_doctor 做一次体检。
5. 用一张表告诉我：已完成、有问题（附修法）、下一步建议。只读，不做任何修改。

${PLATFORM_RULES}`,
}

export default scenario
