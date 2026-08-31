import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('WorkflowStudio conversation composer frame contract', () => {
  it('keeps custom composer slot but does not re-own shared glass material', () => {
    const studioSrc = readFileSync(
      resolve(__dirname, '../views/workflow/WorkflowStudio.vue'),
      'utf8',
    )
    const drawerSrc = readFileSync(
      resolve(__dirname, '../views/workflow/studio-overlays/WorkflowStudioDebugDrawer.vue'),
      'utf8',
    )
    expect(studioSrc).toMatch(/WorkflowStudioDebugDrawer/)
    expect(drawerSrc).toMatch(/ConversationView/)
    expect(drawerSrc).toMatch(/debug-chat-composer/)
    expect(drawerSrc).toMatch(/UnifiedInteractionRenderer/)
    expect(drawerSrc).not.toMatch(/backdrop-filter:\s*var\(--reachai-chat-glass-blur-composer\)/)
    expect(drawerSrc).not.toMatch(/background:\s*var\(--reachai-chat-glass-composer\)/)
  })
})
