import { describe, expect, it } from 'vitest'
import { toolCallFailed, toolCallStatusLabel, toolCallStatusTone } from './toolCallOutcome'

describe('RunOps resumed tool outcomes', () => {
  it('shows a completed resume as successful while retaining its original pause observation', () => {
    const tool = { success: false, status: 'SUCCESS' }
    expect(toolCallFailed(tool)).toBe(false)
    expect(toolCallStatusLabel(tool)).toBe('成功')
    expect(toolCallStatusTone(tool)).toBe('success')
    expect(tool.success).toBe(false)
  })

  it.each(['FAILED', 'ERROR', 'CANCELLED', 'TIMED_OUT', 'TIMEOUT'])('keeps the final %s outcome visible', (status) => {
    const tool = { success: true, status }
    expect(toolCallFailed(tool)).toBe(true)
    expect(toolCallStatusTone(tool)).toBe('danger')
  })

  it.each([
    ['WAITING_USER', '等待用户交互'],
    ['UNKNOWN', '状态未确认'],
    ['UNRECOGNIZED', '状态未确认'],
  ])('does not mistake %s for success or failure', (status, label) => {
    const tool = { success: false, status }
    expect(toolCallFailed(tool)).toBe(false)
    expect(toolCallStatusTone(tool)).toBe('warning')
    expect(toolCallStatusLabel(tool)).toBe(label)
  })

  it('still interprets ordinary audit records from older responses', () => {
    expect(toolCallFailed({ success: false })).toBe(true)
    expect(toolCallStatusLabel({ success: true })).toBe('成功')
  })
})
