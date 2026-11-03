<script setup lang="ts">
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import type { FurnitureObject } from '~/types/office'
import { buildWhiteModelScene, facilityKindLabel, furnitureHeightMm, obstacleKindLabel, WHITE_MODEL_FLOOR_THICKNESS_MM, type WhiteModelScene } from '~/utils/whiteModel'
import { furnitureCatalogTemplates } from '~/utils/manualFurniture'
import type { Product } from '~/types/office'

const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ (event: 'ai-render'): void }>()

const store = useOfficeStore()
const api = useOfficeApi()

const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const furniture = computed(() => store.objects.filter(item => item.kind === 'furniture' && item.zoneId === store.designZoneId) as FurnitureObject[])
const ready = computed(() => !!zone.value && zone.value.reviewUnit === 'mm')
const sceneData = computed<WhiteModelScene | null>(() => zone.value && ready.value ? buildWhiteModelScene(zone.value, furniture.value) : null)
const sceneKey = computed(() => JSON.stringify(sceneData.value))

const host = ref<HTMLDivElement | null>(null)
const initError = ref('')
const ready3d = ref(false)
const selectedId = ref('')
const hoverLabel = ref('')
const products = ref<Product[]>([])
const productsError = ref('')
const notice = ref('')

const selectedItem = computed(() => furniture.value.find(item => item.id === selectedId.value) || null)
const selectedProduct = computed(() => products.value.find(item => item.id === selectedItem.value?.productId) || null)

const C = { x: (x: number) => x, z: (y: number) => -y } // 画布 (x, y 向下) → three (x, z=-y)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let root: THREE.Group | null = null
const furnitureMeshes = new Map<string, THREE.Mesh>()
let dragging: { id: string; dx: number; dz: number } | null = null
const raycaster = new THREE.Raycaster()
const pointerNdc = new THREE.Vector2()
const floorPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
let resizeObserver: ResizeObserver | null = null

const MATERIALS = {
  floor: new THREE.MeshStandardMaterial({ color: 0xdcd4c3, roughness: 0.95 }),
  wall: new THREE.MeshStandardMaterial({ color: 0xfaf8f4, roughness: 0.9 }),
  furniture: new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.75 }),
  furnitureSelected: new THREE.MeshStandardMaterial({ color: 0xffd9ae, roughness: 0.7 }),
  column: new THREE.MeshStandardMaterial({ color: 0xd8d4cd, roughness: 0.9 }),
  obstacle: new THREE.MeshStandardMaterial({ color: 0xc9c4bc, roughness: 0.9 }),
  noPlacement: new THREE.MeshStandardMaterial({ color: 0xd97757, transparent: true, opacity: 0.35 }),
  doorClearance: new THREE.MeshStandardMaterial({ color: 0x4b8bc6, transparent: true, opacity: 0.4 }),
  window: new THREE.MeshStandardMaterial({ color: 0x9ec7e8, transparent: true, opacity: 0.55 }),
  region: new THREE.MeshBasicMaterial({ color: 0x8fbf8f, transparent: true, opacity: 0.25, side: THREE.DoubleSide }),
  clearance: new THREE.MeshBasicMaterial({ color: 0xd97757, transparent: true, opacity: 0.18, side: THREE.DoubleSide }),
  contactShadow: new THREE.MeshBasicMaterial({ color: 0x2a2419, transparent: true, opacity: 0.16 }),
  edge: new THREE.LineBasicMaterial({ color: 0x3f3a33 }),
}
const FACILITY_COLORS: Record<string, number> = {
  FIRE_FACILITY: 0xc45a3a, ELECTRICAL_BOX: 0xc99a2e, SKIRTING: 0x9a938a, POWER_OUTLET: 0x4b8bc6, DATA_OUTLET: 0x6a5acd,
}

function edgeLines(mesh: THREE.Mesh, geometry: THREE.BufferGeometry) {
  const lines = new THREE.LineSegments(new THREE.EdgesGeometry(geometry), MATERIALS.edge)
  mesh.add(lines)
}

function polygonShape(ring: Array<[number, number]>) {
  const shape = new THREE.Shape()
  ring.forEach(([x, y], i) => (i === 0 ? shape.moveTo(x, y) : shape.lineTo(x, y)))
  shape.closePath()
  return shape
}

function rebuild() {
  if (!root || !sceneData.value) return
  root.clear()
  furnitureMeshes.clear()
  const data = sceneData.value

  // 地板与墙体
  const floorGeo = new THREE.ExtrudeGeometry(polygonShape(data.floor), { depth: WHITE_MODEL_FLOOR_THICKNESS_MM, bevelEnabled: false })
  const floor = new THREE.Mesh(floorGeo, MATERIALS.floor)
  floor.geometry.rotateX(-Math.PI / 2)
  floor.position.y = -WHITE_MODEL_FLOOR_THICKNESS_MM
  root.add(floor)
  for (const wall of data.walls) {
    const length = Math.hypot(wall.b[0] - wall.a[0], wall.b[1] - wall.a[1])
    if (length < 1) continue
    const geo = new THREE.BoxGeometry(length, wall.heightMm, wall.thicknessMm)
    const mesh = new THREE.Mesh(geo, MATERIALS.wall)
    const mx = (wall.a[0] + wall.b[0]) / 2, my = (wall.a[1] + wall.b[1]) / 2
    mesh.position.set(C.x(mx), wall.heightMm / 2, C.z(my))
    mesh.rotation.y = Math.atan2(wall.b[1] - wall.a[1], wall.b[0] - wall.a[0])
    edgeLines(mesh, geo)
    root.add(mesh)
  }

  // 布置区（仅展示）
  for (const region of data.regions) {
    const geo = new THREE.ShapeGeometry(polygonShape(region))
    geo.rotateX(-Math.PI / 2)
    const mesh = new THREE.Mesh(geo, MATERIALS.region)
    mesh.position.y = 2
    root.add(mesh)
  }

  // 家具与障碍体积
  for (const volume of data.volumes) {
    let mesh: THREE.Mesh
    if (volume.ring && volume.ring.length >= 3) {
      const geo = new THREE.ExtrudeGeometry(polygonShape(volume.ring), { depth: volume.heightMm, bevelEnabled: false })
      geo.rotateX(-Math.PI / 2)
      mesh = new THREE.Mesh(geo, MATERIALS[volume.tone])
      mesh.position.y = volume.baseMm
    } else {
      const geo = new THREE.BoxGeometry(volume.w, volume.heightMm, volume.d)
      mesh = new THREE.Mesh(geo, volume.source === 'furniture' && volume.id === selectedId.value ? MATERIALS.furnitureSelected : MATERIALS[volume.tone])
      mesh.position.set(C.x(volume.cx), volume.baseMm + volume.heightMm / 2, C.z(volume.cy))
      mesh.rotation.y = -(volume.rotDeg * Math.PI) / 180
      edgeLines(mesh, geo)
      if (volume.source === 'furniture') {
        // 接地阴影板：让白色家具从地板中分离出来
        const pad = new THREE.Mesh(new THREE.BoxGeometry(volume.w + 90, 15, volume.d + 90), MATERIALS.contactShadow)
        pad.position.set(C.x(volume.cx), 8, C.z(volume.cy))
        pad.rotation.y = mesh.rotation.y
        root!.add(pad)
      }
    }
    mesh.userData = { id: volume.id, source: volume.source, label: volume.label }
    root.add(mesh)
    if (volume.source === 'furniture') furnitureMeshes.set(volume.id, mesh)
  }

  // 设施标注
  for (const facility of data.facilities) {
    const color = FACILITY_COLORS[facility.kind] ?? 0x9a938a
    const material = new THREE.MeshStandardMaterial({ color, roughness: 0.7 })
    if (facility.shape === 'point') {
      const [x, y] = facility.points[0]!
      const w = facility.sizeMm?.widthMm ?? 120
      const d = facility.sizeMm?.depthMm ?? 120
      const geo = new THREE.BoxGeometry(w, 300, d)
      const mesh = new THREE.Mesh(geo, material)
      mesh.position.set(C.x(x), 150, C.z(y))
      mesh.userData = { id: facility.id, source: 'facility', label: facility.label }
      root.add(mesh)
    } else if (facility.shape === 'polyline') {
      for (let i = 0; i < facility.points.length - 1; i++) {
        const [ax, ay] = facility.points[i]!, [bx, by] = facility.points[i + 1]!
        const length = Math.hypot(bx - ax, by - ay)
        if (length < 1) continue
        const mesh = new THREE.Mesh(new THREE.BoxGeometry(length, 100, 40), material)
        mesh.position.set(C.x((ax + bx) / 2), 50, C.z((ay + by) / 2))
        mesh.rotation.y = Math.atan2(by - ay, bx - ax)
        root.add(mesh)
      }
    } else {
      const geo = new THREE.ExtrudeGeometry(polygonShape(facility.points), { depth: 1200, bevelEnabled: false })
      geo.rotateX(-Math.PI / 2)
      const mesh = new THREE.Mesh(geo, material)
      mesh.userData = { id: facility.id, source: 'facility', label: facility.label }
      root.add(mesh)
      if (facility.clearanceMm && facility.clearanceMm > 0) {
        const cx = facility.points.reduce((s, p) => s + p[0], 0) / facility.points.length
        const cy = facility.points.reduce((s, p) => s + p[1], 0) / facility.points.length
        const ring = new THREE.Mesh(new THREE.RingGeometry(Math.max(60, facility.clearanceMm - 60), facility.clearanceMm, 48), MATERIALS.clearance)
        ring.rotation.x = -Math.PI / 2
        ring.position.set(C.x(cx), 3, C.z(cy))
        root.add(ring)
      }
    }
  }

  frameCamera(data)
}

function frameCamera(data: WhiteModelScene) {
  if (!camera || !controls) return
  const cx = (data.bounds.minX + data.bounds.maxX) / 2
  const cy = (data.bounds.minY + data.bounds.maxY) / 2
  const span = Math.max(data.bounds.maxX - data.bounds.minX, data.bounds.maxY - data.bounds.minY, 2000)
  camera.position.set(C.x(cx) + span * 0.55, span * 1.5, C.z(cy) + span * 0.75)
  controls.target.set(C.x(cx), 0, C.z(cy))
  controls.update()
}

function pick(event: PointerEvent): THREE.Intersection | null {
  if (!renderer || !camera || !root) return null
  const rect = renderer.domElement.getBoundingClientRect()
  pointerNdc.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  raycaster.setFromCamera(pointerNdc, camera)
  const hits = raycaster.intersectObjects(root.children, true)
  return hits.find(hit => hit.object.userData?.id || hit.object.parent?.userData?.id) || null
}

function onPointerDown(event: PointerEvent) {
  const hit = pick(event)
  const id = (hit?.object.userData?.id || hit?.object.parent?.userData?.id) as string | undefined
  if (!id) { selectedId.value = ''; return }
  selectedId.value = id
  const mesh = furnitureMeshes.get(id)
  if (!mesh || !camera || !renderer) return
  // 拖动家具：锁定轨道控制，沿地面平面移动
  controls!.enabled = false
  const point = new THREE.Vector3()
  raycaster.ray.intersectPlane(floorPlane, point)
  dragging = { id, dx: mesh.position.x - point.x, dz: mesh.position.z - point.z }
  renderer.domElement.setPointerCapture(event.pointerId)
}

function onPointerMove(event: PointerEvent) {
  if (!renderer || !camera || !root) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointerNdc.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  raycaster.setFromCamera(pointerNdc, camera)
  if (dragging) {
    const point = new THREE.Vector3()
    if (raycaster.ray.intersectPlane(floorPlane, point)) {
      const mesh = furnitureMeshes.get(dragging.id)!
      mesh.position.set(point.x + dragging.dx, mesh.position.y, point.z + dragging.dz)
    }
    return
  }
  const hit = pick(event)
  hoverLabel.value = (hit?.object.userData?.label || hit?.object.parent?.userData?.label || '') as string
}

function onPointerUp(event: PointerEvent) {
  if (!dragging) return
  const { id } = dragging
  dragging = null
  if (controls) controls.enabled = true
  renderer?.domElement.releasePointerCapture(event.pointerId)
  const mesh = furnitureMeshes.get(id)
  const item = furniture.value.find(entry => entry.id === id)
  if (!mesh || !item || !sceneData.value) return
  // 写回同一份设计几何：three → 画布坐标（含旋转后的占地近似仍以轴对齐记录）
  const cx = mesh.position.x, cy = -mesh.position.z
  const nx = Math.round(cx - item.w / 2), ny = Math.round(cy - item.h / 2)
  if (nx !== Math.round(item.x) || ny !== Math.round(item.y)) {
    store.moveObject(id, nx, ny)
    notice.value = `已移动「${item.label || '家具'}」到 (${nx}, ${ny})，与 2D 排版同步`
  }
}

function rotateSelected() {
  const item = selectedItem.value
  if (!item) return
  store.updateObject(item.id, { rotation: ((item.rotation || 0) + 90) % 360 } as any)
  notice.value = `已旋转「${item.label || '家具'}」90°`
}

function replaceTemplate(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  const item = selectedItem.value
  const template = furnitureCatalogTemplates.find(entry => entry.id === id)
  if (!item || !template) return
  store.updateObject(item.id, { furnitureTemplateId: template.id, w: template.widthMm, h: template.depthMm, label: template.name } as any)
  notice.value = `已替换为「${template.name}」（${template.widthMm}×${template.depthMm}mm）`
}

function bindProduct(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  const item = selectedItem.value
  const product = products.value.find(entry => entry.id === id)
  if (!item) return
  if (!product) { store.updateObject(item.id, { productId: '' } as any); notice.value = '已解除商品绑定'; return }
  store.updateObject(item.id, { productId: product.id, w: product.widthMm, h: product.depthMm } as any)
  notice.value = `已绑定商品「${product.name}」，尺寸同步为 ${product.widthMm}×${product.depthMm}mm`
}

async function init() {
  if (!host.value) return
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true })
  } catch {
    initError.value = '当前浏览器不支持 WebGL，无法渲染 3D 白模。'
    return
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.domElement.style.display = 'block'
  host.value.appendChild(renderer.domElement)
  scene = new THREE.Scene()
  scene.background = new THREE.Color(0xfaf8f5)
  camera = new THREE.PerspectiveCamera(45, 1, 10, 200000)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.maxPolarAngle = Math.PI / 2 - 0.02

  scene.add(new THREE.HemisphereLight(0xffffff, 0xcfc8bd, 1.9))
  scene.add(new THREE.AmbientLight(0xffffff, 0.45))
  const sun = new THREE.DirectionalLight(0xffffff, 2.3)
  sun.position.set(1, 1.6, 0.8)
  scene.add(sun)
  root = new THREE.Group()
  scene.add(root)

  renderer.domElement.addEventListener('pointerdown', onPointerDown)
  renderer.domElement.addEventListener('pointermove', onPointerMove)
  renderer.domElement.addEventListener('pointerup', onPointerUp)

  const resize = () => {
    if (!host.value || !renderer || !camera) return
    const { clientWidth: w, clientHeight: h } = host.value
    if (w && h) { renderer.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix() }
  }
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host.value)
  resize()
  renderer.setAnimationLoop(() => { controls?.update(); if (renderer && scene && camera) renderer.render(scene, camera) })
  // e2e 测试钩子：家具中心点的屏幕坐标（拖动仿真用）。
  ;(window as any).__whiteModelProject = (id: string) => {
    const mesh = furnitureMeshes.get(id)
    if (!mesh || !camera || !renderer) return null
    const v = mesh.position.clone().project(camera)
    const rect = renderer.domElement.getBoundingClientRect()
    return { x: rect.left + ((v.x + 1) / 2) * rect.width, y: rect.top + ((-v.y + 1) / 2) * rect.height }
  }
  ready3d.value = true
  rebuild()
}

function dispose() {
  delete (window as any).__whiteModelProject
  resizeObserver?.disconnect()
  resizeObserver = null
  renderer?.setAnimationLoop(null)
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null; scene = null; camera = null; controls = null; root = null
  furnitureMeshes.clear(); dragging = null; ready3d.value = false
}

watch(show, async value => {
  if (value) {
    initError.value = ''; notice.value = ''; selectedId.value = ''
    if (products.value.length === 0) {
      try { products.value = await api.loadProducts() } catch { productsError.value = '商品列表加载失败，仅影响绑定商品操作' }
    }
    await nextTick()
    await init()
  } else dispose()
})
watch(sceneKey, () => { if (ready3d.value) rebuild() })
watch(selectedId, id => {
  for (const [meshId, mesh] of furnitureMeshes) mesh.material = meshId === id ? MATERIALS.furnitureSelected : MATERIALS.furniture
})

onBeforeUnmount(dispose)

const legend = [
  ['white', '家具 / 墙体'],
  ['#d8d4cd', obstacleKindLabel.COLUMN + ' / 井道'],
  ['#d97757', obstacleKindLabel.NO_PLACEMENT],
  ['#4b8bc6', obstacleKindLabel.DOOR_CLEARANCE],
  ['#9ec7e8', obstacleKindLabel.WINDOW],
  ['#8fbf8f', '家具布置区'],
] as const
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-[90] flex flex-col bg-[#faf8f5]" data-testid="white-model-preview"
      :data-ready="ready3d || undefined"
      :data-furniture="sceneData?.volumes.filter(v => v.source === 'furniture').length ?? 0"
      :data-obstacles="sceneData?.volumes.filter(v => v.source === 'obstacle').length ?? 0"
      :data-facilities="sceneData?.facilities.length ?? 0"
      :data-walls="sceneData?.walls.length ?? 0">
      <header class="flex flex-none items-center justify-between border-b border-line bg-white px-4 py-2.5">
        <div>
          <h2 class="text-sm font-bold">3D 白模预览 · {{ zone?.name || '当前空间' }}</h2>
          <p class="text-xs text-muted">自由旋转/缩放视角；点选家具可拖动、旋转、替换，改动实时同步 2D 排版</p>
        </div>
        <div class="flex items-center gap-2">
          <button class="rounded-lg border border-line px-3 py-1.5 text-xs text-muted hover:bg-cream" @click="emit('ai-render')">AI 概念效果图</button>
          <button class="rounded-lg border border-line px-3 py-1.5 text-xs" aria-label="关闭 3D 白模预览" @click="show = false">✕ 关闭</button>
        </div>
      </header>
      <div class="flex min-h-0 flex-1">
        <div ref="host" class="relative min-w-0 flex-1 cursor-grab active:cursor-grabbing">
          <div v-if="!ready" class="absolute inset-0 z-10 grid place-items-center bg-[#faf8f5] text-sm text-muted">请先完成图纸比例校准（毫米）后再预览 3D 白模</div>
          <div v-else-if="initError" class="absolute inset-0 z-10 grid place-items-center bg-[#faf8f5] text-sm text-red-700">{{ initError }}</div>
          <div v-if="hoverLabel" class="pointer-events-none absolute left-3 top-3 z-10 rounded-lg bg-black/70 px-2.5 py-1 text-xs text-white">{{ hoverLabel }}</div>
          <div class="absolute bottom-3 left-3 z-10 flex flex-wrap gap-2 rounded-lg bg-white/85 px-3 py-2 text-[11px] text-muted shadow">
            <span v-for="[color, label] in legend" :key="label" class="flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border border-line" :style="{ background: color }"></i>{{ label }}</span>
          </div>
        </div>
        <aside class="flex w-72 flex-none flex-col gap-3 overflow-y-auto border-l border-line bg-white p-3 text-xs">
          <div>
            <h3 class="mb-1 font-semibold text-ink">家具（{{ furniture.length }}）</h3>
            <ul class="space-y-1">
              <li v-for="item in furniture" :key="item.id">
                <button class="w-full rounded-lg border px-2.5 py-1.5 text-left transition"
                  :class="item.id === selectedId ? 'border-bronze bg-[#f7f0e6] text-ink' : 'border-line text-muted hover:bg-cream'"
                  :data-testid="'white-model-select-' + item.id" @click="selectedId = item.id">
                  {{ item.label || '家具' }} · {{ Math.round(item.w) }}×{{ Math.round(item.h) }}mm
                </button>
              </li>
            </ul>
          </div>
          <div v-if="selectedItem" class="rounded-lg border border-line p-2.5" data-testid="white-model-panel">
            <h3 class="mb-2 font-semibold text-ink">{{ selectedItem.label || '家具' }}</h3>
            <p class="mb-2 text-muted">{{ Math.round(selectedItem.w) }}×{{ Math.round(selectedItem.h) }}mm · 高 {{ furnitureHeightMm(selectedItem.furnitureTemplateId) }}mm · 旋转 {{ selectedItem.rotation || 0 }}°</p>
            <button class="mb-2 w-full rounded-lg border border-line px-2 py-1.5 hover:bg-cream" data-testid="white-model-rotate" @click="rotateSelected">旋转 90°</button>
            <label class="mb-1 block text-muted">替换家具模块</label>
            <select class="mb-2 w-full rounded-lg border border-line bg-white px-2 py-1.5" :value="selectedItem.furnitureTemplateId" data-testid="white-model-template" @change="replaceTemplate">
              <option v-for="t in furnitureCatalogTemplates" :key="t.id" :value="t.id">{{ t.name }}（{{ t.widthMm }}×{{ t.depthMm }}）</option>
            </select>
            <label class="mb-1 block text-muted">绑定 / 替换商品{{ selectedProduct ? `（当前：${selectedProduct.name}）` : '' }}</label>
            <select class="w-full rounded-lg border border-line bg-white px-2 py-1.5" :value="selectedItem.productId || ''" data-testid="white-model-product" @change="bindProduct">
              <option value="">不绑定商品</option>
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}（{{ p.widthMm }}×{{ p.depthMm }}mm ¥{{ p.price }}）</option>
            </select>
            <p v-if="productsError" class="mt-1 text-amber-700">{{ productsError }}</p>
          </div>
          <div v-else class="rounded-lg border border-dashed border-line p-3 text-muted">点击场景中的家具或上方列表进行选择</div>
          <div>
            <h3 class="mb-1 font-semibold text-ink">设施标注（{{ sceneData?.facilities.length || 0 }}）</h3>
            <ul class="space-y-1 text-muted">
              <li v-for="f in sceneData?.facilities || []" :key="f.id" class="flex items-center gap-1.5">
                <i class="inline-block h-2.5 w-2.5 rounded-full" :style="{ background: '#' + (FACILITY_COLORS[f.kind] ?? 0x9a938a).toString(16).padStart(6, '0') }"></i>
                {{ f.label }}<span class="text-[10px]">{{ facilityKindLabel[f.kind] }}<template v-if="f.clearanceMm"> · 净空 {{ f.clearanceMm }}mm</template></span>
              </li>
              <li v-if="!sceneData?.facilities.length" class="text-muted">当前空间没有设施标注</li>
            </ul>
          </div>
          <p v-if="notice" class="rounded-lg bg-[#eef5ee] px-2.5 py-2 text-[#3c6b3c]" data-testid="white-model-notice">{{ notice }}</p>
        </aside>
      </div>
    </div>
  </Teleport>
</template>
