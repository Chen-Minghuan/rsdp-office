import type { LayoutReferenceEditing, LayoutReferenceMark } from '~/types/layoutPlanning'

/** 识别任务按 空间+图纸版本 键隔离，支持多空间并发。 */
export function layoutReferenceTaskKey(zoneId: string, analysisId: string, revision: number): string {
  return `${zoneId}:${analysisId}:${revision}`
}

/** 重新识别时保留人工确认的标记，并丢弃与人工标记近似重合的机器建议。 */
export function mergeRecognizedMarks(current: LayoutReferenceMark[], detected: LayoutReferenceMark[]): LayoutReferenceMark[] {
  const manual = current.filter(mark => mark.confidence === 'manual')
  const fresh = detected.filter(mark => !manual.some(item => item.categoryId === mark.categoryId
    && Math.hypot(item.x + item.width / 2 - mark.x - mark.width / 2, item.y + item.depth / 2 - mark.y - mark.depth / 2) < 0.04))
  return [...manual, ...fresh]
}

/** 深拷贝编辑快照：存入任务记录或恢复面板时使用，选中态与放置态不随快照保留。 */
export function cloneReferenceEditing(editing: LayoutReferenceEditing): LayoutReferenceEditing {
  return {
    zoneId: editing.zoneId,
    crop: editing.crop ? { ...editing.crop } : null,
    marks: editing.marks.map(mark => ({ ...mark })),
    selected: null,
    placement: null,
  }
}
