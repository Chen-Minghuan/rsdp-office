<script setup lang="ts">
import { uid } from '~/utils/helpers'
import { TONES } from '~/utils/sample'
import type { OfficeObject, ZoneObject, FurnitureObject } from '~/types/office'

const store = useOfficeStore()
const tool = defineModel<string>('tool', { default: 'select' })
const selectedId = defineModel<string | null>('selectedId')
const showSubmit = defineModel<boolean>('showSubmit')
const mode = defineModel<'overview' | 'design'>('mode', { default: 'overview' })

const W = 1540
const H = 960

const canvasWindow = ref<HTMLDivElement | null>(null)
const board = ref<HTMLDivElement | null>(null)
const drawPreview = ref<HTMLDivElement | null>(null)

const hintText = computed(() => {
  const map: Record<string, string> = {
    select: '选择并拖动画布对象，右侧可编辑属性',
    pan: '按住并拖动画布，也可以使用滚轮缩放',
    zone: '在画布上按住并拖出矩形功能区',
    note: '在画布上单击，添加并编辑需求便签',
    arrow: '在画布上拖拽，绘制指向箭头',
    reference: '点击画布放置参考素材，再编辑其说明',
  }
  return map[tool.value] || ''
})

const zones = computed(() => store.objects.filter((o) => o.kind === 'zone') as ZoneObject[])
const arrows = computed(() => store.objects.filter((o) => o.kind === 'arrow'))
const designZone = computed(() => zones.value.find((z) => z.id === store.designZoneId) || null)
const furniture = computed(() => {
  if (mode.value !== 'design') return []
  const list = store.objects.filter((o) => o.kind === 'furniture') as FurnitureObject[]
  if (designZone.value) {
    return list.filter((f) => f.zoneId === designZone.value!.id)
  }
  return list
})

function placeBoard() {
  if (!board.value) return
  board.value.style.transform = `translate(calc(-50% + ${store.panX}px), calc(-50% + ${store.panY}px)) scale(${store.zoom})`
}

function fitBoard() {
  if (mode.value === 'design' && designZone.value) {
    fitZone(designZone.value)
    return
  }
  const v = canvasWindow.value
  if (!v || !v.clientWidth || !v.clientHeight) return
  store.zoom = Math.min(0.84, (v.clientWidth - 30) / W, (v.clientHeight - 30) / H)
  store.panX = 0
  store.panY = 0
  placeBoard()
}

function fitZone(z: ZoneObject, padding = 80) {
  const v = canvasWindow.value
  if (!v || !v.clientWidth || !v.clientHeight) return
  const zw = z.w + padding * 2
  const zh = z.h + padding * 2
  const scale = Math.min(1.6, (v.clientWidth - 40) / zw, (v.clientHeight - 40) / zh, 1.6)
  store.zoom = Math.max(0.5, scale)
  const centerX = z.x + z.w / 2
  const centerY = z.y + z.h / 2
  store.panX = (W / 2 - centerX) * store.zoom
  store.panY = (H / 2 - centerY) * store.zoom
  placeBoard()
}

function point(e: PointerEvent) {
  const r = board.value!.getBoundingClientRect()
  return {
    x: Math.round((e.clientX - r.left) / store.zoom),
    y: Math.round((e.clientY - r.top) / store.zoom),
  }
}

interface DragState {
  mode: 'pan' | 'move' | 'zone' | 'arrow'
  cx?: number
  cy?: number
  px?: number
  py?: number
  id?: string
  ox?: number
  oy?: number
  moved?: boolean
  x?: number
  y?: number
  lastX?: number
  lastY?: number
}

const drag = ref<DragState | null>(null)

function chooseTool(t: string) {
  tool.value = t
  selectedId.value = null
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  store.zoom = Math.max(0.25, Math.min(2.3, store.zoom * (e.deltaY < 0 ? 1.09 : 1 / 1.09)))
  placeBoard()
}

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0 || !canvasWindow.value) return
  const target = e.target as HTMLElement
  const hit = target.closest('.board-element') as HTMLElement | null
  const p = point(e)

  if (tool.value === 'pan') {
    drag.value = { mode: 'pan', cx: e.clientX, cy: e.clientY, px: store.panX, py: store.panY }
    canvasWindow.value.setPointerCapture(e.pointerId)
    return
  }

  if (tool.value === 'select') {
    if (hit) {
      const id = hit.dataset.id!
      selectedId.value = id
      const o = store.objects.find((x) => x.id === id)!
      store.snapshotUndo()
      drag.value = { mode: 'move', id, cx: p.x, cy: p.y, ox: o.x, oy: o.y, moved: false }
      canvasWindow.value.setPointerCapture(e.pointerId)
    } else {
      selectedId.value = null
    }
    return
  }

  if (tool.value === 'note') {
    store.addObject({ id: uid('n'), kind: 'note', x: p.x, y: p.y, text: '在这里填写办公需求说明…' })
    selectedId.value = store.objects[store.objects.length - 1].id
    tool.value = 'select'
    return
  }

  if (tool.value === 'reference') {
    store.addObject({ id: uid('r'), kind: 'reference', x: p.x, y: p.y, caption: '办公风格参考' })
    selectedId.value = store.objects[store.objects.length - 1].id
    tool.value = 'select'
    return
  }

  if (tool.value === 'zone' || tool.value === 'arrow') {
    drag.value = { mode: tool.value, x: p.x, y: p.y, lastX: p.x, lastY: p.y }
    canvasWindow.value.setPointerCapture(e.pointerId)
    if (tool.value === 'zone' && drawPreview.value) {
      drawPreview.value.style.display = 'block'
      updatePreview(p, p)
    }
  }
}

function updatePreview(a: { x: number; y: number }, b: { x: number; y: number }) {
  if (!drawPreview.value) return
  drawPreview.value.style.left = Math.min(a.x, b.x) + 'px'
  drawPreview.value.style.top = Math.min(a.y, b.y) + 'px'
  drawPreview.value.style.width = Math.abs(a.x - b.x) + 'px'
  drawPreview.value.style.height = Math.abs(a.y - b.y) + 'px'
}

function onPointerMove(e: PointerEvent) {
  if (!drag.value || !canvasWindow.value) return
  const d = drag.value
  if (d.mode === 'pan') {
    store.panX = (d.px || 0) + (e.clientX - (d.cx || 0))
    store.panY = (d.py || 0) + (e.clientY - (d.cy || 0))
    placeBoard()
  } else if (d.mode === 'move' && d.id) {
    const p = point(e)
    const o = store.objects.find((x) => x.id === d.id)
    if (!o) return
    const nx = Math.max(0, Math.min(W - 60, (d.ox || 0) + p.x - (d.cx || 0)))
    const ny = Math.max(0, Math.min(H - 60, (d.oy || 0) + p.y - (d.cy || 0)))
    if (Math.abs(nx - o.x) > 1 || Math.abs(ny - o.y) > 1) {
      if (!d.moved) d.moved = true
      const el = document.querySelector(`.board-element[data-id="${d.id}"]`) as HTMLElement
      if (el) {
        el.style.left = nx + 'px'
        el.style.top = ny + 'px'
      }
      if (o.kind === 'arrow') {
        ;(o as any).x2 = (o as any).x2 + nx - o.x
        ;(o as any).y2 = (o as any).y2 + ny - o.y
      }
      o.x = nx
      o.y = ny
    }
  } else if (d.mode === 'zone') {
    updatePreview(d, point(e))
  } else if (d.mode === 'arrow') {
    d.lastX = point(e).x
    d.lastY = point(e).y
  }
}

function onPointerUp(e: PointerEvent) {
  if (!drag.value || !drawPreview.value) {
    drag.value = null
    return
  }
  const d = drag.value
  drag.value = null

  if (d.mode === 'zone') {
    drawPreview.value.style.display = 'none'
    const p = point(e)
    const x = Math.min(p.x, d.x || 0)
    const y = Math.min(p.y, d.y || 0)
    const w = Math.abs(p.x - (d.x || 0))
    const h = Math.abs(p.y - (d.y || 0))
    if (w > 45 && h > 35) {
      const toneKeys = Object.keys(TONES) as Array<keyof typeof TONES>
      const tone = toneKeys[zones.value.length % toneKeys.length]
      store.addObject({
        id: uid('z'),
        kind: 'zone',
        x,
        y,
        w,
        h,
        name: '新办公区域',
        zoneType: 'OPEN_OFFICE',
        seats: 0,
        note: '',
        tone,
      })
      selectedId.value = store.objects[store.objects.length - 1].id
      tool.value = 'select'
    }
  } else if (d.mode === 'arrow') {
    const p = point(e)
    if (Math.abs(p.x - (d.x || 0)) + Math.abs(p.y - (d.y || 0)) > 30) {
      store.addObject({ id: uid('a'), kind: 'arrow', x: d.x || 0, y: d.y || 0, x2: p.x, y2: p.y })
      tool.value = 'select'
    }
  } else if (d.mode === 'move' && d.id && d.moved) {
    const o = store.objects.find((x) => x.id === d.id)
    if (o) {
      store.moveObject(d.id, o.x, o.y)
    }
  }
}

function onKeydown(e: KeyboardEvent) {
  const active = document.activeElement as HTMLElement
  const typing = active && ['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName)
  if (e.key === 'Escape') {
    selectedId.value = null
    showSubmit.value = false
    return
  }
  if (typing || showSubmit.value) return
  if (['Delete', 'Backspace'].includes(e.key) && selectedId.value) {
    e.preventDefault()
    store.deleteObject(selectedId.value)
    selectedId.value = null
    return
  }
  const shortcuts: Record<string, string> = { v: 'select', h: 'pan', r: 'zone', n: 'note', a: 'arrow' }
  const t = shortcuts[e.key.toLowerCase()]
  if (t) chooseTool(t)
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  nextTick(() => {
    fitBoard()
  })
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})

watch(() => store.objects.length, () => {
  nextTick(placeBoard)
})

watch([mode, designZone], () => {
  nextTick(() => fitBoard())
}, { immediate: true })

defineExpose({ fitBoard })

function objectStyle(o: OfficeObject) {
  const base: Record<string, string> = { position: 'absolute', left: o.x + 'px', top: o.y + 'px' }
  return base
}

function zoneStyle(o: ZoneObject) {
  const t = TONES[o.tone] || TONES.blue
  return {
    position: 'absolute',
    left: o.x + 'px',
    top: o.y + 'px',
    width: o.w + 'px',
    height: o.h + 'px',
    borderRadius: '8px',
    border: `2px solid ${t.stroke}`,
    backgroundColor: t.fill,
  }
}

function furnitureStyle(o: FurnitureObject) {
  return {
    position: 'absolute',
    left: o.x + 'px',
    top: o.y + 'px',
    width: o.w + 'px',
    height: o.h + 'px',
    transform: o.rotation ? `rotate(${o.rotation}deg)` : undefined,
  }
}

function furnitureProduct(o: FurnitureObject) {
  return store.products.find((p) => p.id === o.productId)
}
</script>

<template>
  <div
    ref="canvasWindow"
    class="relative flex-1 overflow-hidden bg-[#faf9f7] cursor-crosshair touch-none"
    style="background-image: radial-gradient(#dfdad4 1px, transparent 1px); background-size: 18px 18px;"
    @wheel.prevent="onWheel"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
  >
    <div
      ref="board"
      class="absolute left-1/2 top-1/2 w-[1540px] h-[960px]"
      style="transform-origin: center center; will-change: transform;"
    >
      <!-- Floor plan SVG -->
      <svg
        class="absolute pointer-events-none"
        style="left: 230px; top: 170px; width: 1090px; height: 640px;"
        viewBox="0 0 1090 640"
        :style="{ opacity: store.blank ? 0.05 : 1 }"
      >
        <rect width="1090" height="640" fill="#fffdf9" />
        <g fill="none" stroke="#47505a" stroke-width="7">
          <path d="M24 19 H1066 V621 H24 Z" />
          <path d="M735 19 V290 M24 402 H735 M504 402 V621 M735 290 H1066 M895 290 V621" />
        </g>
        <g fill="none" stroke="#767b7a" stroke-width="2">
          <path d="M42 20 V42 M150 20 V42 M320 20 V42 M495 20 V42 M690 20 V42 M850 20 V42 M1020 20 V42" />
          <path d="M735 75 h18 M735 150 h18 M735 225 h18 M895 350 h-18 M895 450 h-18" />
          <path d="M270 402 V380 M430 402 V380 M650 402 V380" />
        </g>
        <g stroke="#a4aeb0" stroke-width="3">
          <path d="M50 16 H185 M310 16 H460 M750 16 H865 M925 16 H1045" stroke="#78b7d4" />
          <path d="M24 150 V292 M1066 410 V540" stroke="#78b7d4" />
        </g>
        <g stroke="#b6b0a5" stroke-width="2" fill="none">
          <path d="M735 289 h60 a60 60 0 0 1-60 60" />
          <path d="M505 402 v55 a55 55 0 0 0 55-55" />
          <path d="M895 290 v48 a48 48 0 0 0 48-48" />
        </g>
        <g fill="#dfdcd6" stroke="#aba89f">
          <rect x="450" y="110" width="38" height="38" />
          <rect x="258" y="363" width="33" height="33" />
          <rect x="980" y="560" width="30" height="30" />
        </g>
        <g fill="#d0c8ba" opacity=".65">
          <rect x="85" y="72" width="140" height="10" rx="3" />
          <rect x="250" y="72" width="140" height="10" rx="3" />
          <rect x="415" y="72" width="140" height="10" rx="3" />
          <rect x="80" y="325" width="550" height="7" rx="3" />
        </g>
        <g stroke="#e1d6c4" stroke-width="2" fill="none">
          <rect x="801" y="80" width="186" height="113" rx="4" />
          <rect x="817" y="97" width="154" height="80" rx="4" />
          <rect x="795" y="455" width="67" height="38" rx="6" />
        </g>
        <text x="63" y="599" fill="#8f8b82" font-size="16">模拟办公图纸 · 非真实识别</text>
      </svg>

      <!-- Arrows -->
      <svg width="1540" height="960" viewBox="0 0 1540 960" class="absolute inset-0 pointer-events-none">
        <defs>
          <marker id="arrowHead" markerWidth="12" markerHeight="12" refX="8" refY="4" orient="auto">
            <path d="M0 0 L8 4 L0 8" stroke="#b36a43" fill="none" stroke-width="2" />
          </marker>
        </defs>
        <line
          v-for="a in arrows"
          :key="a.id"
          :x1="a.x"
          :y1="a.y"
          :x2="(a as any).x2"
          :y2="(a as any).y2"
          stroke="#b36a43"
          stroke-width="4"
          stroke-linecap="round"
          marker-end="url(#arrowHead)"
        />
      </svg>

      <!-- Objects -->
      <div class="absolute inset-0 pointer-events-none">
        <template v-for="o in store.objects" :key="o.id">
          <!-- Zone -->
          <div
            v-if="o.kind === 'zone'"
            :data-id="o.id"
            class="board-element pointer-events-auto flex items-center justify-center text-center p-1.5 cursor-move select-none overflow-hidden transition-opacity"
            :class="[
              selectedId === o.id ? 'border-[3px] border-dashed border-[#306fcb] shadow-[0_0_0_4px_rgba(76,118,182,0.13)]' : 'hover:saturate-110 border-2 border-transparent',
              mode === 'design' && store.designZoneId !== o.id ? 'opacity-25 pointer-events-none' : 'opacity-100',
            ]"
            :style="zoneStyle(o as any)"
            @pointerdown.stop="onPointerDown"
          >
            <div>
              <div class="text-xl font-bold text-[#33434b] whitespace-nowrap">{{ (o as any).name }}</div>
              <div class="mt-1 text-[15px] text-[#56636e]">{{ (o as any).seats ? '约 ' + (o as any).seats + ' 个位置' : '功能区域' }}</div>
            </div>
          </div>

          <!-- Note -->
          <div
            v-else-if="o.kind === 'note'"
            :data-id="o.id"
            class="board-element pointer-events-auto w-[180px] min-h-[114px] bg-[#fff0a5] shadow-lg p-4 text-[#634c2b] whitespace-pre-wrap text-[17px] leading-relaxed cursor-move overflow-wrap-anywhere transition-opacity"
            style="transform: rotate(-3deg);"
            :class="[
              selectedId === o.id ? 'outline outline-4 outline-[#5092db]' : '',
              mode === 'design' ? 'opacity-30 pointer-events-none' : 'opacity-100',
            ]"
            :style="objectStyle(o)"
            @pointerdown.stop="onPointerDown"
          >
            {{ (o as any).text }}
          </div>

          <!-- Reference -->
          <div
            v-else-if="o.kind === 'reference'"
            :data-id="o.id"
            class="board-element pointer-events-auto w-[188px] h-[168px] shadow-xl bg-white border-[8px] border-white cursor-move transition-opacity"
            style="transform: rotate(3deg);"
            :class="[
              selectedId === o.id ? 'outline outline-4 outline-[#5092db]' : '',
              mode === 'design' ? 'opacity-30 pointer-events-none' : 'opacity-100',
            ]"
            :style="objectStyle(o)"
            @pointerdown.stop="onPointerDown"
          >
            <div class="h-[119px] overflow-hidden bg-gradient-to-br from-[#ddd0be] to-[#faf5ed] relative">
              <img v-if="(o as any).image" :src="(o as any).image" class="w-full h-full object-cover">
              <div v-else class="absolute inset-0 grid place-items-center text-[#927f6d] text-sm">▤<br>办公风格参考</div>
            </div>
            <div class="text-[13px] text-center text-[#6b6056] pt-1 truncate">{{ (o as any).caption || '办公参考图' }}</div>
          </div>
        </template>

        <!-- Furniture -->
        <div
          v-for="f in furniture"
          :key="f.id"
          :data-id="f.id"
          class="board-element pointer-events-auto absolute flex flex-col items-center justify-center text-center cursor-move select-none overflow-hidden rounded-lg border bg-white/90 shadow-sm transition-opacity"
          :class="[
            selectedId === f.id ? 'border-[#306fcb] ring-[3px] ring-[#306fcb]/15 z-10' : 'border-[#d8cfc4] hover:border-bronze',
            mode === 'overview' ? 'opacity-50' : 'opacity-100',
          ]"
          :style="furnitureStyle(f)"
          @pointerdown.stop="onPointerDown"
        >
          <div class="text-[10px] font-bold text-[#4a443f] truncate max-w-full px-1 leading-tight">
            {{ f.label || furnitureProduct(f)?.name }}
          </div>
          <div class="text-[9px] text-[#7d746c] truncate max-w-full px-1">
            {{ Math.round(f.w) }}×{{ Math.round(f.h) }}
          </div>
        </div>
      </div>

      <!-- Draw preview -->
      <div
        ref="drawPreview"
        class="absolute border-2 border-dashed border-[#4185c2] bg-[#6ca6da32] pointer-events-none hidden"
      />
    </div>

    <div class="absolute left-4 bottom-3.5 bg-white/90 border border-line rounded-lg px-3 py-2 text-[11px] text-[#79736d] z-10">
      {{ hintText }}
    </div>
    <div class="absolute right-3.5 bottom-3.5 bg-white border border-line rounded-lg px-2 py-1.5 text-[#7e7770] text-xs flex items-center gap-2 z-10">
      <span>⌕</span>
      <span>演示空间资料</span>
    </div>
  </div>
</template>
