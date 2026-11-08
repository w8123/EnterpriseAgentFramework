import { describe, expect, it } from 'vitest'
import {
  aiCodingConnectionCanRestore,
  aiCodingProviderLabel,
  AI_CODING_EXECUTOR_OPTIONS,
  aiCodingConnectionStatusDescription,
  aiCodingConnectionStatusLabel,
  aiCodingExecutionAcceptsClientAccess,
  aiCodingExecutionIsTerminal,
  aiCodingExecutionStatusTagType,
  aiCodingExecutionStatusLabel,
  aiCodingTaskStatusTagType,
} from './aiCodingPresentation'

describe('aiCodingPresentation', () => {
  it('shows other MCP coding tools without changing the manual selector options', () => {
    expect(aiCodingProviderLabel('OTHER')).toBe('其他 AI 工具')
    expect(AI_CODING_EXECUTOR_OPTIONS.map(option => option.value)).toEqual(['CODEX', 'CURSOR', 'TRAE', 'CLAUDE_CODE'])
  })

  it('keeps connection activity separate from delivery and acceptance status', () => {
    expect(aiCodingConnectionStatusLabel('ACTIVE')).toBe('对接中')
    expect(aiCodingConnectionStatusLabel('TIMED_OUT')).toBe('对接超时')
    expect(aiCodingExecutionStatusLabel('RESULT_APPLIED')).toBe(
      'AI 编程工具已反馈，待平台验证',
    )
    expect(aiCodingExecutionStatusLabel('ACCEPTANCE_READY')).toBe('待人工验收')
  })

  it('stops offering client recovery after coding delivery is closed', () => {
    expect(aiCodingExecutionAcceptsClientAccess('RESULT_APPLIED')).toBe(true)
    expect(aiCodingExecutionAcceptsClientAccess('ACCEPTANCE_READY')).toBe(false)
    expect(aiCodingExecutionAcceptsClientAccess('COMPLETED')).toBe(false)
    expect(aiCodingExecutionIsTerminal('ACCEPTANCE_READY')).toBe(false)
    expect(aiCodingExecutionIsTerminal('COMPLETED')).toBe(true)
  })

  it('only restores sessions whose task token can still be valid', () => {
    expect(aiCodingConnectionCanRestore({
      status: 'ACTIVE',
      activatedAt: '2026-07-27T12:00:00',
    })).toBe(true)
    expect(aiCodingConnectionCanRestore({
      status: 'TIMED_OUT',
      timeoutReason: 'LEASE_EXPIRED',
      activatedAt: '2026-07-27T12:00:00',
    })).toBe(true)
    expect(aiCodingConnectionCanRestore({
      status: 'TIMED_OUT',
      timeoutReason: 'TOKEN_EXPIRED',
      activatedAt: '2026-07-27T12:00:00',
    })).toBe(false)
    expect(aiCodingConnectionCanRestore({
      status: 'CLOSED',
      activatedAt: '2026-07-27T12:00:00',
    })).toBe(false)
    expect(aiCodingConnectionStatusDescription({
      status: 'TIMED_OUT',
      timeoutReason: 'TOKEN_EXPIRED',
    })).toBe('对接超时（任务凭据已过期）')
  })

  it('uses one semantic task-status tone policy across workbenches', () => {
    expect(aiCodingExecutionStatusTagType('WAITING_USER')).toBe('warning')
    expect(aiCodingExecutionStatusTagType('ACCEPTANCE_READY')).toBe('success')
    expect(aiCodingExecutionStatusTagType('FAILED')).toBe('danger')
    expect(aiCodingExecutionStatusTagType('CANCELLED')).toBe('info')
    expect(aiCodingExecutionStatusTagType('RUNNING')).toBe('primary')
    expect(aiCodingTaskStatusTagType({
      executionStatus: 'RUNNING',
      connection: { status: 'TIMED_OUT' },
    })).toBe('warning')
  })
})
