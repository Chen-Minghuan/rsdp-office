import type { FurnitureTemplate } from '~/types/manualCanvas'
import type { OfficeArrangementPreset, OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'

export const OFFICE_ARRANGEMENT_RULE_VERSION = '2026-10-edge-fill-v7'

export const OFFICE_PRODUCT_STYLES: Array<{ id: OfficeProductStyleId; label: string; description: string }> = [
  { id: 'straight-desk', label: '一字职员台', description: '仅提供素材中的单排、对坐' },
  { id: 'l-desk', label: 'L 型职员台', description: '保留转角关系，仅提供单排、对坐' },
  { id: 'straight-screen', label: '一字屏风卡位', description: '一字卡位，仅提供单排、对坐' },
  { id: 'l-screen', label: 'L 型屏风卡位', description: '提供 L 型单排、对坐和 F 型、T 型、C 型、十字型' },
]

export const OFFICE_MEETING_PRODUCT_STYLES: Array<{ id: OfficeProductStyleId; label: string; description: string }> = [
  { id: 'meeting-table', label: '会议桌', description: '以中心会议桌为核心，周边保留通行和拉椅空间' },
]

const ALL_DESK: OfficeProductStyleId[] = ['straight-desk', 'l-desk']
const ALL_SCREEN: OfficeProductStyleId[] = ['straight-screen', 'l-screen']
const ALL_MEETING: OfficeProductStyleId[] = ['meeting-table']

/**
 * The generic entries are kept for old saved layouts, but they are not part
 * of the product-style selector anymore. New layouts must use one of the
 * material-defined arrangements below.
 */
export const OFFICE_ARRANGEMENT_PRESETS: OfficeArrangementPreset[] = [
  { id: 'general-center', label: '单件居中', description: '按实际尺寸居中放置一件，可继续逐件添加', styleIds: ['general'], baseMode: 'auto', defaults: { wallMm: 0, aisleMm: 900, gapMm: 0 }, topology: 'row' },
  { id: 'general-auto', label: '按场地排布', description: '按空间尺寸、间距和目标数量生成多排方案', styleIds: ['general'], baseMode: 'auto', defaults: { wallMm: 0, aisleMm: 900, gapMm: 0 }, topology: 'row' },
  { id: 'generic-auto', label: '自动优化', description: '比较横纵朝向，按当前样式布置多排', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'auto', defaults: {}, topology: 'row' },
  { id: 'generic-row', label: '单排', description: '沿空间上侧布置一排，保留其余空间', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'row', defaults: {}, topology: 'row' },
  { id: 'generic-grid', label: '矩阵', description: '按当前朝向布置多排，保留排间净距', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'grid', defaults: {}, topology: 'row' },
  { id: 'generic-back-to-back', label: '背靠背', description: '座席朝外，背侧相邻', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'backToBack', defaults: { backGapMm: 0 }, topology: 'paired' },
  { id: 'generic-face-to-face', label: '面对面', description: '座席朝中央相对，保留面对净距', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired' },
  { id: 'generic-staggered', label: '错位排', description: '相邻排错开半个模块', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'staggered', defaults: {}, topology: 'row' },
  { id: 'generic-center-aisle', label: '中间通道', description: '左右两侧形成连续中央净空', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'centerAisle', defaults: { aisleMm: 900 }, topology: 'aisle' },
  { id: 'generic-perimeter', label: '沿边', description: '沿空间边界布置并避开转角重叠', styleIds: [...ALL_DESK, ...ALL_SCREEN], baseMode: 'perimeter', defaults: {}, topology: 'perimeter' },
  { id: 'meeting-center', label: '单桌居中', description: '会议桌居中布置，外围预留拉椅和通行空间', styleIds: ALL_MEETING, baseMode: 'auto', defaults: { wallMm: 1200, aisleMm: 1200, gapMm: 0 }, topology: 'row', sourceLabel: '会议空间：单桌居中' },
  { id: 'straight-desk-row', label: '一字单排', description: '按素材的单排组合生成两排同向桌列，默认净距 1000mm', styleIds: ['straight-desk'], baseMode: 'faceToFace', defaults: { faceGapMm: 1000 }, topology: 'paired', sourceLabel: '素材：一字职员台·单排' },
  { id: 'straight-desk-face-to-face', label: '一字对坐', description: '一字职员台两排相对，默认面对净距 1600mm', styleIds: ['straight-desk'], baseMode: 'faceToFace', defaults: { faceGapMm: 1600 }, topology: 'paired', sourceLabel: '素材：一字职员台·对坐' },
  { id: 'l-desk-row', label: 'L 型单排', description: '按素材的单排组合生成两排同向桌列，默认净距 600mm', styleIds: ['l-desk', 'l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 600 }, topology: 'paired', sourceLabel: '素材：L 型职员台·单排' },
  { id: 'l-desk-face-to-face', label: 'L 型对坐', description: 'L 型卡位镜像相对，默认转角净距 600mm', styleIds: ['l-desk', 'l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 600 }, topology: 'paired', sourceLabel: '素材：L 型职员台·对坐' },
  { id: 'straight-screen-row', label: '一字屏风卡位·单排', description: '一字屏风卡位单排布置，默认净距 1000mm', styleIds: ['straight-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 1000 }, topology: 'paired', sourceLabel: '素材：一字屏风卡位·单排' },
  { id: 'straight-screen-face-to-face', label: '一字屏风卡位·对坐', description: '一字屏风卡位对坐布置，默认净距 1600mm', styleIds: ['straight-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 1600 }, topology: 'paired', sourceLabel: '素材：一字屏风卡位·对坐' },
  { id: 'l-screen-row', label: 'L 型屏风单排', description: '按素材的单排组合生成两排同向桌列，默认净距 900mm', styleIds: ['l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired', sourceLabel: '素材：L 型屏风卡位·单排' },
  { id: 'l-screen-face-to-face', label: 'L 型屏风对坐', description: 'L 型屏风卡位镜像对坐', styleIds: ['l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired', sourceLabel: '素材：L 型屏风卡位·对坐' },
  { id: 'screen-f-row', label: 'F 型单排', description: '以屏风连续边和重复卡位形成 F 型相对组合，默认净距 900mm', styleIds: ['l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired', sourceLabel: '素材：F 型单排' },
  { id: 'screen-t-face-to-face', label: 'T 型对坐', description: '左右两组，每组 2 列×3 排，共 12 件', styleIds: ['l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired', sourceLabel: '素材：T 型对坐' },
  { id: 'screen-c-face-to-face', label: 'C 型对坐', description: '左右两组 C 型围合，每组 4 件，共 8 件', styleIds: ['l-screen'], baseMode: 'faceToFace', defaults: { faceGapMm: 900 }, topology: 'paired', sourceLabel: '素材：C 型对坐' },
  { id: 'screen-cross-face-to-face', label: '十字型对坐', description: '上下两组，每组 4 列×2 排，共 16 件', styleIds: ['l-screen'], baseMode: 'centerAisle', defaults: { faceGapMm: 900, aisleMm: 900 }, topology: 'aisle', sourceLabel: '素材：十字型对坐' },
]

const presetMap = new Map(OFFICE_ARRANGEMENT_PRESETS.map(preset => [preset.id, preset]))

const STRICT_PRESETS: Record<OfficeProductStyleId, OfficeArrangementPresetId[]> = {
  'straight-desk': ['straight-desk-row', 'straight-desk-face-to-face'],
  'l-desk': ['l-desk-row', 'l-desk-face-to-face'],
  'straight-screen': ['straight-screen-row', 'straight-screen-face-to-face'],
  'l-screen': ['l-desk-row', 'l-desk-face-to-face', 'screen-f-row', 'screen-t-face-to-face', 'screen-c-face-to-face', 'screen-cross-face-to-face'],
  'meeting-table': ['meeting-center'],
  general: ['general-center', 'general-auto'],
}

export function officeArrangementPreset(id?: string): OfficeArrangementPreset | undefined {
  return id ? presetMap.get(id as OfficeArrangementPresetId) : undefined
}

export function productStyleForTemplate(template: Pick<FurnitureTemplate, 'kind' | 'name'> & Partial<Pick<FurnitureTemplate, 'id' | 'category'>> & { glyphTemplateId?: string }): OfficeProductStyleId {
  if ('category' in template && template.category === '会议与洽谈') return 'meeting-table'
  const sourceId = template.glyphTemplateId || template.id
  if (sourceId === 'desk-screen-l') return 'l-screen'
  if (sourceId === 'desk-screen-straight') return 'straight-screen'
  if (template.category && template.category !== '职员桌') return 'general'
  if (!sourceId && template.name.includes('屏风')) return template.kind === 'l-desk' || template.kind === 'rounded-l-desk' ? 'l-screen' : 'straight-screen'
  return template.kind === 'l-desk' || template.kind === 'rounded-l-desk' || template.kind === 'u-desk' ? 'l-desk' : 'straight-desk'
}

export function presetsForProductStyle(styleId: OfficeProductStyleId): OfficeArrangementPreset[] {
  return STRICT_PRESETS[styleId].map(id => presetMap.get(id)!).filter(Boolean)
}

export function defaultPresetForProductStyle(styleId: OfficeProductStyleId): OfficeArrangementPreset {
  const presets = presetsForProductStyle(styleId)
  return presets.find(preset => preset.id === 'meeting-center')
    || presets.find(preset => preset.id.endsWith('-row'))
    || presets[0] || OFFICE_ARRANGEMENT_PRESETS[0]!
}
