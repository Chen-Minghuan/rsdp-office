<script setup lang="ts">
import { money } from '~/utils/helpers'

const store = useOfficeStore()

const zoneSetSize = computed(() => new Set(store.cart.map((c) => c.zoneId || 'none')).size)

const budgetRatio = computed(() => {
  if (!store.project.budget) return 0
  return Math.min(100, (store.totalPrice / store.project.budget) * 100)
})

const overBudget = computed(() => store.project.budget && store.totalPrice > store.project.budget)

const groupedCart = computed(() => {
  const map = new Map<string, { name: string; items: typeof store.cart }>()
  for (const c of store.cart) {
    const existing = map.get(c.zoneId || 'none')
    if (existing) {
      existing.items.push(c)
    } else {
      map.set(c.zoneId || 'none', { name: c.zoneName, items: [c] })
    }
  }
  return Array.from(map.entries()).map(([zoneId, group]) => ({
    zoneId,
    ...group,
    total: group.items.reduce((sum, c) => {
      const p = store.products.find((x) => x.id === c.productId)
      return sum + (p?.price || 0) * c.qty
    }, 0),
  }))
})

const zoneBudgets = computed(() => {
  return groupedCart.value.map((z) => ({
    ...z,
    ratio: store.totalPrice ? (z.total / store.totalPrice) * 100 : 0,
  }))
})

function product(c: any) {
  return store.products.find((p) => p.id === c.productId)
}
</script>

<template>
  <div class="flex flex-col h-full gap-4">
    <div class="flex items-start justify-between flex-none">
      <div>
        <h1 class="text-[25px] font-bold tracking-tight mb-2">整体方案与清单</h1>
        <p class="text-xs text-muted">汇总所有已确认空间的家具配置、数量和报价，验证最终交付体验。</p>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="!store.cart.length" class="flex-1 min-h-0 bg-white border border-line rounded-2xl shadow-panel grid place-items-center p-8">
      <div class="text-center max-w-md">
        <div class="w-16 h-16 mx-auto mb-4 rounded-2xl bg-[#f6f0e8] text-bronze grid place-items-center">
          <AppIcon name="table" size="8" />
        </div>
        <h2 class="text-lg font-bold mb-2">尚未生成方案</h2>
        <p class="text-sm text-muted leading-relaxed mb-5">
          整体方案需要基于已确认的画布空间和已选商品。请先完成空间总览与单空间设计。
        </p>
        <NuxtLink to="/canvas" class="inline-flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-semibold text-white bg-bronze hover:bg-bronze-dark">
          <span>前往空间总览</span>
          <AppIcon name="arrowRight" size="4" />
        </NuxtLink>
      </div>
    </div>

    <div v-else class="flex-1 min-h-0 flex gap-4">
      <!-- Left -->
      <div class="flex-1 min-w-0 bg-white border border-line rounded-2xl shadow-panel p-6 overflow-auto">
        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2 text-[15px] font-bold">
            <AppIcon name="documentText" size="4" />
            <span>办公项目方案清单</span>
          </div>
          <span class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] bg-[#e7f1ff] text-[#437eae]">
            <AppIcon name="info" size="3" />
            <span>仅用于原型演示</span>
          </span>
        </div>
        <p class="text-xs text-muted mb-6">以下商品、规格和金额为示例，非 RSDP 实时供应链价格及正式报价。</p>

        <div class="grid grid-cols-4 gap-2.5 mb-6">
          <div class="bg-[#f9f7f4] rounded-xl p-3.5">
            <div class="flex items-center gap-1.5 text-xs text-muted">
              <AppIcon name="zone" size="3" />
              <span>已设计空间</span>
            </div>
            <div class="text-[23px] font-extrabold mt-2">{{ zoneSetSize }}</div>
          </div>
          <div class="bg-[#f9f7f4] rounded-xl p-3.5">
            <div class="flex items-center gap-1.5 text-xs text-muted">
              <AppIcon name="cart" size="3" />
              <span>已选商品行</span>
            </div>
            <div class="text-[23px] font-extrabold mt-2">{{ store.cart.length }}</div>
          </div>
          <div class="bg-[#f9f7f4] rounded-xl p-3.5 col-span-2">
            <div class="flex items-center gap-1.5 text-xs text-muted">
              <AppIcon name="money" size="3" />
              <span>参考总金额</span>
            </div>
            <div class="text-[23px] font-extrabold mt-2 text-[#9b7046]">{{ money(store.totalPrice) }}</div>
          </div>
        </div>

        <h3 class="flex items-center gap-2 text-[15px] font-bold mb-3">
          <AppIcon name="table" size="4" />
          <span>分区商品清单</span>
        </h3>

        <div v-if="!store.cart.length" class="text-center text-[#999] py-14 border border-dashed border-line rounded-xl">
          <AppIcon name="cart" size="6" class="mx-auto mb-2 text-muted" />
          <span>还没有加入商品。请返回单空间设计页选择家具。</span>
        </div>

        <div v-for="group in groupedCart" :key="group.zoneId" class="mb-5 overflow-auto border border-line rounded-xl">
          <div class="bg-[#f7f5f2] px-3 py-2.5 flex items-center justify-between">
            <div class="flex items-center gap-2 text-xs font-bold text-[#5b5751]">
              <AppIcon name="zone" size="3.5" />
              <span>{{ group.name }}</span>
            </div>
            <span class="text-xs text-[#9c7047] font-bold">{{ money(group.total) }}</span>
          </div>
          <table class="w-full text-xs">
            <thead>
              <tr class="bg-[#faf9f7] text-[#77706a]">
                <th class="text-left px-3 py-2">演示商品</th>
                <th class="text-left px-3 py-2">规格 / Variant</th>
                <th class="text-left px-3 py-2">数量</th>
                <th class="text-right px-3 py-2">参考单价</th>
                <th class="text-right px-3 py-2">小计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in group.items" :key="c.id" class="border-b border-[#f0ece8]">
                <td class="px-3 py-2.5">
                  <div class="flex items-center gap-2">
                    <div class="w-8 h-8 rounded bg-gradient-to-br from-[#f2ede5] to-[#e5d9ca] p-1">
                      <AgentProductArt v-if="product(c)" :kind="product(c)!.kind" />
                    </div>
                    <div>
                      <strong>{{ product(c)?.name }}</strong>
                      <div class="text-[10px] text-muted">{{ c.productId }}</div>
                    </div>
                  </div>
                </td>
                <td class="px-3 py-2.5">{{ product(c)?.variant }}</td>
                <td class="px-3 py-2.5">
                  <div class="flex items-center gap-1.5">
                    <button class="w-5 h-6 rounded border border-[#e7dfd7] bg-white text-[#947252] grid place-items-center" @click="store.changeQty(c.id, -1)">
                      <AppIcon name="minus" size="3" />
                    </button>
                    <span>{{ c.qty }}</span>
                    <button class="w-5 h-6 rounded border border-[#e7dfd7] bg-white text-[#947252] grid place-items-center" @click="store.changeQty(c.id, 1)">
                      <AppIcon name="plus" size="3" />
                    </button>
                  </div>
                </td>
                <td class="px-3 py-2.5 text-right">{{ money(product(c)?.price || 0) }}</td>
                <td class="px-3 py-2.5 text-right">{{ money((product(c)?.price || 0) * c.qty) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="mt-5 flex items-start gap-2 bg-[#f6f0e8] border border-[#efe5d7] rounded-lg p-3.5 text-xs text-[#8b7055] leading-relaxed">
          <AppIcon name="info" size="3.5" class="mt-0.5 flex-none" />
          <span>正式项目还需要锁定真实 Variant、RSKU、供应商、MOQ、交期、税率及运费安装费用等信息，本应用不生成具有法律效力的报价单。</span>
        </div>
      </div>

      <!-- Right -->
      <aside class="w-80 flex-none bg-white border border-line rounded-2xl shadow-panel p-6 flex flex-col gap-4 overflow-hidden">
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="documentText" size="4" />
          <span>项目概要</span>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-2">
          <span>项目名称</span>
          <strong class="text-[#383432]">{{ store.project.name }}</strong>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-2">
          <span>预计人数</span>
          <strong class="text-[#383432]">{{ store.project.headcount || '待定' }} 人</strong>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-2">
          <span>整体预算</span>
          <strong class="text-[#383432]">{{ money(store.project.budget) }}</strong>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-2">
          <span>已设计空间</span>
          <strong class="text-[#383432]">{{ zoneSetSize }} 个</strong>
        </div>
        <div class="h-px bg-[#f0ece8]" />
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="chartPie" size="4" />
          <span>预算参考</span>
        </div>
        <div class="h-2 rounded-full bg-[#f1e6da] overflow-hidden">
          <div class="h-full rounded-full transition-all" :style="{ width: budgetRatio + '%', backgroundColor: overBudget ? '#d27664' : '#a77b51' }" />
        </div>
        <div class="text-xs text-muted">
          <span v-if="store.project.budget">已占预算约 {{ budgetRatio.toFixed(1) }}% · 剩余 {{ money(store.project.budget - store.totalPrice) }}</span>
          <span v-else>预算未填写，可返回上传页设置</span>
        </div>

        <div v-if="zoneBudgets.length" class="h-px bg-[#f0ece8]" />
        <div v-if="zoneBudgets.length" class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="zone" size="4" />
          <span>预算分布（按空间）</span>
        </div>
        <div v-if="zoneBudgets.length" class="flex flex-col gap-2">
          <div v-for="z in zoneBudgets" :key="z.zoneId" class="flex items-center justify-between text-xs">
            <span class="text-muted">{{ z.name }}</span>
            <div class="flex items-center gap-2">
              <span>{{ money(z.total) }}</span>
              <span class="text-[10px] text-muted w-10 text-right">{{ z.ratio.toFixed(0) }}%</span>
            </div>
          </div>
        </div>

        <div class="h-px bg-[#f0ece8]" />
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="arrow" size="4" />
          <span>下一步</span>
        </div>
        <button class="inline-flex items-center justify-center gap-1 w-full px-4 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark" @click="store.exportCsv">
          <AppIcon name="documentText" size="3.5" />
          <span>导出整体方案 CSV</span>
        </button>
        <NuxtLink to="/canvas?mode=design" class="inline-flex items-center justify-center gap-1 w-full text-center px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]">
          <AppIcon name="zone" size="3.5" />
          <span>返回继续设计</span>
        </NuxtLink>
        <NuxtLink to="/canvas" class="inline-flex items-center justify-center gap-1 w-full text-center px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]">
          <AppIcon name="map" size="3.5" />
          <span>查看空间总览</span>
        </NuxtLink>
        <div class="mt-auto flex items-start gap-2 text-[11px] text-[#a29b94] leading-relaxed">
          <AppIcon name="info" size="3.5" class="mt-0.5 flex-none" />
          <span>正式联通 RSDP 后，将使用已确认 RSPU/Variant/RSKU、报价权限及订单服务。</span>
        </div>
      </aside>
    </div>
  </div>
</template>
