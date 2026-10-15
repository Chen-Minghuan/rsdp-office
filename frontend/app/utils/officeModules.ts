import type { CustomOfficeModule, OfficeModuleInput, OfficeModuleTemplate } from '~/types/officeModule'
import { furnitureById } from '~/utils/manualFurniture'

export function moduleGlyphId(module: OfficeModuleTemplate): string {
  return 'glyphTemplateId' in module ? module.glyphTemplateId : module.id
}

export function moduleInputProblem(input: OfficeModuleInput): string | null {
  if (!input.name.trim() || input.name.trim().length > 60) return '请输入 1–60 字的模块名称'
  const source = furnitureById.get(input.glyphTemplateId)
  if (!source || source.catalogHidden) return '请选择有效的办公家具图例'
  if (![input.widthMm, input.depthMm].every(value => Number.isFinite(value) && value >= 10)) return '宽度和进深不能小于 10 毫米'
  if (input.heightMm !== undefined && (!Number.isFinite(input.heightMm) || input.heightMm <= 0)) return '高度必须为有效的正数'
  return null
}

export function customModuleDefinition(id: string, input: OfficeModuleInput): CustomOfficeModule | null {
  if (!id.startsWith('UMOD-') || moduleInputProblem(input)) return null
  const source = furnitureById.get(input.glyphTemplateId)!
  return { id, name: input.name.trim(), custom: true, glyphTemplateId: source.id, category: source.category,
    kind: source.kind, widthMm: input.widthMm, depthMm: input.depthMm,
    ...(input.heightMm !== undefined ? { heightMm: input.heightMm } : {}) }
}

export function restoreCustomModules(raw: unknown): CustomOfficeModule[] {
  if (!Array.isArray(raw)) return []
  const restored: CustomOfficeModule[] = []
  for (const item of raw) {
    if (!item || typeof item.id !== 'string' || typeof item.name !== 'string' || typeof item.glyphTemplateId !== 'string') continue
    const module = customModuleDefinition(item.id, item)
    if (module && !restored.some(existing => existing.id === module.id)) restored.push(module)
  }
  return restored
}
