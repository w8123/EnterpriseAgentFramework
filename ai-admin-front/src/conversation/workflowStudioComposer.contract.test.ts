import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('WorkflowStudio conversation composer frame contract', () => {
  it('keeps custom composer slot but does not re-own shared glass material', () => {
    const src = readFileSync(
      resolve(__dirname, '../views/workflow/WorkflowStudio.vue'),
      'utf8',
    )
    expect(src).toMatch(/ConversationView/)
    expect(src).toMatch(/debug-chat-composer/)
    expect(src).toMatch(/UnifiedInteractionRenderer/)
    expect(src).not.toMatch(/backdrop-filter:\s*var\(--reachai-chat-glass-blur-composer\)/)
    expect(src).not.toMatch(/background:\s*var\(--reachai-chat-glass-composer\)/)
  })
})
