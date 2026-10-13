<script setup lang="ts">
import { money } from '~/utils/helpers'

const store = useOfficeStore()
const show = defineModel<boolean>('show')

const brief = computed(() => {
  if (!store.submitted) return null
  return store.submitted
})

function confirm() {
  show.value = false
  navigateTo('/agent')
}
</script>

<template>
  <div v-if="show" class="fixed inset-0 bg-[#29251e7a] z-50 flex items-center justify-center p-5" @click.self="show = false">
    <div class="w-full max-w-[760px] max-h-[88vh] overflow-auto bg-white rounded-2xl shadow-2xl">
      <div class="flex items-center justify-between px-6 py-5 border-b border-line">
        <div>
          <h2 class="text-lg font-bold">提交画布需求给营销 Agent</h2>
          <p class="text-xs text-muted mt-1.5">检查提取内容，确认后生成独立需求快照，进入选品工作台。</p>
        </div>
        <button class="text-xs px-2 py-1 hover:bg-[#f7f3ef] rounded" @click="show = false">✕</button>
      </div>
      <div v-if="brief" class="p-6 grid gap-4">
        <div class="bg-[#f6f0e8] border border-[#efe5d7] rounded-lg p-3.5 text-xs text-[#8b7055] leading-relaxed">
          此处只读取用户输入与画布已填写信息。演示底图的绘图坐标并不是实际毫米尺寸，精确布局仍需校准确认。
        </div>
        <div class="grid grid-cols-2 gap-2.5">
          <div class="bg-[#f8f6f3] rounded-lg p-3">
            <span class="block text-[10px] text-[#9c9188] mb-1.5">办公项目</span>
            <strong class="text-[15px]">{{ brief.projectContext.name }}</strong>
          </div>
          <div class="bg-[#f8f6f3] rounded-lg p-3">
            <span class="block text-[10px] text-[#9c9188] mb-1.5">预计办公人数</span>
            <strong class="text-[15px]">{{ brief.projectContext.headcount || '待确认' }} 人</strong>
          </div>
          <div class="bg-[#f8f6f3] rounded-lg p-3">
            <span class="block text-[10px] text-[#9c9188] mb-1.5">整体预算</span>
            <strong class="text-[15px]">{{ money(brief.projectContext.budgetMax) }}</strong>
          </div>
          <div class="bg-[#f8f6f3] rounded-lg p-3">
            <span class="block text-[10px] text-[#9c9188] mb-1.5">画布功能区</span>
            <strong class="text-[15px]">{{ brief.zones.length }} 个</strong>
          </div>
        </div>
        <div>
          <strong class="text-sm">已识别的明确标注</strong>
          <div class="flex flex-wrap gap-2 mt-2.5">
            <span v-for="z in brief.zones" :key="z.zoneId" class="px-1.5 py-1 rounded text-[10px] bg-[#e7f1ff] text-[#437eae]">
              {{ z.name }}{{ z.targetSeats ? ' · ' + z.targetSeats + ' 人' : '' }}
            </span>
            <span v-if="!brief.zones.length" class="text-xs text-muted">尚无功能区</span>
          </div>
        </div>
        <div>
          <strong class="text-sm">待确认问题</strong>
          <p class="text-xs text-muted leading-relaxed mt-2">{{ brief.pendingQuestions.join('；') }}</p>
        </div>
        <details>
          <summary class="text-bronze cursor-pointer text-xs">查看传递给 Agent 的结构化 JSON</summary>
          <pre class="mt-2 max-h-[190px] overflow-auto text-[11px] bg-[#2d3135] text-[#e9e9e5] rounded-xl p-4 whitespace-pre-wrap break-words">{{ JSON.stringify(brief, null, 2) }}</pre>
        </details>
      </div>
      <div class="flex items-center justify-end gap-2 px-6 py-4 border-t border-line">
        <button class="px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" @click="show = false">继续修改画布</button>
        <button class="px-5 py-2 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark" @click="confirm">确认并进入 Agent　→</button>
      </div>
    </div>
  </div>
</template>
