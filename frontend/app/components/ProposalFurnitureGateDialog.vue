<script setup lang="ts">
import type { ProposalFurnitureCartMismatch } from '~/utils/proposalFurnitureGate'

defineProps<{ mismatches: ProposalFurnitureCartMismatch[] }>()
const emit = defineEmits<{ close: [] }>()

function formatSize(widthMm: number, depthMm: number) {
  return `${widthMm.toLocaleString()} × ${depthMm.toLocaleString()} mm`
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 grid place-items-center bg-black/30 p-4" @click.self="emit('close')" @keydown.esc.stop.prevent="emit('close')">
      <section role="dialog" aria-modal="true" aria-labelledby="proposal-furniture-gate-title" class="w-full max-w-xl rounded-2xl bg-white p-6 shadow-2xl">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h2 id="proposal-furniture-gate-title" class="text-lg font-bold">请先选择家具</h2>
            <p class="mt-1 text-xs leading-5 text-muted">以下画布家具尚未在对应区域的购物车中选择商品：</p>
          </div>
          <button type="button" aria-label="关闭缺失家具提示" class="rounded-lg p-2 text-muted hover:bg-cream" @click="emit('close')"><AppIcon name="close" /></button>
        </div>

        <div class="mt-5 max-h-[50vh] space-y-4 overflow-y-auto">
          <section v-for="mismatch in mismatches" :key="mismatch.zoneId" class="rounded-xl border border-line bg-[#faf8f5] p-4">
            <div class="flex items-baseline justify-between gap-3">
              <h3 class="text-sm font-semibold text-ink">{{ mismatch.zoneName }}</h3>
              <span class="shrink-0 text-[11px] text-muted">画布 {{ mismatch.furnitureCount }} 件 · 购物车 {{ mismatch.cartQuantity }} 件</span>
            </div>
            <ul v-if="mismatch.missing.length" class="mt-3 space-y-2">
              <li v-for="item in mismatch.missing" :key="`${item.name}-${item.widthMm}-${item.depthMm}`" class="flex items-center justify-between gap-4 text-xs">
                <span class="min-w-0 truncate text-ink">{{ item.name }}</span>
                <span class="shrink-0 text-muted">{{ formatSize(item.widthMm, item.depthMm) }} × {{ item.quantity }}</span>
              </li>
            </ul>
            <p v-else-if="mismatch.cartExcessQuantity" class="mt-3 text-xs leading-5 text-muted">购物车比画布多出 {{ mismatch.cartExcessQuantity }} 件商品，请返回该区域核对。</p>
          </section>
        </div>

        <div class="mt-5 flex justify-end">
          <button type="button" class="rounded-xl bg-bronze px-4 py-2 text-sm font-semibold text-white hover:bg-bronze-dark" @click="emit('close')">返回选品</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
