import polygonClipping from 'polygon-clipping'
import { ringProblem } from './review'

/**
 * GEOM-014 空间轮廓布尔运算（合并/剔除）。
 * ZoneObject.polygon 只支持单外环无孔洞，因此只接受"一个外环、无孔"的结果；
 * 其余情况返回可操作的中文原因，由调用方原样提示，不静默取最大块或丢孔。
 * 输出坐标取整到 0.01（毫米场景即 0.01mm），去掉首尾重复点并过 ringProblem 校验。
 */
export type RingBooleanResult = { ring: Array<[number, number]> } | { problem: string }

type MultiPolygon = Array<Array<Array<[number, number]>>>

const toMulti = (ring: Array<[number, number]>): MultiPolygon => [[ring]]

/** 取整 + 去连续重复点 + 去闭合尾点；结果不足 3 点视为退化。 */
function normalizeRing(raw: Array<[number, number]>): Array<[number, number]> {
  const rounded = raw.map(([x, y]): [number, number] => [Math.round(x * 100) / 100, Math.round(y * 100) / 100])
  const ring: Array<[number, number]> = []
  for (const point of rounded) {
    const prev = ring.at(-1)
    if (!prev || Math.hypot(point[0] - prev[0], point[1] - prev[1]) > 0.02) ring.push(point)
  }
  if (ring.length > 1 && Math.hypot(ring[0][0] - ring.at(-1)![0], ring[0][1] - ring.at(-1)![1]) <= 0.02) ring.pop()
  return ring
}

function singleRing(result: MultiPolygon, problems: { empty: string; split: string; hole: string }): RingBooleanResult {
  if (!result.length) return { problem: problems.empty }
  if (result.length > 1) return { problem: problems.split }
  const [polygon] = result
  if (polygon.length > 1) return { problem: problems.hole }
  const ring = normalizeRing(polygon[0])
  if (ring.length < 3) return { problem: problems.empty }
  const problem = ringProblem(ring)
  if (problem) return { problem }
  return { ring }
}

/** 两空间轮廓并集：不相连（分离多块）或形成孔洞时拒绝。 */
export function unionRings(a: Array<[number, number]>, b: Array<[number, number]>): RingBooleanResult {
  if (a.length < 3 || b.length < 3) return { problem: '空间缺少可运算轮廓' }
  return singleRing(polygonClipping.union(toMulti(a), toMulti(b)) as MultiPolygon, {
    empty: '两空间没有可合并的区域',
    split: '两空间不相连，合并后会分成互不相连的多块，当前空间轮廓不支持',
    hole: '合并后轮廓会形成孔洞，当前空间轮廓不支持孔洞',
  })
}

/** 从 a 中剔除 b：剔除完为空、分离成多块或产生孔洞时拒绝。 */
export function subtractRings(a: Array<[number, number]>, b: Array<[number, number]>): RingBooleanResult {
  if (a.length < 3 || b.length < 3) return { problem: '空间缺少可运算轮廓' }
  return singleRing(polygonClipping.difference(toMulti(a), toMulti(b)) as MultiPolygon, {
    empty: '剔除后空间不剩任何区域',
    split: '剔除会把空间分成互不相连的多块，当前空间轮廓不支持',
    hole: '剔除区域完全在空间内部会形成孔洞：请改用「禁放区」标注该区域，或将剔除范围延伸到空间边缘',
  })
}
