<script setup lang="ts">
import { projectBenchPath } from '~/utils/projectBenchRoutes'

definePageMeta({
  redirect: to => ({
    path: projectBenchPath,
    query: typeof to.query.floorId === 'string' ? to.query : { ...to.query, view: 'drawing' },
    hash: to.hash,
  }),
})
</script>

<template><div /></template>
