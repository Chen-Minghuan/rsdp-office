<script setup lang="ts">
// The review workbench is shared with the actual upload → canvas route.
await navigateTo({ path: '/canvas', query: useRoute().query }, { replace: true })
</script>
<template><div /></template>
