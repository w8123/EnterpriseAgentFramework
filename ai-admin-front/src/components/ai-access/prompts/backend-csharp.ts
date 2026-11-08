import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-csharp',
  topic: 'sdk-csharp',
  title: 'C#（ASP.NET Core）后端接入',
  summary: 'ASP.NET Core 项目引入 ReachAI.AspNetCore，注册业务方法并提供嵌入换票接口。',
  category: 'onboarding',
  order: 30,
  goal: '在当前 ASP.NET Core 项目里接入 ReachAI C# SDK',
})
