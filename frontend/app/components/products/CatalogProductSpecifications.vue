<script setup lang="ts">
defineProps<{
  variants: {
    id: string
    name?: string | null
    dimensions?: string | null
    color?: string | null
    material?: string | null
    packageCount?: number | null
    packageVolumeCbm?: number | null
    grossWeight?: number | null
    status?: string
  }[]
  showLogistics?: boolean
}>()
</script>

<template>
  <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
    <div class="mb-4 flex flex-wrap items-end justify-between gap-2">
      <h2 class="text-base font-semibold">商品规格</h2>
      <span class="text-xs text-muted">{{ variants.length }} 个规格</span>
    </div>
    <div class="overflow-x-auto rounded-lg border border-line">
      <table class="w-full text-left text-sm" :class="showLogistics ? 'min-w-[740px]' : 'min-w-[380px]'">
        <thead class="bg-bg text-xs text-muted">
          <tr>
            <th class="px-4 py-3 font-medium">变体名称</th>
            <th class="px-4 py-3 font-medium">尺寸</th>
            <th class="px-4 py-3 font-medium">颜色</th>
            <th class="px-4 py-3 font-medium">材质</th>
            <template v-if="showLogistics">
              <th class="px-4 py-3 text-right font-medium">包数</th>
              <th class="px-4 py-3 text-right font-medium">体积(CBM)</th>
              <th class="px-4 py-3 text-right font-medium">重量(原表值)</th>
              <th class="px-4 py-3 font-medium">状态</th>
            </template>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!variants.length">
            <td :colspan="showLogistics ? 8 : 4" class="px-4 py-8 text-center text-sm text-muted">暂无规格信息</td>
          </tr>
          <tr v-for="variant in variants" :key="variant.id" class="border-t border-line">
            <td class="px-4 py-3">{{ variant.name || '—' }}</td>
            <td class="px-4 py-3 text-xs text-muted">{{ variant.dimensions || '—' }}</td>
            <td class="px-4 py-3 text-xs text-muted">{{ variant.color || '—' }}</td>
            <td class="px-4 py-3 text-xs text-muted">{{ variant.material || '—' }}</td>
            <template v-if="showLogistics">
              <td class="px-4 py-3 text-right tabular-nums">{{ variant.packageCount ?? '—' }}</td>
              <td class="px-4 py-3 text-right tabular-nums">{{ variant.packageVolumeCbm ?? '—' }}</td>
              <td class="px-4 py-3 text-right tabular-nums">{{ variant.grossWeight ?? '—' }}</td>
              <td class="px-4 py-3 text-xs">
                <span class="rounded px-1.5 py-0.5" :class="variant.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ variant.status === 'active' ? '有效' : variant.status }}</span>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
