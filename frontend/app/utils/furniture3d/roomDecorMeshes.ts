import * as THREE from 'three'
import { roomDecorThemes, type RoomDecorPlan } from './roomDecorPlan'

/** World-scale finishes keep the same pattern size as the camera moves or zooms. */
function floorTexture(plan: RoomDecorPlan) {
  const theme = roomDecorThemes[plan.theme]
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 512
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  const carpet = plan.theme === 'slate'
  ctx.fillStyle = theme.floor
  ctx.fillRect(0, 0, 512, 512)

  let seed = 23
  const random = () => {
    seed = (seed * 16807) % 2147483647
    return seed / 2147483647
  }
  if (carpet) {
    for (let i = 0; i < 17000; i++) {
      ctx.fillStyle = i % 2 ? 'rgba(32,42,48,.18)' : 'rgba(255,255,255,.19)'
      const x = random() * 512, y = random() * 512
      ctx.fillRect(x, y, (x < 256) === (y < 256) ? 1 : 4, (x < 256) === (y < 256) ? 4 : 1)
    }
    ctx.strokeStyle = theme.seam
    ctx.lineWidth = 1
    ctx.strokeRect(0.5, 0.5, 255, 255)
    ctx.strokeRect(256.5, 256.5, 255, 255)
  } else {
    for (let row = 0; row < 8; row++) {
      const y = row * 64
      ctx.fillStyle = row % 3 === 0 ? 'rgba(109,66,38,.13)' : 'rgba(255,237,200,.10)'
      ctx.fillRect(0, y, 512, 64)
      ctx.fillStyle = theme.seam
      ctx.fillRect(0, y, 512, 1.1)
      ctx.fillRect((row % 3) * 170, y, 1.4, 64)
      for (let strand = 0; strand < 38; strand++) {
        const grainY = y + random() * 64
        ctx.strokeStyle = strand % 3 === 0 ? 'rgba(107,65,40,.17)' : 'rgba(250,226,182,.21)'
        ctx.beginPath()
        ctx.moveTo(0, grainY)
        ctx.bezierCurveTo(165, grainY + random() * 6, 330, grainY - random() * 6, 512, grainY)
        ctx.stroke()
      }
    }
  }
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping
  texture.repeat.setScalar(1 / (carpet ? 1000 : 1600))
  return texture
}

function box(group: THREE.Group, material: THREE.Material, size: [number, number, number], position: [number, number, number]) {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), material)
  mesh.position.set(...position)
  group.add(mesh)
  return mesh
}

export function buildRoomDecorMeshes(plan: RoomDecorPlan) {
  const theme = roomDecorThemes[plan.theme]
  const texture = floorTexture(plan)
  const floor = new THREE.MeshStandardMaterial({ color: texture ? 0xffffff : theme.floor, roughness: plan.theme === 'slate' ? 1 : 0.76, map: texture })
  const floorEdge = new THREE.MeshStandardMaterial({ color: theme.seam, roughness: 0.9 })
  const wall = new THREE.MeshStandardMaterial({ color: theme.wall, roughness: 0.94 })
  const decor = new THREE.Group()
  const fixtures = new THREE.Group()
  decor.add(fixtures)
  const panel = plan.panel
  if (panel) {
    const group = new THREE.Group()
    group.position.set(panel.center[0], panel.midHeight, -panel.center[1])
    group.rotation.y = panel.rotationRad
    group.userData.decorWallIndex = panel.wallIndex
    box(group, new THREE.MeshStandardMaterial({ color: theme.accent, roughness: 0.95 }), [panel.width, panel.height, 42], [0, 0, 0])
    const ribs = new THREE.MeshStandardMaterial({ color: theme.panel, roughness: 0.92 })
    const ribCount = Math.floor(panel.width / (plan.theme === 'slate' ? 120 : 72))
    const spacing = panel.width / (ribCount + 1)
    for (let i = 1; i <= ribCount; i++) {
      const x = i * spacing - panel.width / 2
      // On both faces so the attachment works for either polygon winding.
      for (const side of [-1, 1]) box(group, ribs, [spacing * 0.48, panel.height - 54, 11], [x, 0, side * 24])
    }
    decor.add(group)
  }
  if (plan.plant) {
    const plant = new THREE.Group()
    plant.position.set(plan.plant[0], 0, -plan.plant[1])
    const pot = new THREE.Mesh(new THREE.CylinderGeometry(130, 101, 245, 24),
      new THREE.MeshStandardMaterial({ color: theme.pot, roughness: 0.85 }))
    pot.position.y = 122.5
    pot.castShadow = true
    plant.add(pot)
    const canopy = new THREE.MeshStandardMaterial({ color: 0x526a43, roughness: 0.86 })
    for (let i = 0; i < 11; i++) {
      const a = i * 2.4
      const leaf = new THREE.Mesh(new THREE.SphereGeometry(1, 10, 8), canopy)
      leaf.position.set(Math.cos(a) * 77, 260 + i * 37, Math.sin(a) * 77)
      leaf.scale.set(82, 52, 60)
      leaf.rotation.z = Math.cos(a) * 0.55
      leaf.castShadow = true
      plant.add(leaf)
    }
    decor.add(plant)
  }
  if (plan.lights.length) {
    const frame = new THREE.MeshStandardMaterial({ color: '#333736', roughness: 0.6 })
    const diffuser = new THREE.MeshBasicMaterial({ color: theme.light })
    for (const light of plan.lights) {
      const group = new THREE.Group()
      group.position.set(light.center[0], light.height, -light.center[1])
      group.rotation.y = light.rotationRad
      box(group, frame, [light.length, 45, 70], [0, 0, 0])
      box(group, diffuser, [light.length - 32, 3, 52], [0, -23, 0])
      for (const end of [-0.34, 0.34]) box(group, frame, [6, 165, 6], [light.length * end, 103, 0])
      fixtures.add(group)
    }
  }
  return { decor, fixtures, floor, floorEdge, wall, texture }
}
