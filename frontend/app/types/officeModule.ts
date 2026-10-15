import type { FurnitureTemplate } from './manualCanvas'

export interface CustomOfficeModule extends FurnitureTemplate {
  custom: true
  /** Shared built-in glyph; custom identity stays separate from its visual source. */
  glyphTemplateId: string
}

export type OfficeModuleTemplate = FurnitureTemplate | CustomOfficeModule
export interface OfficeModuleInput {
  name: string
  glyphTemplateId: string
  widthMm: number
  depthMm: number
  heightMm?: number
}
