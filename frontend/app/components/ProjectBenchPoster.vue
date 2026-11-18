<script setup lang="ts">
function handleJump() {
  // TODO: 跳转目标待确定
}
</script>

<template>
  <section class="border border-line bg-white" aria-label="海报">
    <img
      src="https://img.lubanyuan.com.cn/d/file/p/zixun/guandian/2026/01-21/1768976818_897.jpg"
      alt="海报"
      class="block h-auto w-full"
      loading="lazy"
    >
    <button
      type="button"
      class="block w-full bg-bronze py-2.5 text-sm font-medium text-white transition hover:bg-bronze-dark"
      @click="handleJump"
    >
      查看演示
    </button>
  </section>
</template>
