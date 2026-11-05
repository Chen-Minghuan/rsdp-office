import * as THREE from 'three'
import polygonClipping from 'polygon-clipping'
import type { WhiteModelWall } from '~/utils/whiteModel'

/** 墙体局部原点在墙边起点地面。布尔扣除同时支持落地门洞、窗孔与相交开口。 */
export function buildWallGeometry(wall: WhiteModelWall): THREE.BufferGeometry {
  const length = Math.hypot(wall.b[0] - wall.a[0], wall.b[1] - wall.a[1])
  if (!wall.openings?.length) {
    return new THREE.BoxGeometry(length, wall.heightMm, wall.thicknessMm).translate(length / 2, wall.heightMm / 2, 0)
  }
  const rectangle = (left: number, bottom: number, right: number, top: number): Array<[number, number]> => [
    [left, bottom], [right, bottom], [right, top], [left, top], [left, bottom],
  ]
  const polygons = polygonClipping.difference([rectangle(0, 0, length, wall.heightMm)],
    ...wall.openings.map(opening => [rectangle(opening.startMm, opening.bottomMm, opening.endMm, opening.topMm)]))
  const shapes = polygons.map(polygon => {
    const shape = new THREE.Shape(polygon[0]!.map(([x, y]) => new THREE.Vector2(x, y)))
    shape.holes = polygon.slice(1).map(ring => new THREE.Path(ring.map(([x, y]) => new THREE.Vector2(x, y))))
    return shape
  })
  return new THREE.ExtrudeGeometry(shapes, { depth: wall.thicknessMm, bevelEnabled: false }).translate(0, 0, -wall.thicknessMm / 2)
}
