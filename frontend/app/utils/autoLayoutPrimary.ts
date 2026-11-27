import type { FurnitureObject, ZoneObject } from '~/types/office'
import { furnitureById } from '~/utils/manualFurniture'
import { officeFurnitureTemplateId } from '~/utils/officeFurniture'

/**
 * 会议桌自动排版主家具判定：只承认当前空间显式自动组 `auto:{zoneId}`。
 * 缺失/不匹配 layoutGroupId 的独立副本一律走普通尺寸编辑，不得误入组合重排。
 */
export function isMeetingAutoLayoutPrimary(item: FurnitureObject, zones: ZoneObject[]): boolean {
  if (item.layoutRole !== 'primary') return false
  if (item.layoutSettings?.layoutContext !== 'meeting') return false
  const zone = zones.find(entry => entry.id === item.zoneId)
  if (!zone || item.layoutGroupId !== `auto:${zone.id}`) return false
  const templateId = officeFurnitureTemplateId(item) || item.furnitureTemplateId
  const code = templateId ? furnitureById.get(templateId)?.reference?.code : undefined
  if (!code || code === 'T16' || code === 'S15') return false
  return true
}
