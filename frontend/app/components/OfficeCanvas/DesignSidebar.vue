<script setup lang="ts">
import { money } from '~/utils/helpers'

const store = useOfficeStore()
const collapsed = ref(false)

const zone = computed(() => store.zones.find((z) => z.id === store.designZoneId) || null)
const hasFurniture = computed(() => zone.value ? store.furnitureForZone(zone.value.id).length > 0 : false)
const layoutConfirmed = computed(() => zone.value ? store.layoutConfirmedForZone(zone.value.id) : false)

const activeTab = ref<'products' | 'combos'>('products')

const categories = [
  { key: 'all', label: '全部' },
  { key: 'workstation', label: '工位' },
  { key: 'chair', label: '座椅' },
  { key: 'meeting', label: '会议' },
  { key: 'sofa', label: '沙发' },
  { key: 'desk', label: '桌台' },
  { key: 'cabinet', label: '储物' },
]
const activeCategory = ref('all')

const filteredProducts = computed(() => {
  const z = zone.value
  let list = store.products.filter((p) => !z || p.uses.includes(z.zoneType) || (p.kind === 'other' && p.uses.length === 1 && p.uses[0] === 'OTHER'))
  if (activeCategory.value !== 'all') {
    list = list.filter((p) => p.kind === activeCategory.value)
  }
  return list
})

const filteredCombos = computed(() => {
  const z = zone.value
  const allCombos = store.combos.length ? store.combos : []
  return allCombos.filter((c) => !z || c.uses.includes(z.zoneType))
})

function comboProductNames(combo: any) {
  const allProducts = store.products.length ? store.products : []
  return combo.items.map((item: any) => {
    const p = allProducts.find((x) => x.id === item.productId)
    return `${p?.name || item.productId} × ${item.qty}`
  }).join('、')
}

function addCombo(comboId: string) {
  store.addComboToCart(comboId)
}

function backToOverview() {
  store.setCanvasMode('overview')
}
</script>

<template>
  <div :class="['flex-none bg-white border border-line rounded-2xl shadow-panel flex flex-col overflow-hidden transition-all duration-300', collapsed ? 'w-12' : 'w-[360px]']">
    <div v-show="!collapsed" class="w-[360px] h-full flex flex-col">
      <!-- Header -->
      <div class="relative px-4 py-4 border-b border-line flex-none">
        <button class="absolute top-3 right-3 p-1 rounded hover:bg-[#f7f3ef] text-muted" title="收起侧边栏" @click="collapsed = true">
          <AppIcon name="arrowRight" size="4" />
        </button>
        <button class="inline-flex items-center gap-1 text-xs text-bronze hover:underline mb-2" @click="backToOverview">
          <AppIcon name="arrowLeft" size="3.5" />
          <span>返回空间总览</span>
        </button>
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="zone" size="4" />
          <span>{{ zone?.name || '未选择空间' }}</span>
        </div>
        <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#f4eee5] text-[#946c45]">单空间设计</span>
      </div>
      <p class="text-xs text-muted mt-1.5">
        {{ zone ? (zone.seats ? '目标人数：' + zone.seats + ' 人' : '功能区域') : '请在总览中选择一个功能区' }}
      </p>
    </div>

    <!-- Zone info -->
    <div v-if="zone" class="px-4 py-3 bg-[#f9f7f4] border-b border-line flex-none">
      <div class="flex justify-between text-xs text-[#78736d] my-1">
        <span>功能类型</span>
        <strong class="text-[#383432]">{{ zone.zoneType }}</strong>
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
          <span :class="['text-[10px] px-1.5 py-0.5 rounded font-medium', layoutConfirmed ? 'bg-[#e6f5ee] text-green' : hasFurniture ? 'bg-[#fff8e6] text-[#a67c2e]' : 'bg-[#fff0f0] text-[#a65e4c]']">
            {{ layoutConfirmed ? '排版已确认' : hasFurniture ? '待确认排版' : '待排版' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Layout pending: guidance -->
    <div v-if="!layoutConfirmed" class="flex-1 overflow-auto p-4 flex flex-col gap-4">
      <div class="text-center py-8">
        <div class="w-14 h-14 mx-auto rounded-2xl bg-[#f6f0e8] text-bronze grid place-items-center mb-3">
          <AppIcon name="cube" size="7" />
        </div>
        <h3 class="text-sm font-bold text-[#5b5751] mb-1">先完成空间排版</h3>
        <p class="text-xs text-muted leading-relaxed">
          在左侧选择单位模块并设置尺寸和间距，<br>一键自动排版或应用选定方式后生成布局。
        </p>
      </div>

      <div class="bg-[#f9f7f4] rounded-xl p-3.5 text-xs text-[#78736d] leading-relaxed">
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
        <button class="w-full px-4 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze/50 cursor-not-allowed flex items-center justify-center gap-1.5" disabled>
          <AppIcon name="check" size="3.5" />
          <span>确认排版后解锁推荐产品</span>
        </button>
      </div>
    </div>

    <!-- Layout confirmed: full product catalog -->
    <template v-else>
      <div class="px-4 py-3 border-b border-line flex-none">
        <div class="flex items-center gap-2 text-[15px] font-bold mb-2">
          <AppIcon name="cube" size="4" />
          <span>推荐产品</span>
        </div>
        <div class="flex gap-2 mb-2">
          <button
            :class="['px-2.5 py-1 rounded-lg text-[10px] transition', activeTab === 'products' ? 'bg-bronze text-white' : 'bg-[#f7f4f0] text-[#746c64] hover:bg-[#efe9e2]']"
            @click="activeTab = 'products'"
          >
            单品
          </button>
          <button
            :class="['px-2.5 py-1 rounded-lg text-[10px] transition', activeTab === 'combos' ? 'bg-bronze text-white' : 'bg-[#f7f4f0] text-[#746c64] hover:bg-[#efe9e2]']"
            @click="activeTab = 'combos'"
          >
            搭配组合
          </button>
        </div>
        <div v-if="activeTab === 'products'" class="flex gap-1.5 flex-wrap">
          <button
            v-for="c in categories"
            :key="c.key"
            :class="[
              'px-2 py-1 rounded-lg text-[10px] transition',
              activeCategory === c.key ? 'bg-bronze text-white' : 'bg-[#f7f4f0] text-[#746c64] hover:bg-[#efe9e2]',
            ]"
            @click="activeCategory = c.key"
          >
            {{ c.label }}
          </button>
        </div>
      </div>

      <div v-if="activeTab === 'products'" class="flex-1 overflow-auto p-3 flex flex-col gap-2">
        <div v-for="p in filteredProducts" :key="p.id" class="flex gap-2.5 p-2.5 border border-line rounded-xl">
          <div class="w-[72px] h-[72px] rounded-lg bg-gradient-to-br from-[#f2ede5] to-[#e5d9ca] grid place-items-center flex-none p-1.5">
            <AgentProductArt :kind="p.kind" />
          </div>
          <div class="flex-1 min-w-0 flex flex-col gap-1">
            <div class="text-xs font-bold">{{ p.name }}</div>
            <div class="text-[10px] text-muted">{{ p.desc }}</div>
            <div class="text-[10px] text-muted">{{ p.variant }}</div>
            <div class="mt-auto flex items-center justify-between">
              <div class="text-sm text-[#9d6a3a] font-bold">{{ money(p.price) }}</div>
              <button class="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold bg-[#f7f0e8] text-bronze-dark border border-[#efe4d7] hover:bg-[#f3e8dc]" @click="store.addToCart(p.id)">
                <AppIcon name="plus" size="2.5" />
                <span>加入方案</span>
              </button>
            </div>
          </div>
        </div>
        <div v-if="!filteredProducts.length" class="text-center text-xs text-muted py-8">
          该分类下暂无适配产品
        </div>
      </div>

      <div v-else class="flex-1 overflow-auto p-3 flex flex-col gap-2">
        <div v-for="c in filteredCombos" :key="c.id" class="flex flex-col gap-2 p-3 border border-line rounded-xl">
          <div class="flex items-center justify-between">
            <div class="text-xs font-bold">{{ c.name }}</div>
            <span class="text-[10px] px-1.5 py-0.5 rounded bg-[#f4eee5] text-[#946c45]">{{ c.style || '现代' }}</span>
          </div>
          <div class="text-[10px] text-muted">{{ c.desc }}</div>
          <div class="text-[10px] text-[#78736d]">包含：{{ comboProductNames(c) }}</div>
          <div class="mt-auto flex items-center justify-between">
            <div class="text-sm text-[#9d6a3a] font-bold">{{ money(c.price) }}</div>
            <button class="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold bg-[#f7f0e8] text-bronze-dark border border-[#efe4d7] hover:bg-[#f3e8dc]" @click="addCombo(c.id)">
              <AppIcon name="plus" size="2.5" />
              <span>加入方案</span>
            </button>
          </div>
        </div>
        <div v-if="!filteredCombos.length" class="text-center text-xs text-muted py-8">
          当前空间暂无适配组合
        </div>
      </div>

      <div class="px-4 py-3 border-t border-line bg-[#fefcfb] flex-none">
        <div class="flex justify-between text-xs text-[#78736d] my-1">
          <span>已选商品</span>
          <strong class="text-[#383432]">{{ store.cart.length }}</strong>
        </div>
        <div class="flex justify-between text-xs text-[#78736d] my-1">
          <span>参考金额</span>
          <strong class="text-lg text-[#9c7047]">{{ money(store.totalPrice) }}</strong>
        </div>
        <NuxtLink to="/proposal" class="inline-flex items-center justify-center gap-1 w-full text-center px-4 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark">
          <span>生成整体方案</span>
          <AppIcon name="arrowRight" size="3.5" />
        </NuxtLink>
      </div>
    </template>
    </div>

    <!-- Collapsed strip -->
    <div v-show="collapsed" class="w-12 h-full flex flex-col items-center py-3">
      <button class="p-1.5 rounded-lg hover:bg-[#f7f3ef] text-muted mb-4" title="展开侧边栏" @click="collapsed = false">
        <AppIcon name="arrowLeft" size="5" />
      </button>
      <div class="text-[10px] text-muted tracking-widest" style="writing-mode: vertical-rl;">
        选品
      </div>
    </div>
  </div>
</template>
