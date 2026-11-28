<script setup lang="ts">
const { pending, answerGate } = useRecognitionGuard()
</script>

<template>
  <div v-if="pending" class="absolute inset-0 z-30" data-testid="recognition-gate-dialog" @pointerdown.stop>
    <section
      role="alertdialog"
      aria-modal="true"
      aria-label="正在识别原图家具"
      class="absolute left-1/2 top-1/2 w-80 -translate-x-1/2 -translate-y-1/2 border border-line bg-white p-4 text-xs shadow-panel"
    >
      <p class="font-bold text-[#403a33]">「{{ pending.zoneName }}」正在识别原图家具</p>
      <p class="mt-1.5 text-[11px] leading-relaxed text-muted">现在改动当前空间的排版会使本次识别结果失效。可以取消识别后继续，或保持识别、等识别完成再操作。</p>
      <div class="mt-3 flex gap-2">
        <button type="button" data-testid="recognition-gate-keep" class="flex-1 border border-line px-2 py-1.5 text-[11px] text-muted hover:bg-[#f3efe9]" @click="answerGate(false)">保持识别</button>
        <button type="button" data-testid="recognition-gate-cancel" class="flex-1 bg-accent px-2 py-1.5 text-[11px] font-semibold text-white" @click="answerGate(true)">取消识别并继续</button>
      </div>
    </section>
  </div>
</template>
