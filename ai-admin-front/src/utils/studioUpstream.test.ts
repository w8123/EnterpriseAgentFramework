import { describe, expect, it } from 'vitest'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import { upstreamNodeIds } from './studioUpstream'

const node = (id: string, data: Record<string, unknown> = {}) => ({ id, data: { kind: 'llm', ...data } }) as unknown as CanvasNode
const edge = (source: string, target: string) => ({ id: `${source}-${target}`, source, target }) as CanvasEdge

describe('upstreamNodeIds', () => {
  it('only lists nodes that run before the selected node', () => {
    const nodes = [node('a'), node('b'), node('c'), node('d')]
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('a', 'd')]
    expect([...upstreamNodeIds('c', nodes, edges)].sort()).toEqual(['a', 'b'])
    expect([...upstreamNodeIds('a', nodes, edges)]).toEqual([])
  })

  it('lets a loop body node read the loop and what runs before it', () => {
    const nodes = [node('start'), node('loop', { kind: 'loop', loopConfig: { bodyNodeIds: ['body'] } }), node('body')]
    const edges = [edge('start', 'loop')]
    expect([...upstreamNodeIds('body', nodes, edges)].sort()).toEqual(['loop', 'start'])
  })
})
