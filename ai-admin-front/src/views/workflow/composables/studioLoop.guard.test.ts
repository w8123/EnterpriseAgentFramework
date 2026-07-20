import { describe, expect, it } from 'vitest'
import type { CanvasNode } from '@/types/studio'
import {
  defaultLoopConfig,
  listLoopBodyCandidates,
  normalizeMaxIterations,
  occupiedLoopBodyNodeIds,
  syncLoopBodyMembership,
} from '@/utils/studioLoop'
import { connectionCondition } from '@/views/workflow/composables/useWorkflowStudioCanvasActions'

function node(id: string, kind: CanvasNode['data']['kind'], extra: Partial<CanvasNode['data']> = {}): CanvasNode {
  return {
    id,
    type: kind,
    position: { x: 0, y: 0 },
    data: {
      label: id,
      kind,
      configVersion: 2,
      ...extra,
    },
  }
}

describe('studioLoop contract', () => {
  it('defaults maxIterations to 100', () => {
    expect(defaultLoopConfig().maxIterations).toBe(100)
    expect(normalizeMaxIterations(undefined)).toBe(100)
    expect(normalizeMaxIterations(0)).toBe(100)
    expect(normalizeMaxIterations(3)).toBe(3)
  })

  it('LOOP outgoing edges always serialize as always', () => {
    const loop = node('loop-1', 'loop')
    expect(connectionCondition(loop, 'continue')).toBe('always')
    expect(connectionCondition(loop, 'done')).toBe('always')
    expect(connectionCondition(loop, undefined)).toBe('always')
  })

  it('lists body candidates excluding forbidden and occupied nodes', () => {
    const nodes = [
      node('start', 'start'),
      node('end', 'end'),
      node('loop-a', 'loop', {
        loopConfig: {
          ...defaultLoopConfig(),
          bodyNodeIds: ['tpl-a'],
          bodyEntry: 'tpl-a',
          bodyExit: 'tpl-a',
        },
      }),
      node('loop-b', 'loop', { loopConfig: defaultLoopConfig() }),
      node('tpl-a', 'template'),
      node('tpl-b', 'template'),
      node('interaction', 'interaction'),
      node('approval', 'approval'),
    ]
    const candidates = listLoopBodyCandidates({ loopNodeId: 'loop-b', nodes })
    expect(candidates.map((item) => item.id)).toEqual(['tpl-b'])
    expect(occupiedLoopBodyNodeIds(nodes, 'loop-b').has('tpl-a')).toBe(true)
  })

  it('sync keeps membership when canvas empty, and cleans deleted/forbidden ids', () => {
    const loopConfig = {
      ...defaultLoopConfig(),
      bodyNodeIds: ['tpl', 'gone'],
      bodyEntry: 'tpl',
      bodyExit: 'gone',
      collection: 'var.items',
    }
    const emptySync = syncLoopBodyMembership({
      loopNodeId: 'loop-1',
      nodes: [],
      loopConfig,
    })
    expect(emptySync.bodyNodeIds).toEqual(['tpl', 'gone'])

    const synced = syncLoopBodyMembership({
      loopNodeId: 'loop-1',
      nodes: [
        node('loop-1', 'loop', { loopConfig }),
        node('tpl', 'template'),
        node('interaction', 'interaction'),
      ],
      loopConfig: {
        ...loopConfig,
        bodyNodeIds: ['tpl', 'gone', 'interaction'],
      },
    })
    expect(synced.bodyNodeIds).toEqual(['tpl'])
    expect(synced.bodyEntry).toBe('tpl')
    expect(synced.bodyExit).toBe('tpl')
  })
})
