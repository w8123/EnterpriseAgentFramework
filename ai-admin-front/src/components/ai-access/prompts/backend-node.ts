import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-node',
  topic: 'sdk-node',
  title: 'Node.js（Express）后端接入',
  summary: 'Express 项目引入 reachai SDK，注册业务方法并提供嵌入换票接口。',
  category: 'onboarding',
  order: 50,
  goal: '在当前 Express 项目里接入 ReachAI Node.js SDK',
})
