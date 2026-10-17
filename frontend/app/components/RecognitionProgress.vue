<script setup lang="ts">
import type { FloorPlanProcessing } from '~/types/floorPlan'
const props = defineProps<{ progress?: FloorPlanProcessing | null; pending: boolean }>()
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | undefined
onMounted(() => { ticker = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => clearInterval(ticker))
const labels = { QUEUED: '等待处理', PREPARING: '正在处理图片', RECOGNIZING: '正在识别空间', REFINING: '正在校正轮廓', SAVING: '正在保存结果', DONE: '识别完成', FAILED: '识别未完成' }
const elapsed = computed(() => {
  const value = props.progress
  if (!value) return null
  const started = value.startedAt ? Date.parse(value.startedAt) : NaN
  return props.pending && Number.isFinite(started) ? Math.max(value.elapsedMs || 0, now.value - started) : value.elapsedMs
})
const seconds = (ms: number) => `${(ms / 1000).toFixed(1)} 秒`
const steps = computed(() => {
  const timings = props.progress?.timingsMs || {}
  return [
    { label: '处理图片', ms: timings.prepare },
    { label: '识别空间', ms: ['REFINING', 'SAVING', 'DONE'].includes(props.progress?.stage || '') && timings.spaces !== undefined ? Math.max(timings.spaces, timings.labels || 0) : undefined },
    { label: '校正轮廓', ms: timings.contours },
  ]
})
</script>

<template>
  <section data-testid="recognition-progress" role="status" class="rounded-xl border border-line bg-[#f9f7f4] p-3 space-y-2 text-xs">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <strong>{{ progress ? labels[progress.stage] : '正在上传并创建识别任务…' }}</strong>
      <span v-if="elapsed != null" class="text-muted">{{ pending ? '已用时' : '总用时' }} {{ seconds(elapsed) }}</span>
    </div>
    <ol class="flex flex-wrap gap-x-4 gap-y-1 text-muted"><li v-for="step in steps" :key="step.label">{{ step.label }}<span v-if="step.ms != null"> ✓ {{ seconds(step.ms) }}</span></li></ol>
    <p v-if="progress?.stage === 'RECOGNIZING'" class="text-muted">底图已就绪，空间识别在后台继续，完成后可应用到画布。</p>
    <p v-if="progress?.timingsMs?.labels != null" class="text-muted">房间名称定位：{{ seconds(progress.timingsMs.labels) }}（与空间识别并行）</p>
    <p v-if="progress?.cacheHit" class="text-bronze">已复用相同图纸、页码和处理参数的标准底图</p>
  </section>
</template>
