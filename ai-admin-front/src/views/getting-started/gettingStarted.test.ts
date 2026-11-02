import { describe, expect, it } from 'vitest'
import { buildGettingStartedProgress, type GettingStartedFacts } from './gettingStarted'
import type { ModelInstance } from '@/types/model'
import type { ScanProject } from '@/types/scanProject'
import type { McpPublication } from '@/types/mcp'

const model = (overrides: Partial<ModelInstance>) => ({ id: '1', name: 'DeepSeek', status: 'ACTIVE', modelType: 'LLM', ...overrides }) as ModelInstance
const project = (name: string) => ({ id: 1, name }) as ScanProject
const publication = (state: McpPublication['state']) => ({ id: 1, name: '芋道员工工具', state }) as McpPublication

const empty: GettingStartedFacts = { models: [], projects: [], publications: [], activeEmployeeConnections: 0 }

describe('buildGettingStartedProgress', () => {
  it('points a fresh installation at connecting a model first', () => {
    const progress = buildGettingStartedProgress(empty)
    expect(progress.steps.map((step) => step.state)).toEqual(['next', 'todo', 'todo', 'todo'])
    expect(progress.nextStep?.key).toBe('model')
    expect(progress.doneCount).toBe(0)
  })

  it('only counts active chat models, not embeddings or disabled ones', () => {
    const progress = buildGettingStartedProgress({
      ...empty,
      models: [model({ modelType: 'EMBEDDING' }), model({ status: 'DISABLED' })],
    })
    expect(progress.steps[0].state).toBe('next')
  })

  it('moves the next step forward and summarises finished ones', () => {
    const progress = buildGettingStartedProgress({
      ...empty,
      models: [model({ name: 'DeepSeek V4' })],
      projects: [project('芋道 ERP'), project('OA'), project('CRM')],
      publications: [publication('DRAFT')],
    })
    expect(progress.steps.map((step) => step.state)).toEqual(['done', 'done', 'next', 'todo'])
    expect(progress.steps[0].summary).toBe('已有 1 个可用模型：DeepSeek V4')
    expect(progress.steps[1].summary).toBe('已接入 3 个系统：芋道 ERP、OA 等')
    expect(progress.nextStep?.actionPath).toBe('/mcp-hub/publications')
  })

  it('marks steps it cannot read as unknown without blocking the others', () => {
    const progress = buildGettingStartedProgress({ ...empty, models: null })
    expect(progress.steps[0].state).toBe('unknown')
    expect(progress.nextStep?.key).toBe('system')
  })

  it('is all done once an employee is connected', () => {
    const progress = buildGettingStartedProgress({
      models: [model({})],
      projects: [project('芋道 ERP')],
      publications: [publication('PUBLISHED')],
      activeEmployeeConnections: 2,
    })
    expect(progress.allDone).toBe(true)
    expect(progress.nextStep).toBeNull()
    expect(progress.steps[3].summary).toBe('2 个员工连接正在使用')
  })
})
