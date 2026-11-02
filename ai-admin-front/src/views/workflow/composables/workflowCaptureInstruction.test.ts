import { describe, expect, it } from 'vitest'
import { captureInstructionForEmptyDraft } from './workflowCaptureInstruction'

describe('captureInstructionForEmptyDraft', () => {
  it('prefills an empty saved graph even when the canvas renders its start and end placeholders', () => {
    expect(captureInstructionForEmptyDraft(' 查询仓库 ', true, '{"nodes":[]}', ['start', 'end'])).toBe('查询仓库')
  })

  it('does not prefill an existing, edited, unreadable or read-only workflow', () => {
    expect(captureInstructionForEmptyDraft('x', true, '{"nodes":[{"id":"n"}]}', ['start', 'end'])).toBe('')
    expect(captureInstructionForEmptyDraft('x', true, '{"nodes":[]}', ['start', 'tool', 'end'])).toBe('')
    expect(captureInstructionForEmptyDraft('x', true, '{', ['start', 'end'])).toBe('')
    expect(captureInstructionForEmptyDraft('x', false, '{"nodes":[]}', ['start', 'end'])).toBe('')
  })
})
