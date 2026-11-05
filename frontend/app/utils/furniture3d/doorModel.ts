import * as THREE from 'three'
import type { WhiteModelDoor } from '~/utils/whiteModel'

/** 门框落地、门扇沿标注开启到 90°。局部 X 从铰链沿关闭门扇，Z 为穿墙方向。 */
export function buildDoorModel(door: WhiteModelDoor): THREE.Group {
  const group = new THREE.Group()
  const { widthMm: width, heightMm: height, opensTo } = door
  const frame = Math.min(55, width / 8), depth = door.wallThicknessMm + 24
  const trim = new THREE.MeshStandardMaterial({ color: 0xe5dfd3, roughness: 0.6 })
  const wood = new THREE.MeshStandardMaterial({ color: 0xbb8d5c, roughness: 0.48 })
  const panel = new THREE.MeshStandardMaterial({ color: 0xcda77b, roughness: 0.55 })
  const metal = new THREE.MeshStandardMaterial({ color: 0x8d9498, metalness: 0.85, roughness: 0.25 })
  const add = (parent: THREE.Group, w: number, h: number, d: number, x: number, y: number, z: number, material: THREE.Material, part: string) => {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material)
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    mesh.userData.doorPart = part
    parent.add(mesh)
    return mesh
  }
  for (const x of [frame / 2, width - frame / 2]) add(group, frame, height, depth, x, height / 2, 0, trim, 'frame')
  add(group, width - 2 * frame, frame, depth, width / 2, height - frame / 2, 0, trim, 'frame')
  // 门套压边在墙两面，底部不加横框，门洞直接通到地面。
  for (const z of [-depth / 2, depth / 2]) {
    for (const x of [frame / 2, width - frame / 2]) add(group, frame + 26, height + 13, 12, x, (height + 13) / 2, z, trim, 'trim')
    add(group, width + 26, frame + 13, 12, width / 2, height - frame / 2, z, trim, 'trim')
  }
  const leaf = new THREE.Group(), leafWidth = width - 2 * frame - 6, leafHeight = height - frame - 12, thickness = 42
  leaf.position.set(frame + 3, 6, opensTo * (thickness / 2 + 2))
  leaf.rotation.y = -opensTo * Math.PI / 2
  leaf.userData.doorPart = 'leaf'
  group.add(leaf)
  add(leaf, leafWidth, leafHeight, thickness, leafWidth / 2, leafHeight / 2, 0, wood, 'slab')
  for (const sign of [-1, 1]) {
    // 上下装饰面板与双侧锁具，让正反面均可辨识。
    for (const [y, h] of [[leafHeight * 0.29, leafHeight * 0.4], [leafHeight * 0.76, leafHeight * 0.36]]) {
      add(leaf, leafWidth * 0.76, h!, 5, leafWidth / 2, y!, sign * (thickness / 2 + 1), panel, 'panel')
    }
    const handleX = leafWidth * 0.88, handleY = Math.min(1000, leafHeight * 0.52)
    add(leaf, 30, 150, 8, handleX, handleY, sign * (thickness / 2 + 6), metal, 'lock')
    add(leaf, 16, 16, 38, handleX, handleY + 28, sign * (thickness / 2 + 20), metal, 'handle')
    add(leaf, Math.min(110, leafWidth * 0.2), 16, 16, handleX - Math.min(45, leafWidth * 0.08), handleY + 28, sign * (thickness / 2 + 39), metal, 'handle')
  }
  for (const fraction of [0.15, 0.5, 0.85]) {
    const hinge = new THREE.Mesh(new THREE.CylinderGeometry(9, 9, 100, 12), metal)
    hinge.position.set(frame + 3, height * fraction, opensTo * (thickness / 2 + 2))
    hinge.castShadow = true
    hinge.userData.doorPart = 'hinge'
    group.add(hinge)
  }
  group.position.set(door.a[0], 0, -door.a[1])
  group.rotation.y = Math.atan2(door.b[1] - door.a[1], door.b[0] - door.a[0])
  group.userData = { id: door.id, source: 'obstacle', label: `${door.label} · 高 ${height} mm（示意）`, doorModel: true }
  return group
}
