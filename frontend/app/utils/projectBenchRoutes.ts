export const projectBenchPath = '/workspace/project-bench'

export function projectDrawingRoute(floorId?: string) {
  return { path: projectBenchPath, query: floorId ? { floorId } : { view: 'drawing' } }
}
