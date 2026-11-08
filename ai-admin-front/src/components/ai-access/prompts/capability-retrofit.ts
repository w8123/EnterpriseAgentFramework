import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'capability-retrofit',
  title: '用 AI 改造业务能力',
  category: 'retrofit',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 扫描业务代码列出候选能力，你勾选后它加 SDK 声明、同步到平台，评审接受后发布给员工。',
  order: 10,
  variables: [
    { name: 'projectCode', label: '项目编码', required: true },
    { name: 'objective', label: '改造目标' },
  ],
  template: `${PLATFORM_PREAMBLE}

给 ReachAI 项目「{{projectCode}}」做一次业务能力改造，目标：{{objective}}

1. 调用 reachai_retrofit_start 新建改造任务（会自动领取到这条连接），再用 reachai_ai_coding_task_context 读任务上下文，下载里面的 reachai-capability-retrofit Skill 并照做。
2. 扫描当前仓库，用 reachai_retrofit_propose 提交候选清单（最多 20 个，标清只读/会改数据/不可撤回），然后停下来等我勾选。不要替我勾选。
3. 用 reachai_ai_coding_task_questions 读到我的勾选后，只给选中的方法加能力声明；编译、跑测试、重启服务。
4. 用 reachai_ai_coding_task_verify（verificationKey=SDK_SYNC）做同步校验，再用 reachai_ai_coding_task_artifact 提交改造报告。
5. 告诉我去「用 AI 改造业务能力」页面评审接受，并发布给员工。

${PLATFORM_RULES}`,
  tryAfter: '帮我把查询应收款开放给 AI',
}

export default scenario
