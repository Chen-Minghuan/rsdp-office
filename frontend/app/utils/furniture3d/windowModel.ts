import * as THREE from 'three'
import type { WhiteModelWindow } from '~/utils/whiteModel'

/** 有窗框、窗扇、分格、玻璃、把手和窗台的立体窗，局部 X 沿墙、Y 竖直、Z 穿墙。 */
export function buildWindowModel(window: WhiteModelWindow): THREE.Group {
  const group = new THREE.Group()
  const { widthMm: width, heightMm: height } = window
  const frame = Math.min(55, width / 8, height / 8), depth = Math.min(95, window.wallThicknessMm)
  const metal = new THREE.MeshStandardMaterial({ color: 0x535c60, metalness: 0.7, roughness: 0.3 })
  const sash = new THREE.MeshStandardMaterial({ color: 0x899194, metalness: 0.6, roughness: 0.28 })
  const glass = new THREE.MeshPhysicalMaterial({ color: 0xd7e9ed, metalness: 0, roughness: 0.07, transmission: 0.4, transparent: true, opacity: 0.48, thickness: 6, ior: 1.5, depthWrite: false, side: THREE.DoubleSide })
  const stone = new THREE.MeshStandardMaterial({ color: 0xe8e5dc, roughness: 0.55 })
  const add = (w: number, h: number, d: number, x: number, y: number, z: number, material: THREE.Material, part: string) => {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material)
    mesh.position.set(x, y, z)
    mesh.castShadow = part !== 'glass'
    mesh.receiveShadow = part !== 'glass'
    mesh.userData.windowPart = part
    group.add(mesh)
    return mesh
  }
  for (const sign of [-1, 1]) {
    add(frame, height, depth, sign * (width - frame) / 2, height / 2, 0, metal, 'frame')
    add(width - 2 * frame, frame, depth, 0, sign < 0 ? frame / 2 : height - frame / 2, 0, metal, 'frame')
  }
  const panes = Math.max(1, Math.min(4, Math.ceil(width / 1400)))
  const clearWidth = width - 2 * frame, paneWidth = clearWidth / panes, sashWidth = Math.min(24, paneWidth / 8)
  for (let index = 0; index < panes; index++) {
    const center = -clearWidth / 2 + paneWidth * (index + 0.5), paneHeight = height - 2 * frame
    for (const sign of [-1, 1]) {
      add(sashWidth, paneHeight, 44, center + sign * (paneWidth - sashWidth) / 2, height / 2, 6, sash, 'sash')
      add(paneWidth - 2 * sashWidth, sashWidth, 44, center, sign < 0 ? frame + sashWidth / 2 : height - frame - sashWidth / 2, 6, sash, 'sash')
    }
    add(paneWidth - 2 * sashWidth, paneHeight - 2 * sashWidth, 6, center, height / 2, 6, glass, 'glass')
    if (paneWidth > 280) {
      const handleX = center + paneWidth / 2 - sashWidth / 2
      add(12, 110, 14, handleX, height * 0.47, depth / 2 + 8, metal, 'handle')
      add(12, 110, 14, handleX, height * 0.47, -depth / 2 - 8, metal, 'handle')
    }
  }
  add(width + 100, 35, window.wallThicknessMm + 90, 0, -17.5, 0, stone, 'sill')
  group.position.set((window.a[0] + window.b[0]) / 2, window.baseMm, -(window.a[1] + window.b[1]) / 2)
  group.rotation.y = Math.atan2(window.b[1] - window.a[1], window.b[0] - window.a[0])
  group.userData = { id: window.id, source: 'obstacle', label: `${window.label} · 窗高 ${height} / 窗台高 ${window.baseMm} mm（示意）`, windowModel: true }
  return group
}
