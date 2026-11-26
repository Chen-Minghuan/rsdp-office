import type { LocalProductImage, LocalProductInput } from '~/types/local-products'

const bytesPerMb = 1024 * 1024
const maxBatchBytes = 12 * bytesPerMb
const maxBatchProducts = 100
export const workbookPreviewPageSize = 10

export function workbookSize(bytes: number) {
  if (bytes < bytesPerMb) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / bytesPerMb).toFixed(2) + ' MB'
}

export function workbookImageBytes(image: LocalProductImage, sizes: Record<string, number>) {
  if (image.id && sizes[image.id] !== undefined) return sizes[image.id]!
  const content = image.content ?? ''
  const padding = content.endsWith('==') ? 2 : Number(content.endsWith('='))
  return content.length * 3 / 4 - padding
}

export function splitWorkbookProducts(products: LocalProductInput[], sizes: Record<string, number>) {
  const batches: LocalProductInput[][] = []
  let batch: LocalProductInput[] = []
  let imageBytes = 0
  for (const product of products) {
    const bytes = product.images.reduce((total, image) => total + workbookImageBytes(image, sizes), 0)
    if (bytes > maxBatchBytes) throw new Error(product.name + ' 的图片超过单件商品可提交的大小')
    if (batch.length && (batch.length >= maxBatchProducts || imageBytes + bytes > maxBatchBytes)) {
      batches.push(batch)
      batch = []
      imageBytes = 0
    }
    batch.push(product)
    imageBytes += bytes
  }
  if (batch.length) batches.push(batch)
  return batches
}
