import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-go',
  topic: 'sdk-go',
  title: 'Go（net/http）后端接入',
  summary: 'Go net/http 项目引入 reachai-go，注册业务方法并提供嵌入换票接口。',
  category: 'onboarding',
  order: 60,
  goal: '在当前 Go 项目里接入 ReachAI Go SDK',
})
