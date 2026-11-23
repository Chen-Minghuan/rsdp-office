export const ROUND_RING_TABLE = 'table-conference-ring'
export const RACETRACK_TABLE = 'table-conference-racetrack'

export function meetingRingTable(templateId: string, width: number, depth: number) {
  if (![width, depth].every(value => Number.isFinite(value) && value >= 10)) return null
  if (templateId === ROUND_RING_TABLE) {
    const count = Math.min(48, Math.max(4, Math.round(Math.sqrt(width * depth) / 500)))
    return { radiusX: width / 2, radiusY: depth / 2, thickness: depth / 5, straightRun: 0, straightCount: 0, arcCount: count }
  }
  if (templateId !== RACETRACK_TABLE || width <= depth) return null
  const straightRun = width - depth
  return {
    radiusX: depth / 2, radiusY: depth / 2, thickness: depth / 4, straightRun,
    straightCount: Math.min(100, Math.max(1, Math.round(straightRun / 1400))), arcCount: 4,
  }
}

export function meetingRingDescription(templateId: string, width: number, depth: number) {
  const ring = meetingRingTable(templateId, width, depth)
  if (!ring) return ''
  const format = (mm: number) => Number(mm.toFixed(1))
  if (!ring.straightCount) {
    const angle = Math.PI / ring.arcCount
    const size = width === depth
      ? `，约 ${format(width * Math.sin(angle))} × ${format(ring.radiusY - (ring.radiusY - ring.thickness) * Math.cos(angle))} mm`
      : ''
    return `扇形桌段 × ${ring.arcCount}（每段 ${format(360 / ring.arcCount)}°${size}）`
  }
  return `直段 × ${2 * ring.straightCount}（${format(ring.straightRun / ring.straightCount)} × ${format(ring.thickness)} mm）＋ 90° 端弧段 × 4（${format(ring.radiusX)} × ${format(ring.radiusY)} mm）`
}

export function meetingRingLinework(templateId: string, width: number, depth: number) {
  const ring = meetingRingTable(templateId, width, depth)
  if (!ring) return null
  const { radiusX: rx, radiusY: ry, thickness, straightRun, straightCount, arcCount } = ring
  const innerX = straightRun ? rx - thickness : rx * 0.6
  const innerY = ry - thickness
  const left = rx, right = rx + straightRun
  const outline = (x: number, y: number) =>
    `M ${left} ${ry - y} H ${right} A ${x} ${y} 0 0 1 ${right} ${ry + y} H ${left} A ${x} ${y} 0 0 1 ${left} ${ry - y} Z`
  const seams = straightRun ? [
    ...Array.from({ length: straightCount + 1 }, (_, index) => {
      const x = left + straightRun * index / straightCount
      return `M ${x} 0 v ${thickness} M ${x} ${depth - thickness} v ${thickness}`
    }),
    `M 0 ${ry} h ${thickness} M ${width - thickness} ${ry} h ${thickness}`,
  ] : Array.from({ length: arcCount }, (_, index) => {
    const angle = (index + 0.5) * Math.PI * 2 / arcCount
    return `M ${left + innerX * Math.cos(angle)} ${ry + innerY * Math.sin(angle)} L ${left + rx * Math.cos(angle)} ${ry + ry * Math.sin(angle)}`
  })
  return { body: outline(rx, ry) + ' ' + outline(innerX, innerY), seams: seams.join(' ') }
}
