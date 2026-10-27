export function parseFloorNames(input: string): string[] {
  const names: string[] = []
  for (const part of input.split(/[,，、;；\s]+/).filter(Boolean)) {
    const range = part.match(/^(-?\d+)\s*[~～\-—至]\s*(-?\d+)\s*(?:F|f|层)?$/)
    if (range) {
      const start = Number(range[1]), end = Number(range[2])
      if (Math.abs(end - start) > 100) throw new Error('一次最多添加 101 个连续楼层')
      const step = start <= end ? 1 : -1
      for (let value = start; value !== end + step; value += step) names.push(value < 0 ? `B${Math.abs(value)}F` : `${value}F`)
    } else {
      names.push(part.replace(/层$/, ''))
    }
  }
  return [...new Set(names)]
}
