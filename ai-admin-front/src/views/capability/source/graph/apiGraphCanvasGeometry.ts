import type { ApiGraphNode } from '@/api/apiGraph'

export type Point = { x: number, y: number }

/** 入参/出参连线先水平伸出卡片边缘，避免竖段被卡片遮挡 */
const EDGE_FIELD_CARD_STUB = 28

/** 去掉 path 开头的 M x y，便于与前置水平段拼接 */
function stripLeadingSvgMove(d: string): string {
  const s = d.trimStart()
  const m = s.match(/^M\s*([\d.-]+)\s*([\d.-]+)\s+(.*)/)
  return m?.[3]?.trimStart() ?? s
}

/**
 * 正交路径上 90° 圆角对应的 SVG 小弧 sweep（large-arc=0）。
 * 用拐角前一点 → 拐角 → 拐角后一点的走向算叉积（屏幕坐标 y 向下），
 * 与手写 hDir*vDir 相比，在卡片拖动、起终点相对位置变化时更稳定。
 */
function svgSweepForCorner(prev: Point, corner: Point, next: Point): 0 | 1 {
  const vInX = corner.x - prev.x
  const vInY = corner.y - prev.y
  const vOutX = next.x - corner.x
  const vOutY = next.y - corner.y
  const cross = vInX * vOutY - vInY * vOutX
  /* SVG y 向下：小弧 sweep 与「数学系 y 向上」叉积符号相反，用 cross>0 */
  return cross > 0 ? 1 : 0
}

/** 入参锚点在卡片左侧：先向左伸出；出参在右侧：先向右伸出 */
function fieldEdgeHorizStubOut(node: ApiGraphNode, anchor: Point): Point {
  if (node.kind === 'FIELD_IN') return { x: anchor.x - EDGE_FIELD_CARD_STUB, y: anchor.y }
  if (node.kind === 'FIELD_OUT') return { x: anchor.x + EDGE_FIELD_CARD_STUB, y: anchor.y }
  return { ...anchor }
}

/** 连到入参/出参锚点前，先水平接到「卡片外」再竖向进入锚点 */
function fieldEdgeHorizStubIn(node: ApiGraphNode, anchor: Point): Point {
  if (node.kind === 'FIELD_IN') return { x: anchor.x - EDGE_FIELD_CARD_STUB, y: anchor.y }
  if (node.kind === 'FIELD_OUT') return { x: anchor.x + EDGE_FIELD_CARD_STUB, y: anchor.y }
  return { ...anchor }
}

export function composeGraphEdgePath(
  source: ApiGraphNode,
  target: ApiGraphNode,
  anchorSource: Point,
  anchorTarget: Point,
  orthoOpts?: { radius?: number; bendT?: number },
): { path: string; labelX: number; labelY: number } {
  const srcRun = fieldEdgeHorizStubOut(source, anchorSource)
  const tgtRun = fieldEdgeHorizStubIn(target, anchorTarget)
  const opt = { radius: 14, bendT: 0.5, ...orthoOpts }

  const hasSrcStub = anchorSource.x !== srcRun.x || anchorSource.y !== srcRun.y
  const hasTgtStub = anchorTarget.x !== tgtRun.x || anchorTarget.y !== tgtRun.y

  const core = buildOrthogonalRoundedPath(srcRun.x, srcRun.y, tgtRun.x, tgtRun.y, opt)
  const inner = stripLeadingSvgMove(core.path)

  /** 水平 stub 末端与「先竖后横」首段竖线衔接处加小圆弧（须接到 core 首条竖线的终点 y1，避免破坏后续 A） */
  const STUB_JOIN_R = 10
  let path: string
  if (hasSrcStub && core.verticalFirst) {
    const firstLeg = core.path.match(/^M\s*([\d.-]+)\s*([\d.-]+)\s+L\s*([\d.-]+)\s*([\d.-]+)\s+/)
    const y1 = firstLeg ? Number(firstLeg[4]) : NaN
    const xLeg = firstLeg ? Number(firstLeg[3]) : NaN
    if (
      firstLeg
      && Number.isFinite(y1)
      && Number.isFinite(xLeg)
      && Math.abs(xLeg - srcRun.x) < 0.5
      && Math.abs(y1 - srcRun.y) > 0.5
    ) {
      const hStub = Math.sign(srcRun.x - anchorSource.x) || 1
      const vGo = Math.sign(y1 - srcRun.y) || 1
      const vertLeg = Math.abs(y1 - srcRun.y)
      const rr = Math.min(
        STUB_JOIN_R,
        EDGE_FIELD_CARD_STUB - 2,
        vertLeg * 0.38,
      )
      /** 90° 小弧的竖向抬升必须 ≤ 竖边长度，否则 A 会走长弧/畸形弧 */
      const r = Math.min(Math.max(4, rr), Math.max(vertLeg, 1e-6))
      const x0 = srcRun.x - hStub * r
      const arcEndY = srcRun.y + vGo * r
      const sweep = svgSweepForCorner(
        { x: x0, y: srcRun.y },
        { x: srcRun.x, y: srcRun.y },
        { x: srcRun.x, y: arcEndY },
      )
      const head = `M ${anchorSource.x} ${anchorSource.y} L ${x0} ${srcRun.y} A ${r} ${r} 0 0 ${sweep} ${srcRun.x} ${arcEndY} L ${srcRun.x} ${y1}`
      const rest = core.path.replace(/^M\s*[\d.-]+\s*[\d.-]+\s+L\s*[\d.-]+\s*[\d.-]+\s*/, '')
      path = `${head} ${rest}`.replace(/\s+/g, ' ').trim()
    } else {
      path = `M ${anchorSource.x} ${anchorSource.y} L ${srcRun.x} ${srcRun.y} ${inner}`.replace(/\s+/g, ' ').trim()
    }
  } else if (hasSrcStub) {
    path = `M ${anchorSource.x} ${anchorSource.y} L ${srcRun.x} ${srcRun.y} ${inner}`.replace(/\s+/g, ' ').trim()
  } else {
    path = core.path.trim()
  }

  if (hasTgtStub && core.verticalFirst) {
    const arcEnd = core.path.match(
      /A\s+[\d.-]+\s+[\d.-]+\s+0\s+0\s+[01]\s+([\d.-]+)\s+([\d.-]+)\s+L\s+([\d.-]+)\s+([\d.-]+)\s*$/,
    )
    const ax = arcEnd ? Number(arcEnd[1]) : NaN
    const ay4 = arcEnd ? Number(arcEnd[2]) : NaN
    const lx = arcEnd ? Number(arcEnd[3]) : NaN
    const ly = arcEnd ? Number(arcEnd[4]) : NaN
    if (
      arcEnd
      && Number.isFinite(ax)
      && Number.isFinite(ay4)
      && Number.isFinite(lx)
      && Number.isFinite(ly)
      && Math.abs(ax - lx) < 0.5
      && Math.abs(lx - tgtRun.x) < 0.5
      && Math.abs(ly - tgtRun.y) < 0.5
      && Math.abs(anchorTarget.y - tgtRun.y) < 0.5
    ) {
      const y4 = ay4
      const hGo = Math.sign(anchorTarget.x - tgtRun.x) || 1
      const vIn = Math.sign(tgtRun.y - y4) || 1
      const vertLeg = Math.abs(tgtRun.y - y4)
      const horizLeg = Math.abs(anchorTarget.x - tgtRun.x)
      const rr = Math.min(
        STUB_JOIN_R,
        horizLeg * 0.42,
        vertLeg * 0.38,
      )
      /** 90° 小弧：半径不能超过参与圆角的两条直角边长度 */
      const r = Math.min(Math.max(4, rr), Math.max(vertLeg, 1e-6), Math.max(horizLeg, 1e-6))
      const sweep = svgSweepForCorner(
        { x: tgtRun.x, y: y4 },
        { x: tgtRun.x, y: tgtRun.y },
        { x: anchorTarget.x, y: anchorTarget.y },
      )
      const y0 = tgtRun.y - vIn * r
      const xArc = tgtRun.x + hGo * r
      const tail = ` L ${tgtRun.x} ${tgtRun.y}`
      const pos = path.lastIndexOf(tail)
      if (pos >= 0) {
        const fillet = ` L ${tgtRun.x} ${y0} A ${r} ${r} 0 0 ${sweep} ${xArc} ${tgtRun.y} L ${anchorTarget.x} ${anchorTarget.y}`
        path = `${path.slice(0, pos)}${fillet}`.replace(/\s+/g, ' ').trim()
      } else {
        path = `${path} L ${anchorTarget.x} ${anchorTarget.y}`.replace(/\s+/g, ' ').trim()
      }
    } else {
      path = `${path} L ${anchorTarget.x} ${anchorTarget.y}`.replace(/\s+/g, ' ').trim()
    }
  } else if (hasTgtStub) {
    path = `${path} L ${anchorTarget.x} ${anchorTarget.y}`.replace(/\s+/g, ' ').trim()
  }

  return { path, labelX: core.labelX, labelY: core.labelY }
}

/**
 * 正交折线 + 90° 圆角（SVG 圆弧），用于接口图谱连线。
 * 竖向跨度明显大于横向时用「先竖后横」，否则「先横后竖」。
 */
export function buildOrthogonalRoundedPath(
  sx: number,
  sy: number,
  tx: number,
  ty: number,
  opts?: { radius?: number; bendT?: number },
): { path: string; labelX: number; labelY: number; verticalFirst: boolean } {
  const radius = opts?.radius ?? 12
  const bendT = opts?.bendT ?? 0.5
  const dx = tx - sx
  const dy = ty - sy
  const adx = Math.abs(dx)
  const ady = Math.abs(dy)

  if (adx < 1 && ady < 1) {
    return { path: `M ${sx} ${sy} L ${tx} ${ty}`, labelX: (sx + tx) / 2, labelY: (sy + ty) / 2 - 8, verticalFirst: false }
  }
  if (ady < 2) {
    return { path: `M ${sx} ${sy} L ${tx} ${ty}`, labelX: (sx + tx) / 2, labelY: sy - 10, verticalFirst: false }
  }
  if (adx < 2) {
    return { path: `M ${sx} ${sy} L ${tx} ${ty}`, labelX: sx - 10, labelY: (sy + ty) / 2, verticalFirst: false }
  }

  const verticalFirst = ady > adx * 1.15
  const hDir = dx >= 0 ? 1 : -1
  const vDir = dy >= 0 ? 1 : -1

  if (!verticalFirst) {
    const mx = sx + dx * bendT
    const rad = Math.max(
      3,
      Math.min(
        radius,
        Math.abs(mx - sx) * 0.42,
        Math.abs(tx - mx) * 0.42,
        ady * 0.48 - 1,
      ),
    )
    const x1 = mx - hDir * rad
    const y2 = sy + vDir * rad
    const y3 = ty - vDir * rad
    const x4 = mx + hDir * rad
    const sweep1 = svgSweepForCorner({ x: x1, y: sy }, { x: mx, y: sy }, { x: mx, y: y3 })
    const sweep2 = svgSweepForCorner({ x: mx, y: y3 }, { x: mx, y: ty }, { x: x4, y: ty })
    const path = [
      `M ${sx} ${sy}`,
      `L ${x1} ${sy}`,
      `A ${rad} ${rad} 0 0 ${sweep1} ${mx} ${y2}`,
      `L ${mx} ${y3}`,
      `A ${rad} ${rad} 0 0 ${sweep2} ${x4} ${ty}`,
      `L ${tx} ${ty}`,
    ].join(' ')
    return { path, labelX: mx, labelY: (y2 + y3) / 2 - 8, verticalFirst: false }
  }

  const my = sy + dy * bendT
  const rad = Math.max(
    3,
    Math.min(
      radius,
      Math.abs(my - sy) * 0.42,
      Math.abs(ty - my) * 0.42,
      adx * 0.48 - 1,
    ),
  )
  const y1 = my - vDir * rad
  const x2 = sx + hDir * rad
  const x3 = tx - hDir * rad
  const y4 = my + vDir * rad
  const sweep1 = svgSweepForCorner({ x: sx, y: y1 }, { x: sx, y: my }, { x: x3, y: my })
  const sweep2 = svgSweepForCorner({ x: x3, y: my }, { x: tx, y: my }, { x: tx, y: y4 })
  const path = [
    `M ${sx} ${sy}`,
    `L ${sx} ${y1}`,
    `A ${rad} ${rad} 0 0 ${sweep1} ${x2} ${my}`,
    `L ${x3} ${my}`,
    `A ${rad} ${rad} 0 0 ${sweep2} ${tx} ${y4}`,
    `L ${tx} ${ty}`,
  ].join(' ')
  return { path, labelX: (x2 + x3) / 2, labelY: my - 8, verticalFirst: true }
}
