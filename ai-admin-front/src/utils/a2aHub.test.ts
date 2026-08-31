import { describe, expect, it } from 'vitest'
import {
  a2aFormatBytes,
  a2aLabel,
  a2aTaskCenterDeepLink,
  a2aTaskTerminal,
  a2aTone,
} from './a2aHub'

describe('a2aHub presentation semantics', () => {
  it('keeps official task states readable without changing their value', () => {
    expect(a2aLabel('TASK_STATE_INPUT_REQUIRED')).toBe('等待输入')
    expect(a2aTone('TASK_STATE_FAILED')).toBe('danger')
  })

  it('recognizes only terminal A2A task states as terminal', () => {
    expect(a2aTaskTerminal('TASK_STATE_COMPLETED')).toBe(true)
    expect(a2aTaskTerminal('TASK_STATE_CANCELED')).toBe(true)
    expect(a2aTaskTerminal('TASK_STATE_WORKING')).toBe(false)
  })

  it('formats payload sizes without exposing content', () => {
    expect(a2aFormatBytes(512)).toBe('512 B')
    expect(a2aFormatBytes(2048)).toBe('2.0 KB')
  })

  it('parses only complete direction-qualified Task Center deep links', () => {
    expect(a2aTaskCenterDeepLink({ taskId: ' task-42 ', direction: 'outbound' })).toEqual({
      taskId: 'task-42',
      direction: 'OUTBOUND',
    })
    expect(a2aTaskCenterDeepLink({ taskId: ['task-7'], direction: ['INBOUND'] })).toEqual({
      taskId: 'task-7',
      direction: 'INBOUND',
    })
    expect(a2aTaskCenterDeepLink({ taskId: 'task-42' })).toBeNull()
    expect(a2aTaskCenterDeepLink({ taskId: 'task-42', direction: 'SIDEWAYS' })).toBeNull()
  })
})
