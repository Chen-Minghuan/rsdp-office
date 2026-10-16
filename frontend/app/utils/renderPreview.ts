import type { OfficeObject } from '~/types/office'
import type { RenderPreviewSnapshot } from '~/types/renderPreview'

// Must match RenderPreviewScene.java; omit view state and confirmation flags.
const zoneFields = ['id', 'name', 'zoneType', 'reviewUnit', 'polygon', 'evidenceKind', 'sourceAnalysisId', 'sourceAnalysisRevision', 'spaceTemplateId', 'templateSpaceId']
const furnitureFields = ['id', 'zoneId', 'x', 'y', 'w', 'h', 'rotation', 'heightMm', 'wingWidthMm', 'label', 'furnitureTemplateId', 'moduleSnapshot', 'productId', 'productSelection']
const project = (item: object, keys: string[]) => Object.fromEntries(keys.filter(key => (item as any)[key] != null).map(key => [key, (item as any)[key]]))

export function renderPreviewSnapshot(objects: OfficeObject[], zoneId: string): RenderPreviewSnapshot | null {
  const zone = objects.find(item => item.kind === 'zone' && item.id === zoneId)
  if (!zone) return null
  return {
    zone: project(zone, zoneFields),
    furniture: objects.filter(item => item.kind === 'furniture' && item.zoneId === zoneId)
      .sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0).map(item => project(item, furnitureFields)),
  }
}

export function renderSnapshotKey(value: unknown): string {
  const canonical = (item: any): any => Array.isArray(item) ? item.map(canonical)
    : item && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, canonical(item[key])])) : item
  return JSON.stringify(canonical(value))
}

export const renderPreviewActive = (status: string) => ['SUBMITTING', 'PENDING', 'RUNNING'].includes(status)
