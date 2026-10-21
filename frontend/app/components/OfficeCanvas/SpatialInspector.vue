<script setup lang="ts">
import type { SpatialSpace } from '~/types/spatial'
import { ZONE_TYPES } from '~/utils/sample'

const props = defineProps<{
  space: SpatialSpace | null
  isCalibrated: boolean
  pendingCount: number
  issue?: string | null
  issueBlocking?: boolean
  needsReview?: boolean
  layoutBlocker?: string | null
  templateSource?: boolean
  sourceNote?: string
  canRestoreSource?: boolean
  calibrationActive: boolean
}>()
const emit = defineEmits<{
  (e: 'resize', width: number, depth: number): void
  (e: 'category', category: string): void
  (e: 'startCalibration'): void
  (e: 'restoreSource'): void
}>()
const showUnderlay = defineModel<boolean>('showUnderlay', { default: true })
const showOutline = defineModel<boolean>('showOutline', { default: true })
const showDimensions = defineModel<boolean>('showDimensions', { default: true })
const showOriginal = defineModel<boolean>('showOriginal', { default: false })
const underlayOpacity = defineModel<number>('underlayOpacity', { default: 0.75 })
const width = ref<number | null>(null), depth = ref<number | null>(null)
watch(() => [props.space?.spaceId, props.space?.derived.rectangleDimensions?.widthMm, props.space?.derived.rectangleDimensions?.depthMm], () => {
  width.value = Math.round(props.space?.derived.rectangleDimensions?.widthMm || 0) || null
  depth.value = Math.round(props.space?.derived.rectangleDimensions?.depthMm || 0) || null
}, { immediate: true })
const validDimensions = computed(() => Number.isFinite(width.value) && Number.isFinite(depth.value) && Number(width.value) > 0 && Number(depth.value) > 0)
const sourceLabel = computed(() => props.space?.evidenceKind === 'DEMO' ? '演示几何' : props.space?.evidenceKind === 'ESTIMATED' ? '识别候选 · 来源待核验' : props.space?.origin === 'VISION' ? '图像识别' : props.space?.origin === 'CAD' ? 'CAD 解析' : '人工编辑')
</script>

<template>
  <aside class="review-inspector review-panel" aria-label="空间属性">
    <div class="inspector-heading"><h2>空间属性</h2><small>当前选中</small></div>
    <template v-if="space">
      <div class="selected-title"><h3>{{ space.name }}</h3><span class="review-badge" :class="needsReview ? 'pending' : 'verified'">{{ needsReview ? '需复核' : '可用' }}</span></div>
      <p class="room-id">空间 ID · {{ space.spaceId }}</p>
      <div class="property-row"><span>空间用途</span><select :value="space.category" aria-label="空间用途" @change="emit('category', ($event.target as HTMLSelectElement).value)"><option v-for="[value, label] in ZONE_TYPES" :key="value" :value="value">{{ label }}</option></select></div>
      <div class="property-row"><span>几何来源</span><strong>{{ sourceLabel }}</strong></div>
      <p v-if="sourceNote" class="inspector-hint" role="status">{{ sourceNote }}</p>
      <section class="inspector-section">
        <div class="section-heading"><h4>实际尺寸</h4><small>毫米 mm</small></div>
        <form @submit.prevent="validDimensions && emit('resize', Number(width), Number(depth))">
          <div class="dimension-inputs">
            <label>宽度<div><input v-model.number="width" aria-label="空间宽度（毫米）" type="number" min="1" step="any" :disabled="!isCalibrated || !space.rectangleFrame" placeholder="待校准"><span>mm</span></div></label>
            <label>进深<div><input v-model.number="depth" aria-label="空间进深（毫米）" type="number" min="1" step="any" :disabled="!isCalibrated || !space.rectangleFrame" placeholder="待校准"><span>mm</span></div></label>
          </div>
          <button v-if="space.rectangleFrame" class="dimension-apply" type="submit" :disabled="!validDimensions || !isCalibrated">应用尺寸</button>
        </form>
        <div class="property-row"><span>净面积</span><strong data-testid="selected-area">{{ space.derived.areaM2 != null ? space.derived.areaM2.toFixed(2) + ' m²' : '待校准' }}</strong></div>
        <div class="property-row"><span>编辑锚点</span><strong>{{ space.rectangleFrame ? '局部左下角固定' : '保持异形轮廓' }}</strong></div>
        <p class="inspector-hint">{{ space.rectangleFrame ? '数值与轮廓联动；修改后更新关联排版。' : '可拖动选中顶点修正异形边界，面积按多边形计算。' }}<br>缺少入口或转折时，点“编辑轮廓”补点，或沿墙重描。底图保留原始状态。</p>
        <p v-if="issue" class="geometry-issue" :role="issueBlocking ? 'alert' : 'status'">{{ issue }}</p>
        <button v-if="canRestoreSource" class="calibration-button" type="button" @click="emit('restoreSource')">恢复本次识别轮廓</button>
      </section>
    </template>
    <div v-else class="inspector-hint empty-selection">在左侧列表或画布中选择空间。</div>
    <section class="inspector-section calibration-section">
      <div class="section-heading"><h4>比例校准</h4><span class="calibration-state" :class="{ calibrated: isCalibrated }">{{ isCalibrated ? '比例已校准' : '待校准' }}</span></div>
      <slot name="automatic-calibration" />
      <button v-if="!calibrationActive" class="calibration-button" @click="emit('startCalibration')">两点校准图纸比例 <span>↗</span></button>
      <p v-else class="inspector-hint">正在两点校准：在图纸上所选两点旁输入实际长度并应用。</p>
    </section>
    <section class="inspector-section layers-section">
      <h4>图层与对照</h4>
      <label class="layer-toggle"><span>原始图纸</span><input v-model="showUnderlay" type="checkbox" role="switch"></label>
      <label class="opacity-label"><span>底图透明度</span><span>{{ Math.round(underlayOpacity * 100) }}%</span></label>
      <input v-model.number="underlayOpacity" aria-label="底图透明度" type="range" min="0.1" max="1" step="0.05" class="opacity-slider">
      <label class="layer-toggle"><span>设计空间轮廓</span><input v-model="showOutline" type="checkbox" role="switch"></label>
      <label class="layer-toggle"><span>实际尺寸标注</span><input v-model="showDimensions" type="checkbox" role="switch"></label>
      <label class="layer-toggle"><span>{{ templateSource ? '模板原始轮廓' : '原始识别轮廓' }}</span><input v-model="showOriginal" type="checkbox" role="switch"></label>
    </section>
    <div class="review-guidance" :class="{ complete: pendingCount === 0 }"><strong>{{ layoutBlocker ? '进入排版前需处理' : pendingCount ? pendingCount + ' 个空间建议复核' : '空间可用于排版' }}</strong><p>{{ layoutBlocker || (pendingCount ? '复核提示不影响继续排版。发现尺寸、用途或边界不符，可直接修改，无需逐个确认。' : '可直接进入家具排版，发现不符可随时返回修改。') }}</p></div>
  </aside>
</template>
