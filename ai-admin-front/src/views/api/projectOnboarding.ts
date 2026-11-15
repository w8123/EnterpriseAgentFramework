/**
 * 接入进度：从「加了接口」到「员工和助手能用、每天自动评测」要走的几步。
 * 这里只根据平台里已有的事实判断每一步做没做，不保存任何状态，所以对任何接入的系统都一样。
 */
export type OnboardingStepKey = 'apis' | 'auth' | 'publish' | 'login' | 'eval'
export type OnboardingStepState = 'done' | 'todo' | 'blocked' | 'optional' | 'unknown'
export type OnboardingAction = 'add' | 'accept' | 'auth' | 'publish' | 'login' | 'eval-account' | 'eval-open'

export interface OnboardingFacts {
  /** 项目里的接口总数，以及其中已接纳的数量 */
  apiTotal: number
  acceptedTotal: number
  /** CONFIGURED / BLOCKED / 其它；null 表示还读不到（没有接口时就没有地方配） */
  authStatus: string | null
  /** 指向本项目、并且已打开「员工使用」的发布里，对员工可见的能力数；null 表示没有这样的发布 */
  employeeToolCount: number | null
  /** 员工登录入口：true 有、false 没有、null 当前账号看不到（不是平台管理员） */
  loginEntry: boolean | null
  evalAccount: boolean
  /** 最近一次评测的通过率（0 到 1）；null 表示还没评测过 */
  lastPassRate: number | null
}

export interface OnboardingStep {
  key: OnboardingStepKey
  title: string
  state: OnboardingStepState
  detail: string
  action?: { kind: OnboardingAction; label: string }
}

function percent(rate: number) {
  return `${Math.round(rate * 100)}%`
}

export function onboardingSteps(facts: OnboardingFacts): OnboardingStep[] {
  const hasApis = facts.apiTotal > 0
  const hasAccepted = facts.acceptedTotal > 0
  const authDone = facts.authStatus === 'CONFIGURED'
  const published = (facts.employeeToolCount ?? 0) > 0

  const apis: OnboardingStep = !hasApis
    ? { key: 'apis', title: '添加接口', state: 'todo', detail: '还没有接口。导入接口文档最快，一次能加几十上百个。', action: { kind: 'add', label: '添加 API' } }
    : !hasAccepted
      ? { key: 'apis', title: '添加接口', state: 'todo', detail: `已有 ${facts.apiTotal} 个接口，但还没有接纳任何一个。接纳后才能配鉴权和发布。`, action: { kind: 'accept', label: '去接纳' } }
      : { key: 'apis', title: '添加接口', state: 'done', detail: `${facts.apiTotal} 个接口，已接纳 ${facts.acceptedTotal} 个。`, action: { kind: 'add', label: '继续添加' } }

  const auth: OnboardingStep = !hasAccepted
    ? { key: 'auth', title: '接口鉴权', state: 'blocked', detail: '先接纳至少一个接口。' }
    : authDone
      ? { key: 'auth', title: '接口鉴权', state: 'done', detail: '服务地址和凭据已配置，本项目的接口默认使用。', action: { kind: 'auth', label: '查看' } }
      : { key: 'auth', title: '接口鉴权', state: 'todo',
          detail: facts.authStatus === 'BLOCKED' ? '配置有问题，需要重新保存。' : '填业务系统的服务地址和凭据；可以让平台用账号密码自动登录换令牌。',
          action: { kind: 'auth', label: '去配置' } }

  const publish: OnboardingStep = !hasAccepted || !authDone
    ? { key: 'publish', title: '发布给员工和助手', state: 'blocked', detail: '先完成前两步。' }
    : published
      ? { key: 'publish', title: '发布给员工和助手', state: 'done', detail: `员工和助手现在能用 ${facts.employeeToolCount} 个能力。`, action: { kind: 'publish', label: '继续发布' } }
      : { key: 'publish', title: '发布给员工和助手', state: 'todo', detail: '把接口变成员工的 AI 工具和助手能直接调用的能力，查询类的不用审批。', action: { kind: 'publish', label: '去发布' } }

  // 平台看不出业务系统里有没有嵌 AI 面板，所以登录入口不算必做：嵌了面板的系统员工本来就有身份。
  let login: OnboardingStep
  if (facts.loginEntry === true) {
    login = { key: 'login', title: '员工登录入口', state: 'done', detail: '员工可以用业务系统的账号登录 ReachAI。', action: { kind: 'login', label: '查看' } }
  } else if (facts.loginEntry === null) {
    login = { key: 'login', title: '员工登录入口', state: 'unknown', detail: '需要平台管理员在「认证源」里查看和配置。' }
  } else {
    login = { key: 'login', title: '员工登录入口', state: 'optional',
      detail: '让员工用业务系统的账号登录 ReachAI；业务系统只有登录接口也可以，不用改它。业务系统里已经嵌了 AI 面板的话，这一步可以不做。',
      action: { kind: 'login', label: '去配置' } }
  }

  let evaluation: OnboardingStep
  if (!published) {
    evaluation = { key: 'eval', title: '自动评测', state: 'blocked', detail: '发布后才能评测助手答得对不对。' }
  } else if (!facts.evalAccount) {
    evaluation = { key: 'eval', title: '自动评测', state: 'todo', detail: '配一个只能查询的平台评测账号，平台就能自己出题、自己评分。', action: { kind: 'eval-account', label: '使用平台评测账号' } }
  } else if (facts.lastPassRate === null) {
    evaluation = { key: 'eval', title: '自动评测', state: 'todo', detail: '评测账号已配好，还没评测过。', action: { kind: 'eval-open', label: '去评测' } }
  } else {
    evaluation = { key: 'eval', title: '自动评测', state: 'done', detail: `最近一次评测通过率 ${percent(facts.lastPassRate)}。`, action: { kind: 'eval-open', label: '查看评测' } }
  }

  return [apis, auth, publish, login, evaluation]
}

/** 必做的步骤都完成了就算接入完成；可选的和当前账号看不到的不算。 */
export function onboardingProgress(steps: OnboardingStep[]) {
  const required = steps.filter(step => step.state !== 'optional' && step.state !== 'unknown')
  const done = required.filter(step => step.state === 'done').length
  return { done, total: required.length, complete: required.length > 0 && done === required.length }
}

/** 第一个现在就能做的步骤，用来高亮「下一步」；必做的都做完了再提可选的。 */
export function nextOnboardingStep(steps: OnboardingStep[]) {
  return steps.find(step => step.state === 'todo') ?? steps.find(step => step.state === 'optional' && step.action) ?? null
}
