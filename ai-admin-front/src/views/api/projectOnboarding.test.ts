import { describe, expect, it } from 'vitest'
import { nextOnboardingStep, onboardingProgress, onboardingSteps, type OnboardingFacts } from './projectOnboarding'

const empty: OnboardingFacts = {
  apiTotal: 0, acceptedTotal: 0, authStatus: null, employeeToolCount: null,
  loginEntry: false, evalAccount: false, lastPassRate: null,
}

function states(facts: Partial<OnboardingFacts>) {
  return onboardingSteps({ ...empty, ...facts }).map(step => `${step.key}:${step.state}`)
}

describe('project onboarding guide', () => {
  it('starts a new system at adding APIs and holds back what cannot be done yet', () => {
    const steps = onboardingSteps(empty)
    expect(steps.map(step => `${step.key}:${step.state}`)).toEqual(
      ['apis:todo', 'auth:blocked', 'publish:blocked', 'login:optional', 'eval:blocked'])
    expect(nextOnboardingStep(steps)?.action?.kind).toBe('add')
    expect(onboardingProgress(steps)).toEqual({ done: 0, total: 4, complete: false })
  })

  it('asks to accept APIs before auth when none is accepted', () => {
    const steps = onboardingSteps({ ...empty, apiTotal: 35 })
    expect(steps[0].action?.kind).toBe('accept')
    expect(steps[1].state).toBe('blocked')
  })

  it('walks one step at a time', () => {
    expect(states({ apiTotal: 35, acceptedTotal: 13 })).toEqual(
      ['apis:done', 'auth:todo', 'publish:blocked', 'login:optional', 'eval:blocked'])
    expect(states({ apiTotal: 35, acceptedTotal: 13, authStatus: 'CONFIGURED' })).toEqual(
      ['apis:done', 'auth:done', 'publish:todo', 'login:optional', 'eval:blocked'])
    expect(states({ apiTotal: 35, acceptedTotal: 13, authStatus: 'CONFIGURED', employeeToolCount: 13 })).toEqual(
      ['apis:done', 'auth:done', 'publish:done', 'login:optional', 'eval:todo'])
  })

  it('offers the platform eval account first, then the first run, then shows the score', () => {
    const base = { apiTotal: 3, acceptedTotal: 3, authStatus: 'CONFIGURED', employeeToolCount: 3 }
    expect(onboardingSteps({ ...empty, ...base })[4].action?.kind).toBe('eval-account')
    expect(onboardingSteps({ ...empty, ...base, evalAccount: true })[4].action?.kind).toBe('eval-open')
    const scored = onboardingSteps({ ...empty, ...base, evalAccount: true, lastPassRate: 0.875 })[4]
    expect(scored.state).toBe('done')
    expect(scored.detail).toContain('88%')
  })

  it('never makes the login entry a required step, but suggests it once the rest is done', () => {
    const base = { apiTotal: 3, acceptedTotal: 3, authStatus: 'CONFIGURED', employeeToolCount: 3, evalAccount: true, lastPassRate: 1 }
    const missing = onboardingSteps({ ...empty, ...base })
    expect(missing[3].state).toBe('optional')
    expect(onboardingProgress(missing)).toEqual({ done: 4, total: 4, complete: true })
    expect(nextOnboardingStep(missing)?.key).toBe('login')
    const hidden = onboardingSteps({ ...empty, ...base, loginEntry: null })
    expect(hidden[3].state).toBe('unknown')
    expect(hidden[3].action).toBeUndefined()
    expect(nextOnboardingStep(hidden)).toBeNull()
    const configured = onboardingSteps({ ...empty, ...base, loginEntry: true })
    expect(onboardingProgress(configured)).toEqual({ done: 5, total: 5, complete: true })
  })

  it('flags a broken auth configuration', () => {
    const auth = onboardingSteps({ ...empty, apiTotal: 1, acceptedTotal: 1, authStatus: 'BLOCKED' })[1]
    expect(auth.state).toBe('todo')
    expect(auth.detail).toContain('有问题')
  })
})
