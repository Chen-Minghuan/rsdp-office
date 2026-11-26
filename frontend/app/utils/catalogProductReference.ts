const localProductPrefix = 'OFFICE-LOCAL-'

export function localProductReference(productId: string) {
  return localProductPrefix + productId
}

export function localProductId(reference: string) {
  return reference.startsWith(localProductPrefix) ? reference.slice(localProductPrefix.length) : null
}

export function catalogProductPath(reference: string) {
  const productId = localProductId(reference)
  return productId
    ? `/products/local/${encodeURIComponent(productId)}?from=catalog`
    : `/products/${encodeURIComponent(reference)}`
}
