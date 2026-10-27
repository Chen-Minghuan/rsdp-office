import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutChannel, LayoutProposal } from '~/types/layoutPlanning'
import type { OfficeLayoutSpacing } from './officeAutoLayout'
import { CHAIR_BACK_PASSAGE_MM, chairBackMinGapMm } from './officeAutoLayout'
import { officeArrangementPreset } from './officeArrangementPresets'
import type { OfficeArrangementPresetId } from '~/types/officeArrangement'
import { CHANNEL_WIDTH_HINT_MM } from './layoutChannels'
import { channelEgressIssues } from './channelEgress'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { ringArea } from './spatial/geometry'

/**
 * LAYOUT-035 统一校核预警分级（全部为策略值，非建筑规范；硬校验拒绝语义不变）：
 * - error：会被确认/应用拒绝的硬冲突（方案无法排出、座位不足目标——confirmLayout 对目标不足返回 false）；
 * - warning：低于推荐策略值（通道宽度、入口衔接/截断、人均面积、边缘补位）；
 * - info：未核验说明与现场核验提醒。
 */
export type LayoutWarningLevel = 'error' | 'warning' | 'info'
export interface LayoutWarning {
  /** 稳定标识：面板用作 data-warning-id；channel-width / channel-egress-* 兼容 023/029 旧 testid。 */
  id: string
  level: LayoutWarningLevel
  text: string
  /** 修改方向建议（黄/红必附）。 */
  advice?: string
}

/** 人均面积提示阈值（㎡/座）：低于此值给黄级提示。策略值，非建筑规范。 */
export const AREA_PER_SEAT_HINT_M2 = 2

/** 空间净面积（mm²）= 轮廓面积 − 建筑约束外包矩形面积（近似，障碍按外包矩形计）。 */
export function zoneNetAreaMm2(zone: ZoneObject): number {
  if (!zone.polygon?.length) return 0
  return Math.max(1, Math.abs(ringArea(zone.polygon)) - zoneLayoutExclusions(zone).reduce((sum, area) => sum + area.w * area.h, 0))
}

/**
 * 排版面板统一预警。proposal 为当前激活方案（含用户选中的候选）；channels 为空间已保存通道；
 * furniture 为当前已放置家具（供入口联动走廊挤占计算）；firstBlockDepthMm 取已保存排版记录。
 */
export function layoutPanelWarnings(args: { zone: ZoneObject; channels: LayoutChannel[]; furniture: FurnitureObject[]; proposal: LayoutProposal | null; firstBlockDepthMm?: number; spacing?: OfficeLayoutSpacing; presetId?: OfficeArrangementPresetId; seatClearanceMm?: number }): LayoutWarning[] {
  const { zone, channels, furniture, proposal } = args
  const warnings: LayoutWarning[] = []
  if (proposal?.problem) {
    warnings.push({ id: 'proposal-problem', level: 'error', text: proposal.problem, advice: '调整家具尺寸、间距、通道或目标人数后重试；也可逐件手动放置' })
  } else if (proposal) {
    if (proposal.shortfall > 0) {
      warnings.push({
        id: 'shortfall', level: 'error',
        text: `座位不足：已排 ${proposal.seats} / 目标 ${proposal.seats + proposal.shortfall}，确认排版会被拒绝`,
        advice: '换更小的桌型、缩小间距、减少通道占用，或调低目标人数',
      })
    }
    const seats = proposal.seats
    if (seats > 0) {
      const areaPerSeat = zoneNetAreaMm2(zone) / seats / 1_000_000
      if (areaPerSeat < AREA_PER_SEAT_HINT_M2) {
        warnings.push({
          id: 'area-per-seat', level: 'warning',
          text: `人均面积约 ${areaPerSeat.toFixed(1)} ㎡/座，低于 ${AREA_PER_SEAT_HINT_M2} ㎡/座 策略参考`,
          advice: '减少目标人数、换更紧凑的桌型，或缩减通道与预留区域',
        })
      }
    }
    for (const text of proposal.warnings) {
      if (text.includes('补位')) {
        warnings.push({ id: 'edge-fill', level: 'warning', text, advice: '核对补位位置与现场通道；不需要可清空后逐件放置' })
      } else {
        warnings.push({ id: 'field-review', level: 'info', text })
      }
    }
  }
  channels.forEach((channel, index) => {
    if (channel.widthMm >= CHANNEL_WIDTH_HINT_MM) return
    const label = `${channel.orientation === 'vertical' ? '纵向' : '横向'}通道${channels.length > 1 ? ' ' + (index + 1) : ''}`
    warnings.push({
      id: 'channel-width', level: 'warning',
      text: `「${label}」宽度低于 ${CHANNEL_WIDTH_HINT_MM}mm 通行提示阈值（策略提示，非规范强制）`,
      advice: `加宽至 ${CHANNEL_WIDTH_HINT_MM}mm 以上；现场条件有限时可保留，并在效果图阶段复核`,
    })
  })
  channelEgressIssues(zone, channels, furniture, args.firstBlockDepthMm).forEach((issue, index) => {
    if (issue.kind === 'unmarked') warnings.push({ id: 'channel-egress-unmarked', level: 'info', text: issue.text })
    else if (issue.kind === 'unlinked') warnings.push({ id: `channel-egress-unlinked-${index}`, level: 'warning', text: issue.text, advice: '拖动通道端头至入口附近，或在空间核对页调整门洞净空标注' })
    else warnings.push({ id: `channel-egress-blocked-${index}`, level: 'warning', text: issue.text, advice: '移开衔接路径上的家具或禁放区，或调整通道位置' })
  })
  // LAYOUT-038：椅背相对间隔低于净宽（2×拉椅预留+500 通行，策略值）。
  // generic-* 配对排布走 planOfficeFurniture，引擎已自动加宽（说明原因与数值）；样式参考排布（adaptive）
  // 的间距按素材固定，不加宽、只提示。手动拖小间距（同一份 spacing）触发同级提示。
  const preset = args.presetId ? officeArrangementPreset(args.presetId) : undefined
  if (args.spacing && preset && (preset.baseMode === 'backToBack' || preset.baseMode === 'faceToFace')) {
    const clearance = args.seatClearanceMm ?? 400
    const minGap = chairBackMinGapMm(clearance)
    const current = preset.baseMode === 'backToBack' ? args.spacing.aisleMm : (args.spacing.faceGapMm ?? 900)
    if (current < minGap) {
      const where = preset.baseMode === 'backToBack' ? '相邻组之间的排距' : '对坐两组之间的间隔'
      const generic = args.presetId!.startsWith('generic-')
      warnings.push({
        id: 'chair-back-gap', level: 'warning',
        text: generic
          ? `椅背相对：${where} ${Math.round(current)}mm 低于净宽 ${minGap}mm（2×拉椅预留 ${Math.round(clearance)}mm + ${CHAIR_BACK_PASSAGE_MM}mm 通行，策略值），排版时已自动加宽到 ${minGap}mm，排数可能因此减少`
          : `椅背相对：${where} ${Math.round(current)}mm 低于净宽 ${minGap}mm（2×拉椅预留 ${Math.round(clearance)}mm + ${CHAIR_BACK_PASSAGE_MM}mm 通行，策略值）；参考排布间距按素材固定，未自动加宽`,
        advice: generic
          ? '如需更多排数可换浅桌型；如确认现场不需要该通行量，可在确认前逐件微调（确认校验不含此策略项）'
          : '可参考提示调大净距参数（会偏离参考排布），或保持素材间距并现场复核通行',
      })
    }
  }
  const order: Record<LayoutWarningLevel, number> = { error: 0, warning: 1, info: 2 }
  return warnings.sort((a, b) => order[a.level] - order[b.level])
}

/** 旧 testid 迁移（023/029）：统一块内保留原 testid，便于既有 e2e 与人工核对定位。 */
export function layoutWarningTestId(warning: LayoutWarning): string {
  if (warning.id === 'channel-width') return 'channel-width-hint'
  if (warning.id.startsWith('channel-egress')) return 'channel-egress-hint'
  return 'layout-warning-item'
}
