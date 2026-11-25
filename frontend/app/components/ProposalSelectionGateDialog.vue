<script setup lang="ts">
import { proposalFurnitureSelectionRoute } from '~/utils/proposalFurnitureGate'

// 方案页选品门禁弹窗：中间件拦截跳转后写入不一致区域，点击区域跳回画布处理。
const { gate, close } = useProposalSelectionGate()
const store = useOfficeStore()
const dialog = ref<HTMLElement | null>(null)

watch(gate, async (value) => {
  if (!value) return
  await nextTick()
  dialog.value?.focus()
})

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'))
  if (!focusable.length) return
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function goToZone(zoneId: string) {
  const current = gate.value
  if (!current) return
  const target = proposalFurnitureSelectionRoute(current.floorId, zoneId, store.canEnterLayout)
  close()
  void navigateTo(target)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="gate" class="fixed inset-0 z-[80] flex items-center justify-center bg-black/30 p-4" data-testid="proposal-gate-backdrop" @click.self="close">
      <section
        ref="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="proposal-gate-title"
        data-testid="proposal-gate-dialog"
        tabindex="-1"
        class="gate-dialog"
        @keydown="onKeydown"
      >
        <header class="gate-header">
          <div>
            <span class="gate-kicker">SELECTION CHECK</span>
            <h2 id="proposal-gate-title">{{ gate.issues.length }} 个区域商品数量与画布不一致</h2>
            <p>请先处理以下区域的选品差异，再进入项目清单确认。</p>
          </div>
          <button type="button" class="gate-close" aria-label="关闭" @click="close"><AppIcon name="close" size="5" /></button>
        </header>
        <ul class="gate-list">
          <li v-for="issue in gate.issues" :key="issue.zoneId">
            <button type="button" class="gate-row" :data-testid="`proposal-gate-zone-${issue.zoneId}`" @click="goToZone(issue.zoneId)">
              <div class="gate-row-info">
                <strong>{{ issue.zoneName }}</strong>
                <div>家具 {{ issue.furnitureCount }} 件 · 清单 {{ issue.cartQuantity }} 件</div>
                <ul v-if="issue.missing.length" class="gate-missing">
                  <li v-for="item in issue.missing" :key="JSON.stringify([item.name, item.widthMm, item.depthMm])">
                    {{ item.name }} · {{ item.widthMm.toLocaleString() }} × {{ item.depthMm.toLocaleString() }} mm · 缺 {{ item.quantity }} 件
                  </li>
                </ul>
                <p v-if="issue.cartExcessQuantity">清单多出 {{ issue.cartExcessQuantity }} 件</p>
              </div>
              <span class="gate-row-action">{{ store.canEnterLayout ? '去选品' : '去复核空间' }}<AppIcon name="arrowRight" size="3.5" /></span>
            </button>
          </li>
        </ul>
        <footer class="gate-footer">
          <button type="button" class="gate-dismiss" @click="close">知道了</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.gate-dialog{display:flex;flex-direction:column;width:min(560px,100%);max-height:calc(100dvh - 48px);overflow:auto;border:1px solid var(--color-line);border-radius:18px;background:#fff;padding:22px;outline:none;box-shadow:0 24px 70px rgba(44,36,28,.24)}
.gate-header{display:flex;justify-content:space-between;gap:15px}
.gate-kicker{display:block;color:#aa7d50;letter-spacing:.16em;font-size:9px;font-weight:800;margin-bottom:4px}
.gate-header h2{font-size:17px;font-weight:800;letter-spacing:-.02em}
.gate-header p{font-size:11px;color:#958d84;margin-top:6px}
.gate-close{width:32px;height:32px;display:grid;place-items:center;border-radius:8px;color:#81776c;flex:none}
.gate-close:hover{background:#f6f1eb}
.gate-list{display:grid;gap:1px;margin-top:16px;background:var(--color-line);border:1px solid var(--color-line);border-radius:10px;overflow:hidden}
.gate-row{display:flex;width:100%;align-items:flex-start;justify-content:space-between;gap:.5rem 1rem;padding:12px 14px;background:white;text-align:left;font-size:.8rem;line-height:1.5;color:var(--color-muted)}
.gate-row:hover{background:var(--color-accent-tint)}
.gate-row-info strong{color:var(--color-ink)}
.gate-missing{margin-top:4px}
.gate-row-action{display:inline-flex;align-items:center;gap:4px;flex:none;margin-top:2px;font-size:11px;font-weight:700;color:var(--color-accent-ink);white-space:nowrap}
.gate-footer{display:flex;justify-content:flex-end;margin-top:16px}
.gate-dismiss{border:1px solid var(--color-line);border-radius:8px;background:#fff;color:#72675c;font-size:11px;font-weight:700;padding:9px 18px}
.gate-dismiss:hover{background:var(--color-bg)}
</style>
