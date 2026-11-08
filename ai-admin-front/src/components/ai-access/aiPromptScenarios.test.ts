import { describe, expect, it } from 'vitest'
import {
  AI_PROMPT_SCENARIOS, findAiPromptScenario, groupAiPromptScenarios, missingAiPromptVariables, renderAiPrompt,
} from './aiPromptScenarios'

describe('aiPromptScenarios', () => {
  it('collects every prompt file with a unique key and known tools', () => {
    const keys = AI_PROMPT_SCENARIOS.map(item => item.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys).toEqual(expect.arrayContaining([
      'project-workbench-onboarding', 'page-workbench', 'doctor-project', 'embed-react', 'backend-go', 'employee-business',
    ]))
    for (const item of AI_PROMPT_SCENARIOS) {
      expect(item.title).toMatch(/\S/)
      const used = [...item.template.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map(match => match[1])
      const declared = new Set(['reachaiUrl', ...item.variables.map(variable => variable.name)])
      for (const name of used) expect(declared.has(name!), `${item.key} uses undeclared {{${name}}}`).toBe(true)
    }
  })

  it('fills variables and reports missing required ones', () => {
    const scenario = findAiPromptScenario('project-workbench-onboarding')!
    expect(missingAiPromptVariables(scenario, {}).map(item => item.name)).toEqual(['projectCode'])
    expect(missingAiPromptVariables(scenario, { projectCode: 'yudao' })).toEqual([])
    const text = renderAiPrompt(scenario, { projectCode: 'yudao' })
    expect(text).toContain('projectCode="yudao"')
    expect(text).toContain('reachai_project_onboarding_start')
    expect(text).toContain('reachai_ai_coding_task_context')
    expect(text).toContain('OTHER')
    expect(text).not.toContain('reachai_ai_coding_task_claim')
    expect(text).not.toContain('claimCode')
    expect(text).not.toMatch(/\{\{/)
  })

  it('offers repository onboarding without a task or claim code', () => {
    const scenario = findAiPromptScenario('project-onboarding')!
    expect(missingAiPromptVariables(scenario, {})).toEqual([])
    const text = renderAiPrompt(scenario, {})
    expect(text).toContain('reachai_project_onboarding_start')
    expect(text).toContain('reachai_onboarding_enrollment_request')
    expect(text).toContain('AI 操作审核')
  })

  it('marks optional blanks as unspecified and groups by category order', () => {
    const scenario = findAiPromptScenario('workflow-draft')!
    expect(renderAiPrompt(scenario, {})).toContain('项目：（未指定）')
    const groups = groupAiPromptScenarios(AI_PROMPT_SCENARIOS)
    expect(groups[0]!.category).toBe('onboarding')
  })
})
