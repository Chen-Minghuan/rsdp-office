<script setup lang="ts">
import { money } from '~/utils/helpers'
import { ZONE_TYPES } from '~/utils/sample'

const store = useOfficeStore()

const zone = computed(() => store.zones.find((z) => z.id === store.designZoneId) || null)
const hasFurniture = computed(() => zone.value ? store.furnitureForZone(zone.value.id).length > 0 : false)
const layoutConfirmed = computed(() => zone.value ? store.layoutConfirmedForZone(zone.value.id) : false)
const layoutReviewNotice = computed(() => layoutConfirmed.value && zone.value ? store.layoutReviewWarning(zone.value.id) : null)

const selectedId = defineModel<string | null>('selectedId')
defineProps<{ allowedRspuIds?: string[]; showFloorSummary: boolean }>()
const zoneTypeLabel = computed(() => ZONE_TYPES.find(([value]) => value === zone.value?.zoneType)?.[1] || '其他')
</script>

<template>
  <div class="flex min-h-0 w-full flex-1 flex-col overflow-hidden bg-white">
    <!-- 兜底滚动层：高度充足时内容不溢出、本层无滚动条，仅商品列表自滚，表现与原布局一致；
         视口不足（小屏或浏览器放大）时本层出现滚动条，上方信息区可滚走，结算条在层外钉底 -->
    <div class="flex-1 min-h-0 overflow-y-auto flex flex-col" data-testid="sidebar-fallback-scroll">

    <!-- Zone info -->
    <div v-if="zone" class="px-4 py-3 bg-bg border-b border-line flex-none">
      <div class="flex justify-between text-xs text-[#78736d] my-1">
        <span>功能类型</span>
        <strong class="text-[#383432]">{{ zoneTypeLabel }}</strong>
      </div>
      <div class="flex justify-between text-xs text-[#78736d] my-1">
        <span>尺寸（{{ zone.reviewUnit === 'mm' ? '毫米' : '待校准' }}）</span>
        <strong class="text-[#383432]">{{ Math.round(zone.w) }} × {{ Math.round(zone.h) }}</strong>
      </div>
      <div class="flex justify-between text-xs text-[#78736d] my-1">
        <span>区域说明</span>
        <strong class="text-[#383432]">{{ zone.note || '无' }}</strong>
      </div>
      <div class="mt-2 pt-2 border-t border-[#efe5d7]">
        <div class="flex items-center justify-between">
          <span class="text-xs text-[#78736d]">布局状态</span>
          <span :class="['text-[10px] px-1.5 py-0.5 rounded-none font-medium', layoutConfirmed ? 'bg-[#e6f5ee] text-green' : hasFurniture ? 'bg-[#fff8e6] text-[#a67c2e]' : 'bg-[#fff0f0] text-[#a65e4c]']">
            {{ layoutConfirmed ? '排版已确认' : hasFurniture ? '待确认排版' : '待排版' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Layout pending: guidance -->
    <div v-if="!layoutConfirmed" class="flex-1 overflow-auto p-4 flex flex-col gap-4">
      <div class="text-center py-8">
        <div class="w-14 h-14 mx-auto rounded-none bg-[#f6f0e8] text-bronze grid place-items-center mb-3">
          <AppIcon name="cube" size="7" />
        </div>
        <h3 class="text-sm font-bold text-[#5b5751] mb-1">先完成空间排版</h3>
        <p class="text-xs text-muted leading-relaxed">
 在“自动布局”生成方案，或在“产品选型”手动排版，<br>确认后均可按模块尺寸匹配真实商品。
        </p>
      </div>

      <div class="bg-bg rounded-none p-3.5 text-xs text-[#78736d] leading-relaxed">
        <div class="font-semibold text-[#5b5751] mb-2 flex items-center gap-1.5">
          <AppIcon name="info" size="3.5" />
          <span>排版步骤</span>
        </div>
        <ul class="space-y-1.5">
          <li class="flex items-start gap-2">
            <AppIcon name="check" size="3" class="mt-0.5 text-green" />
            <span>选择适配该空间的单位模块</span>
          </li>
          <li class="flex items-start gap-2">
            <AppIcon name="check" size="3" class="mt-0.5 text-green" />
            <span>确认或修改模块宽深尺寸</span>
          </li>
          <li class="flex items-start gap-2">
            <AppIcon name="check" size="3" class="mt-0.5 text-green" />
            <span>调整排间、列间和离墙距离，选择自动或常用方式</span>
          </li>
        </ul>
      </div>

      <div class="mt-auto">
        <button class="w-full px-4 py-2.5 rounded-none text-xs font-semibold text-white bg-bronze/50 cursor-not-allowed flex items-center justify-center gap-1.5" disabled>
          <AppIcon name="check" size="3.5" />
          <span>确认排版后解锁推荐产品</span>
        </button>
      </div>
    </div>

    <!-- Layout confirmed: real SKU recommendations for current module footprints -->
    <template v-else>
      <p v-if="layoutReviewNotice" class="border-b border-[#f0d9b3] bg-[#fff8e8] px-3 py-2 text-[10px] leading-relaxed text-[#8a6a2c]" data-testid="layout-review-notice">{{ layoutReviewNotice }}</p>
      <OfficeCanvasModuleProductRecommendations v-model:selected-id="selectedId" :allowed-rspu-ids="allowedRspuIds" />
    </template>
    </div>

    <!-- 结算条钉底：位于兜底滚动层之外，任何有效视口高度下保持可见，随时可进入方案 -->
    <div v-if="layoutConfirmed && showFloorSummary" class="px-4 py-3 border-t border-line bg-bg flex-none">
        <div class="flex justify-between text-xs text-[#78736d] my-1">
          <span>已选商品</span>
          <strong class="text-[#383432]">{{ store.cart.length }}</strong>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-1">
          <span>参考金额</span>
          <strong class="text-lg text-bronze-dark">{{ money(store.totalPrice) }}</strong>
        </div>
        <NuxtLink :to="{ path: '/proposal', query: { floorId: store.workspaceId } }" class="inline-flex items-center justify-center gap-1 w-full text-center px-4 py-2.5 rounded-none text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark">
          <span>生成整体方案</span>
          <AppIcon name="arrowRight" size="3.5" />
        </NuxtLink>
      </div>
  </div>
</template>
