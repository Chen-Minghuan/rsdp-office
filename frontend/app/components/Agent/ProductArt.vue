<script setup lang="ts">
const props = defineProps<{ kind: string }>()

const body = computed(() => {
  const desk = '<rect x="20" y="38" width="90" height="15" rx="3" fill="#b99872" stroke="#896e52"/><path d="M31 53V89M99 53V89" stroke="#686967" stroke-width="6"/>'
  const chairs = '<rect x="52" y="61" width="27" height="21" rx="5" fill="#757b7c"/><path d="M65 81V98M53 100H77" stroke="#606768" stroke-width="4"/>'
  const map: Record<string, string> = {
    workstation: `${desk}<rect x="54" y="21" width="25" height="13" rx="2" fill="#e1e9e6"/><rect x="34" y="68" width="19" height="15" rx="4" fill="#7a8487"/><rect x="85" y="68" width="19" height="15" rx="4" fill="#7a8487"/>`,
    chair: chairs,
    meeting: '<rect x="16" y="40" width="101" height="32" rx="13" fill="#b99a7c" stroke="#896b50"/><circle cx="34" cy="28" r="11" fill="#858989"/><circle cx="67" cy="28" r="11" fill="#858989"/><circle cx="100" cy="28" r="11" fill="#858989"/><circle cx="34" cy="85" r="11" fill="#858989"/><circle cx="67" cy="85" r="11" fill="#858989"/><circle cx="100" cy="85" r="11" fill="#858989"/>',
    sofa: '<rect x="22" y="28" width="88" height="62" rx="14" fill="#ded2c1" stroke="#a8957c" stroke-width="4"/><rect x="36" y="48" width="62" height="29" rx="7" fill="#f5ede1"/><path d="M65 48V77" stroke="#c7b6a3" stroke-width="3"/>',
    desk: `${desk}<rect x="91" y="54" width="20" height="34" rx="2" fill="#8a8076"/>`,
    cabinet: '<rect x="37" y="24" width="64" height="72" rx="5" fill="#bcbcb8" stroke="#8d8984" stroke-width="3"/><path d="M40 46H98M40 70H98" stroke="#89837d" stroke-width="3"/><path d="M64 36H76M64 60H76M64 82H76" stroke="#746f6b" stroke-width="3"/>',
  }
  return map[props.kind] || desk
})
</script>

<template>
  <svg viewBox="0 0 130 120" aria-hidden="true" class="w-full h-full">
    <ellipse cx="68" cy="102" rx="52" ry="8" fill="#bba78c" opacity=".18" />
    <g v-html="body" />
  </svg>
</template>
