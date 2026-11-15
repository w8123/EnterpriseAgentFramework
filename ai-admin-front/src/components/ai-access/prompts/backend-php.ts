import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'backend-php',
  topic: 'sdk-php',
  title: 'PHP（原生 / Laravel / ThinkPHP）后端接入',
  summary: 'PHP 项目引入 reachai/php-sdk，注册业务方法、配置命令行心跳并提供嵌入换票接口。',
  category: 'onboarding',
  order: 65,
  goal: '在当前 PHP 项目里接入 ReachAI PHP SDK',
})
