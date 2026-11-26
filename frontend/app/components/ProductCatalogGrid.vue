<template>
  <div class="product-cards">
    <ul aria-label="商品卡片列表" class="catalog-grid py-3">
      <slot />
    </ul>
  </div>
</template>

<style scoped>
.product-cards { container: product-cards / inline-size; }
.catalog-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.65rem; }

@container product-cards (min-width: 455px) {
  .catalog-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@container product-cards (min-width: 660px) {
  .catalog-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@container product-cards (min-width: 865px) {
  .catalog-grid { grid-template-columns: repeat(5, minmax(0, 1fr)); }
}
@container product-cards (min-width: 1160px) {
  .catalog-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); }
}
@container product-cards (min-width: 1470px) {
  .catalog-grid { grid-template-columns: repeat(7, minmax(0, 1fr)); }
}
@container product-cards (min-width: 1650px) {
  .catalog-grid { grid-template-columns: repeat(8, minmax(0, 1fr)); }
}
</style>
