import { describe, expect, it } from 'vitest'

import { createLiquidGlassMap, edgeDisplacement, liquidDropOutline, liquidSurfaceSdf, roundedRectSdf } from './liquidGlass'

const options = { width: 200, height: 100, radius: 16, bezel: 16, depth: 10 }

describe('liquid glass displacement field', () => {
  it('measures signed distance to a rounded rectangle', () => {
    expect(roundedRectSdf(0, 0, 100, 50, 16)).toBeLessThan(0)
    expect(roundedRectSdf(100, 0, 100, 50, 16)).toBeCloseTo(0, 5)
    expect(roundedRectSdf(120, 0, 100, 50, 16)).toBeCloseTo(20, 5)
  })

  it('leaves the interior untouched so content behind the centre is not distorted', () => {
    expect(edgeDisplacement(100, 50, options)).toEqual({ dx: 0, dy: 0 })
    expect(edgeDisplacement(40, 50, options)).toEqual({ dx: 0, dy: 0 })
  })

  it('samples inward near each edge, strongest at the rim', () => {
    const left = edgeDisplacement(1, 50, options)
    const right = edgeDisplacement(199, 50, options)
    const top = edgeDisplacement(100, 1, options)
    const bottom = edgeDisplacement(100, 99, options)

    expect(left.dx).toBeGreaterThan(0)
    expect(right.dx).toBeLessThan(0)
    expect(top.dy).toBeGreaterThan(0)
    expect(bottom.dy).toBeLessThan(0)
    expect(Math.abs(left.dx)).toBeLessThanOrEqual(options.depth)
    expect(Math.abs(edgeDisplacement(10, 50, options).dx)).toBeLessThan(Math.abs(left.dx))
  })

  it('keeps corner refraction finite, symmetric and within the requested depth', () => {
    const left = edgeDisplacement(6, 6, options)
    const right = edgeDisplacement(194, 6, options)
    expect(left.dx).toBeGreaterThan(0)
    expect(left.dy).toBeGreaterThan(0)
    expect(right.dx).toBeCloseTo(-left.dx, 5)
    expect(right.dy).toBeCloseTo(left.dy, 5)
    expect(Math.hypot(left.dx, left.dy)).toBeLessThanOrEqual(options.depth)
    expect(edgeDisplacement(-1, 50, options)).toEqual({ dx: 0, dy: 0 })
  })

  it('does not allocate optical maps for hidden or invalid surfaces', () => {
    expect(createLiquidGlassMap({ width: 0, height: 100, radius: 16 })).toBeNull()
    expect(createLiquidGlassMap({ width: 200, height: 0, radius: 16 })).toBeNull()
    expect(createLiquidGlassMap({ width: Number.NaN, height: 100, radius: 16 })).toBeNull()
    expect(createLiquidGlassMap({ width: 200, height: 100, radius: 16, depth: Infinity })).toBeNull()
  })

  it.each([
    ['top-left', 24, 22, 32, 35, 150, 12, 24, 5, 1],
    ['top-right', 166, 22, 158, 35, 40, 12, 166, 5, 1],
    ['bottom-left', 24, 208, 32, 195, 150, 218, 24, 225, -1],
    ['bottom-right', 166, 208, 158, 195, 40, 218, 166, 225, -1],
  ] as const)('keeps the %s droplet joined to the menu with matching rim and inward refraction', (dropCorner, x, y, shoulderX, shoulderY, outsideX, outsideY, rimX, rimY, direction) => {
    const drop = { ...options, width: 190, height: 230, shape: 'droplet' as const, dropCorner }
    expect(liquidSurfaceSdf(x, y, drop)).toBeLessThan(0)
    expect(liquidSurfaceSdf(shoulderX, shoulderY, drop)).toBeLessThan(0)
    expect(liquidSurfaceSdf(95, 120, drop)).toBeLessThan(-drop.bezel)
    expect(liquidSurfaceSdf(outsideX, outsideY, drop)).toBeGreaterThan(0)
    expect(edgeDisplacement(outsideX, outsideY, drop)).toEqual({ dx: 0, dy: 0 })
    expect(edgeDisplacement(rimX, rimY, drop).dy * direction).toBeGreaterThan(0)
    const outline = liquidDropOutline(drop.width, drop.height, dropCorner)
    const points = [...outline.matchAll(/(?:M|L) ([\d.-]+) ([\d.-]+)/g)]
    expect(points).toHaveLength(192)
    for (const point of points) {
      expect(Math.abs(liquidSurfaceSdf(Number(point[1]), Number(point[2]), drop))).toBeLessThan(0.02)
    }
  })

  it.each(['left', 'right'] as const)('points the lower droplet out of the %s edge at the trigger height', (dropSide) => {
    const dropCorner = dropSide === 'right' ? 'bottom-right' as const : 'bottom-left' as const
    const drop = { ...options, width: 190, height: 230, shape: 'droplet' as const, dropCorner, dropSide, dropOffset: 184 }
    const x = dropSide === 'right' ? 168 : 22
    const rimX = dropSide === 'right' ? 185 : 5
    expect(liquidSurfaceSdf(x, 184, drop)).toBeLessThan(0)
    const normal = edgeDisplacement(rimX, 184, drop)
    expect(normal.dx * (dropSide === 'right' ? -1 : 1)).toBeGreaterThan(0)
    expect(Math.abs(normal.dy)).toBeLessThan(0.001)
    const points = [...liquidDropOutline(drop.width, drop.height, dropCorner, dropSide, drop.dropOffset).matchAll(/(?:M|L) ([\d.-]+) ([\d.-]+)/g)]
    expect(points).toHaveLength(192)
    for (const point of points) {
      expect(Math.abs(liquidSurfaceSdf(Number(point[1]), Number(point[2]), drop))).toBeLessThan(0.02)
    }
  })
})
