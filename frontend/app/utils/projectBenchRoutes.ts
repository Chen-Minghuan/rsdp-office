export const projectBenchPath = '/workspace/project-bench'

export function projectDrawingRoute(floorId?: string) {
  return { path: projectBenchPath, query: floorId ? { floorId } : { view: 'drawing' } }
}

export function projectOverviewRoute(floorId: string) {
  return { path: projectBenchPath, query: { view: 'overview', floorId } }
}

/** 所有“进入排版/使用模板”入口统一落到产品选型工作区。 */
export function productSchemeRoute(floorId?: string, zoneId?: string) {
  return { path: '/canvas', query: { floorId, mode: 'design', view: 'products', zoneId } }
}
