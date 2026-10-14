import { describe, expect, it } from 'vitest'
import { graphPathBounds, graphRectVisible } from './useGraphViewport'

describe('graph viewport geometry', () => {
  it('keeps viewport intersections and overscan but culls distant cards without changing their coordinates', () => {
    const viewport = { x: 1000, y: 800, width: 500, height: 400 }
    expect(graphRectVisible({ x: 950, y: 900, width: 268, height: 160 }, viewport)).toBe(true)
    expect(graphRectVisible({ x: 1600, y: 900, width: 268, height: 160 }, viewport)).toBe(true)
    expect(graphRectVisible({ x: 3000, y: 900, width: 268, height: 160 }, viewport)).toBe(false)
    expect(graphRectVisible({ x: 3000, y: 900, width: 268, height: 160 }, null)).toBe(true)
  })
  it('retains a crossing edge whose endpoint cards are offscreen and conservatively handles unknown paths', () => {
    const bounds = graphPathBounds('M 0 100 L 500 100 Q 520 100 520 120 L 2000 120')
    expect(bounds).toEqual({ x: 0, y: 100, width: 2000, height: 20 })
    expect(graphRectVisible(bounds!, { x: 900, y: 0, width: 200, height: 300 }, 0)).toBe(true)
    expect(graphPathBounds('')).toBeNull()
    const arc = graphPathBounds('M 3000 3000 L 3050 3000 A 12 12 0 0 1 3062 3012 L 3300 3200')
    expect(arc?.x).toBeGreaterThan(2900)
    expect(graphRectVisible(arc!, { x: 0, y: 0, width: 500, height: 400 })).toBe(false)
  })
})
