/**
 * Liquid Glass 边缘折射：为导航/操作层生成一张静态位移贴图，交给 SVG feDisplacementMap。
 *
 * 圆角 SDF 来自 rdev/liquid-glass-react (MIT, Copyright 2025 Max Rovensky)。
 * 曲面高度 / Snell 折射曲线和法线高光改编自 leefanv/liqui-design
 * (MIT, Copyright (c) 2026 Fan Li), commit fae9f2de55d8a049d77bbaed80f46fa68ae92508。
 * 只生成边缘的光学贴图，不捕获 DOM、不逐帧绘制。来源与许可见 THIRD_PARTY_NOTICES.md。
 *
 * 只有 Chromium 内核能把 SVG 滤镜作用于 backdrop-filter 的合成结果；其他浏览器、减少透明度、
 * 强制颜色或用户关闭时回退到纯 CSS 玻璃（_glass.scss）。
 */

export type LiquidDropCorner = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right'
export type LiquidDropSide = 'top' | 'right' | 'bottom' | 'left'

export interface LiquidGlassMapOptions {
  width: number
  height: number
  radius: number
  /** 边缘折射带宽度（px） */
  bezel?: number
  /** 边缘最大采样偏移（px） */
  depth?: number
  /** 下拉浮层可选连体水滴轮廓；其他表面继续使用原有圆角矩形。 */
  shape?: 'rounded-rect' | 'droplet'
  dropCorner?: LiquidDropCorner
  dropSide?: LiquidDropSide
  /** 水滴中心沿所在边的位置（px）；省略时靠近指定角落。 */
  dropOffset?: number
}

export interface LiquidGlassMap {
  url: string
  width: number
  height: number
  /** feDisplacementMap scale */
  scale: number
  specular: string
  specularDark: string
}

const PROFILE_SIZE = 128
const maps = new Map<string, LiquidGlassMap>()
const clamp = (value: number) => Math.max(0, Math.min(1, value))

// A continuous glass shoulder: the slope gives the incident angle; Snell's law
// gives the sideways sample shift. The centre of the surface stays undistorted.
const refractionProfile = (() => {
  const result = new Float32Array(PROFILE_SIZE)
  const height = (t: number) => Math.pow(1 - Math.pow(1 - t, 4), 0.25)
  const epsilon = 1 / 1024
  let maximum = 0
  for (let i = 0; i < PROFILE_SIZE; i += 1) {
    const t = Math.max(i / (PROFILE_SIZE - 1), epsilon)
    const hi = Math.min(1, t + epsilon)
    const lo = Math.max(0, t - epsilon)
    const slope = ((height(hi) - height(lo)) / (hi - lo)) * 0.6
    const incident = Math.atan(Math.abs(slope))
    const bend = incident - Math.asin(Math.sin(incident) / 1.5)
    result[i] = height(t) * 0.6 * Math.tan(bend)
    maximum = Math.max(maximum, result[i])
  }
  for (let i = 0; i < PROFILE_SIZE; i += 1) result[i] /= maximum || 1
  return result
})()

/** Signed distance from point (x, y) to a rounded rectangle centred at the origin; negative inside. */
export function roundedRectSdf(x: number, y: number, halfWidth: number, halfHeight: number, radius: number): number {
  const qx = Math.abs(x) - halfWidth + radius
  const qy = Math.abs(y) - halfHeight + radius
  return Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - radius
}

type SurfaceGeometry = Pick<LiquidGlassMapOptions, 'width' | 'height' | 'radius' | 'shape' | 'dropCorner' | 'dropSide' | 'dropOffset'>

function resolveDropOffset(length: number, side: LiquidDropSide, corner: LiquidDropCorner, offset?: number): number {
  const end = side === 'left' || side === 'right' ? corner.startsWith('bottom') : corner.endsWith('right')
  return Math.max(24, Math.min(length - 24, offset ?? (end ? length - 24 : 24)))
}

/** Canonical top-facing surface; side-facing surfaces rotate the same geometry. */
function dropletSdf(x: number, y: number, width: number, height: number, dropX: number): number {
  const top = 30
  const bottom = height - 4
  const radius = Math.min(26, (bottom - top) / 2, width / 2 - 5)
  const body = roundedRectSdf(x - width / 2, y - (top + bottom) / 2, width / 2 - 5, (bottom - top) / 2, radius)
  const drop = Math.hypot(x - dropX, y - 22) - 18
  const blend = Math.max(16 - Math.abs(body - drop), 0) / 16
  return Math.min(body, drop) - blend * blend * 4
}

/** One continuous surface, including the concave shoulder between the droplet and the menu. */
export function liquidSurfaceSdf(x: number, y: number, options: SurfaceGeometry): number {
  const { width, height } = options
  if (options.shape === 'droplet' && width >= 100 && height >= 80) {
    const corner = options.dropCorner ?? 'top-left'
    const side = options.dropSide ?? (corner.startsWith('bottom') ? 'bottom' : 'top')
    const horizontal = side === 'left' || side === 'right'
    const length = horizontal ? height : width
    const depth = horizontal ? width : height
    const along = horizontal ? y : x
    let across = horizontal ? x : y
    if (side === 'bottom' || side === 'right') across = depth - across
    return dropletSdf(along, across, length, depth, resolveDropOffset(length, side, corner, options.dropOffset))
  }
  return roundedRectSdf(x - width / 2, y - height / 2, width / 2, height / 2, Math.min(options.radius, width / 2, height / 2))
}

/** The same signed-distance field supplies both the visible edge and its optical normals. */
export function liquidDropOutline(
  width: number, height: number, dropCorner: LiquidDropCorner = 'top-left',
  dropSide: LiquidDropSide = dropCorner.startsWith('bottom') ? 'bottom' : 'top', dropOffset?: number,
): string {
  if (![width, height, dropOffset ?? 0].every(Number.isFinite) || width < 100 || height < 80) return ''
  const horizontal = dropSide === 'left' || dropSide === 'right'
  const length = horizontal ? height : width
  const depth = horizontal ? width : height
  const offset = resolveDropOffset(length, dropSide, dropCorner, dropOffset)
  // This point lies in the overlap of both convex lobes, so every ray crosses the rim once.
  const origin = { x: offset, y: 35 }
  const points = Array.from({ length: 192 }, (_, index) => {
    const angle = index / 192 * Math.PI * 2
    const dx = Math.cos(angle)
    const dy = Math.sin(angle)
    let inside = 0
    let outside = width + height
    for (let step = 0; step < 20; step += 1) {
      const distance = (inside + outside) / 2
      if (dropletSdf(origin.x + dx * distance, origin.y + dy * distance, length, depth, offset) <= 0) inside = distance
      else outside = distance
    }
    const along = origin.x + dx * inside
    let across = origin.y + dy * inside
    if (dropSide === 'right' || dropSide === 'bottom') across = depth - across
    return `${(horizontal ? across : along).toFixed(2)} ${(horizontal ? along : across).toFixed(2)}`
  })
  return `M ${points.join(' L ')} Z`
}

/**
 * Inward displacement (in px) at a point: zero in the interior, growing smoothly towards the edge so the
 * backdrop near the rim is sampled from further inside, like light bending through a thick glass edge.
 */
export function edgeDisplacement(
  x: number,
  y: number,
  options: Required<Pick<LiquidGlassMapOptions, 'width' | 'height' | 'radius' | 'bezel' | 'depth'>> & Pick<LiquidGlassMapOptions, 'shape' | 'dropCorner' | 'dropSide' | 'dropOffset'>,
): { dx: number; dy: number } {
  const distance = -liquidSurfaceSdf(x, y, options)
  if (distance >= options.bezel || distance < 0) return { dx: 0, dy: 0 }

  const strength = refractionProfile[Math.min(PROFILE_SIZE - 1, Math.round((distance / options.bezel) * (PROFILE_SIZE - 1)))]
  const epsilon = 0.5
  const gx = liquidSurfaceSdf(x + epsilon, y, options) - liquidSurfaceSdf(x - epsilon, y, options)
  const gy = liquidSurfaceSdf(x, y + epsilon, options) - liquidSurfaceSdf(x, y - epsilon, options)
  const length = Math.hypot(gx, gy) || 1
  // SDF gradient points outward; sample inward.
  return {
    dx: (-gx / length) * strength * options.depth,
    dy: (-gy / length) * strength * options.depth,
  }
}

export function createLiquidGlassMap(options: LiquidGlassMapOptions): LiquidGlassMap | null {
  if (![options.width, options.height, options.radius, options.bezel ?? 24, options.depth ?? 32, options.dropOffset ?? 0].every(Number.isFinite)) return null
  const width = Math.round(options.width)
  const height = Math.round(options.height)
  if (width <= 0 || height <= 0) return null
  if (typeof document === 'undefined') return null

  const resolved = {
    width,
    height,
    radius: Math.min(Math.max(0, options.radius), width / 2, height / 2),
    bezel: Math.min(Math.max(2, options.bezel ?? 24), width / 2, height / 2),
    depth: Math.max(0, options.depth ?? 32),
    shape: options.shape ?? 'rounded-rect',
    dropCorner: options.dropCorner ?? 'top-left',
    dropSide: options.dropSide ?? (options.dropCorner?.startsWith('bottom') ? 'bottom' : 'top'),
    dropOffset: options.dropOffset,
  }
  const key = `${width}:${height}:${resolved.radius}:${resolved.bezel}:${resolved.depth}:${resolved.shape}:${resolved.dropCorner}:${resolved.dropSide}:${resolved.dropOffset ?? ''}`
  const cached = maps.get(key)
  if (cached) {
    maps.delete(key)
    maps.set(key, cached)
    return cached
  }
  // Bound both work and retained image memory. Large drawers and 4K toolbars
  // use a scaled field rather than silently losing refraction above 1600px.
  const resolution = Math.min(1, 1600 / Math.max(width, height), Math.sqrt(450000 / (width * height)))
  const mapWidth = Math.max(1, Math.round(width * resolution))
  const mapHeight = Math.max(1, Math.round(height * resolution))
  const canvas = document.createElement('canvas')
  canvas.width = mapWidth
  canvas.height = mapHeight
  const context = canvas.getContext('2d')
  if (!context) return null
  const image = context.createImageData(mapWidth, mapHeight)
  const data = image.data
  const light = context.createImageData(mapWidth, mapHeight)
  const dark = context.createImageData(mapWidth, mapHeight)
  const reach = 4.5 + resolved.bezel * 0.15
  for (let y = 0; y < mapHeight; y += 1) {
    for (let x = 0; x < mapWidth; x += 1) {
      const px = ((x + 0.5) / mapWidth) * width
      const py = ((y + 0.5) / mapHeight) * height
      const offset = (y * mapWidth + x) * 4
      const { dx, dy } = edgeDisplacement(px, py, resolved)
      data[offset] = Math.round(128 + (dx / (resolved.depth || 1)) * 127)
      data[offset + 1] = Math.round(128 + (dy / (resolved.depth || 1)) * 127)
      data[offset + 2] = 128
      data[offset + 3] = 255

      const distance = -liquidSurfaceSdf(px, py, resolved)
      if (distance < -1 || distance > reach) continue
      const gx = liquidSurfaceSdf(px + 0.5, py, resolved) - liquidSurfaceSdf(px - 0.5, py, resolved)
      const gy = liquidSurfaceSdf(px, py + 0.5, resolved) - liquidSurfaceSdf(px, py - 0.5, resolved)
      const length = Math.hypot(gx, gy) || 1
      const vertical = Math.abs(gy / length)
      const stroke = clamp(1.4 - distance) * clamp(0.5 + distance)
      const directional = Math.pow(clamp((vertical - Math.cos(1.08)) / (1 - Math.cos(1.08))), 1.25)
      const highlight = directional * (stroke + 0.16 * Math.pow(1 - clamp(distance / reach), 2))
      const shade = (1 - vertical) * stroke * 0.24
      for (const [output, brightness, strength] of [[light.data, 255, 0.95], [dark.data, 225, 0.65]] as const) {
        const alpha = clamp(highlight * strength + shade * (1 - highlight))
        const value = alpha ? Math.round((brightness * highlight * strength) / alpha) : 0
        output[offset] = output[offset + 1] = output[offset + 2] = value
        output[offset + 3] = Math.round(alpha * 255)
      }
    }
  }
  context.putImageData(image, 0, 0)
  const url = canvas.toDataURL('image/png')
  context.putImageData(light, 0, 0)
  const specular = canvas.toDataURL('image/png')
  context.putImageData(dark, 0, 0)
  const result = {
    url,
    width,
    height,
    scale: resolved.depth * 2,
    specular,
    specularDark: canvas.toDataURL('image/png'),
  }
  maps.set(key, result)
  if (maps.size > 16) maps.delete(maps.keys().next().value!)
  return result
}

let cachedSupport: boolean | null = null

/** SVG filters on a backdrop-filtered layer only render as refraction in Chromium-based browsers. */
export function supportsLiquidGlassRefraction(): boolean {
  if (cachedSupport !== null) return cachedSupport
  if (typeof navigator === 'undefined' || typeof CSS === 'undefined' || typeof CSS.supports !== 'function') {
    cachedSupport = false
    return cachedSupport
  }
  const userAgent = navigator.userAgent
  const chromium = /(Chrome|Chromium|Edg)\/\d+/.test(userAgent)
  const excluded = /(Firefox|FxiOS|CriOS|EdgiOS|OPiOS)\//.test(userAgent) || /iPhone|iPad|iPod/.test(userAgent)
  cachedSupport = chromium && !excluded && CSS.supports('backdrop-filter', 'blur(1px)')
  return cachedSupport
}

export function prefersReducedTransparency(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && (window.matchMedia('(prefers-reduced-transparency: reduce)').matches
      || window.matchMedia('(forced-colors: active)').matches)
}
