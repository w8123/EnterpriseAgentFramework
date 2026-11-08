import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-java',
  topic: 'sdk-java',
  title: 'Java（Spring Boot）后端接入',
  summary: 'Spring Boot 2.7 / 3 项目引入 starter，注册业务方法并提供嵌入换票接口。',
  category: 'onboarding',
  order: 20,
  goal: '在当前 Spring Boot 项目里接入 ReachAI Java SDK',
})
