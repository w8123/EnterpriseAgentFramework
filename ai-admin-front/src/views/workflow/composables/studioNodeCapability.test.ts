import { describe, expect, it } from 'vitest'
import type { AgentGraphNodeTypeDescriptor } from '@/types/agent'
import {
  STUDIO_NODE_REGISTRY,
  canCreateStudioNodeKind,
  enabledStudioNodeKinds,
  resolveStudioNodeCreation,
  studioNodeCapabilityMap,
} from '@/utils/studioNodeRegistry'

function descriptor(partial: Partial<AgentGraphNodeTypeDescriptor> & Pick<AgentGraphNodeTypeDescriptor, 'type' | 'canvasKind'>): AgentGraphNodeTypeDescriptor {
  return {
    canvasCategory: 'flow',
    family: 'FLOW',
    retryable: false,
    aliases: [],
    maturity: 'STABLE',
    runtimeExecutable: true,
    publishable: true,
    studioEnabled: true,
    aiAuthoringEnabled: true,
    unavailableReason: null,
    ...partial,
  }
}

describe('enabledStudioNodeKinds', () => {
  it('only enables runtimeExecutable && studioEnabled nodes', () => {
    const enabled = enabledStudioNodeKinds([
      descriptor({ type: 'LLM', canvasKind: 'llm' }),
      descriptor({
        type: 'INTERACTION',
        canvasKind: 'interaction',
        maturity: 'BETA',
        runtimeExecutable: true,
        publishable: false,
        studioEnabled: false,
        aiAuthoringEnabled: false,
      }),
      descriptor({
        type: 'CODE',
        canvasKind: 'code',
        maturity: 'PLANNED',
        runtimeExecutable: false,
        publishable: false,
        studioEnabled: false,
        aiAuthoringEnabled: false,
        unavailableReason: 'Runtime Handler is not implemented for this node type',
      }),
      descriptor({
        type: 'PAGE_ACTION',
        canvasKind: 'pageAction',
        maturity: 'BETA',
      }),
    ], true)

    expect(enabled.has('llm')).toBe(true)
    expect(enabled.has('pageAction')).toBe(true)
    expect(enabled.has('interaction')).toBe(false)
    expect(enabled.has('code')).toBe(false)
    expect(enabled.has('start')).toBe(true)
    expect(enabled.has('end')).toBe(true)
  })

  it('fails closed when catalog is unloaded or empty', () => {
    expect(enabledStudioNodeKinds([], true)).toEqual(new Set(['start', 'end']))
    expect(enabledStudioNodeKinds([
      descriptor({ type: 'LLM', canvasKind: 'llm' }),
    ], false)).toEqual(new Set(['start', 'end']))
    expect(enabledStudioNodeKinds(
      Object.keys(STUDIO_NODE_REGISTRY).map((kind) => descriptor({
        type: 'LLM',
        canvasKind: kind as any,
      })),
      false,
    )).toEqual(new Set(['start', 'end']))
  })

  it('keeps capability map for existing node kinds after new fields', () => {
    const map = studioNodeCapabilityMap([
      descriptor({
        type: 'PAGE_ACTION',
        canvasKind: 'pageAction',
        maturity: 'BETA',
        unavailableReason: null,
      }),
      descriptor({
        type: 'INTERACTION',
        canvasKind: 'interaction',
        maturity: 'BETA',
        runtimeExecutable: true,
        studioEnabled: false,
        publishable: false,
        aiAuthoringEnabled: false,
        unavailableReason: 'Workflow interaction pause/resume closure is not complete',
      }),
    ])
    expect(map.pageAction?.maturity).toBe('BETA')
    expect(map.interaction?.studioEnabled).toBe(false)
    expect(map.interaction?.unavailableReason).toContain('pause/resume')
  })
})

describe('resolveStudioNodeCreation / paste create guard', () => {
  const catalog = [
    descriptor({ type: 'LLM', canvasKind: 'llm' }),
    descriptor({ type: 'TOOL', canvasKind: 'tool' }),
    descriptor({
      type: 'INTERACTION',
      canvasKind: 'interaction',
      maturity: 'BETA',
      runtimeExecutable: true,
      publishable: false,
      studioEnabled: false,
      aiAuthoringEnabled: false,
      unavailableReason: 'Workflow interaction pause/resume closure is not complete',
    }),
    descriptor({
      type: 'CODE',
      canvasKind: 'code',
      maturity: 'PLANNED',
      runtimeExecutable: false,
      publishable: false,
      studioEnabled: false,
      aiAuthoringEnabled: false,
      unavailableReason: 'Runtime Handler is not implemented for this node type',
    }),
  ]

  it('blocks INTERACTION and PLANNED paste/create while allowing STABLE kinds', () => {
    expect(canCreateStudioNodeKind('interaction', catalog, true)).toBe(false)
    expect(resolveStudioNodeCreation('interaction', catalog, true).reason).toContain('pause/resume')
    expect(canCreateStudioNodeKind('code', catalog, true)).toBe(false)
    expect(resolveStudioNodeCreation('code', catalog, true).reason).toContain('Runtime Handler')
    expect(canCreateStudioNodeKind('llm', catalog, true)).toBe(true)
    expect(canCreateStudioNodeKind('tool', catalog, true)).toBe(true)
  })

  it('fails closed when catalog is unloaded and reports no canvas mutation should occur', () => {
    const blocked = resolveStudioNodeCreation('llm', catalog, false)
    expect(blocked.allowed).toBe(false)
    expect(blocked.reason).toContain('未加载')
    const empty = resolveStudioNodeCreation('llm', [], true)
    expect(empty.allowed).toBe(false)
    // Pure guard decision is the gate used by pasteCopiedNode before nodes/edges/dirty mutation.
    expect(canCreateStudioNodeKind('llm', [], true)).toBe(false)
  })
})
