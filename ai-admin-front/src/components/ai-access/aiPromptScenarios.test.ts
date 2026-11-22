import { describe, expect, it } from 'vitest'
import {
  AI_PROMPT_SCENARIOS, findAiPromptScenario, missingAiPromptVariables, renderAiPrompt,
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
      const declared = new Set(['reachaiUrl', ...item.variables.map(variable => variable.name)])
      const used = [...item.template.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map(match => match[1])
      for (const name of used) expect(declared.has(name!), `${item.key} uses undeclared {{${name}}}`).toBe(true)
    }
  })

  it('fills variables and reports missing required ones', () => {
    const scenario = findAiPromptScenario('project-workbench-onboarding')!
    expect(missingAiPromptVariables(scenario, {}).map(item => item.name)).toEqual(['projectCode'])
    expect(missingAiPromptVariables(scenario, { projectCode: 'yudao' })).toEqual([])
    const text = renderAiPrompt(scenario, { projectCode: 'yudao' })
    expect(text).toContain('projectCode="yudao"')
    expect(text).toContain('reachai_integration_guide（topic="project-workbench-onboarding"')
    expect(text).not.toContain('STARTED')
    expect(text).not.toContain('reachai_ai_coding_task_claim')
    expect(text).not.toContain('claimCode')
    expect(text).not.toMatch(/\{\{/)
  })

  it('offers repository onboarding without a task or claim code', () => {
    const scenario = findAiPromptScenario('project-onboarding')!
    expect(missingAiPromptVariables(scenario, {})).toEqual([])
    const text = renderAiPrompt(scenario, {})
    expect(text).toContain('reachai_integration_guide（topic="project-onboarding"）')
  })

  it.each(['page-workbench', 'ai-coding-task'])('hands off actual task context and defers operation rules to the guide for %s', (key) => {
    const scenario = findAiPromptScenario(key)!
    const text = renderAiPrompt(scenario, { projectCode: 'orders', taskId: 'task-1', claimCode: 'fixture-code' })
    expect(text).toContain(`reachai_integration_guide（topic="${key}"`)
    expect(text).toContain('taskId="task-1"，claimCode="fixture-code"')
    expect(text).not.toContain('STARTED')
    expect(text).not.toContain('PowerShell')
    expect(text).not.toContain('taskToken=')
  })

  it('marks optional context as unspecified rather than inventing a target', () => {
    const scenario = findAiPromptScenario('workflow-draft')!
    expect(renderAiPrompt(scenario, {})).toContain('projectCode="（未指定）"')
  })
})
