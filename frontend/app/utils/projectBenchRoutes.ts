export const projectBenchPath = '/workspace/project-bench'

export function projectDrawingRoute(floorId?: string) {
  return { path: projectBenchPath, query: floorId ? { floorId } : { view: 'drawing' } }
}

export function projectOverviewRoute(floorId: string) {
  return { path: projectBenchPath, query: { view: 'overview', floorId } }
}
