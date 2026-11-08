import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-python',
  topic: 'sdk-python',
  title: 'Python（FastAPI）后端接入',
  summary: 'FastAPI 项目引入 reachai SDK，注册业务方法并提供嵌入换票接口。',
  category: 'onboarding',
  order: 40,
  goal: '在当前 FastAPI 项目里接入 ReachAI Python SDK',
})
