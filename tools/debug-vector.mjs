// 调试：打印每个 path 的颜色与包围盒，定位渲染异常。
import { readFileSync } from 'node:fs'

const xml = readFileSync(process.argv[2], 'utf8')
const re = /<path\b([\s\S]*?)(?:\/>|>([\s\S]*?)<\/path>)/g
let m
let i = 0
while ((m = re.exec(xml))) {
  const attrs = m[1]
  const body = m[2] || ''
  const dMatch = attrs.match(/android:pathData="([^"]+)"/)
  const d = dMatch ? dMatch[1] : '(无 pathData)'
  const fillMatch = attrs.match(/android:fillColor="([^"]+)"/)
  const fill = fillMatch ? fillMatch[1] : null
  const grad = body.match(/<gradient\b([\s\S]*?)<\/gradient>/)

  const nums = (d.match(/-?[0-9]*\.?[0-9]+/g) || []).map(Number)
  const xs = [], ys = []
  for (let k = 0; k + 1 < nums.length; k += 2) { xs.push(nums[k]); ys.push(nums[k + 1]) }
  const bb = xs.length
    ? `x ${Math.min(...xs)}~${Math.max(...xs)}  y ${Math.min(...ys)}~${Math.max(...ys)}`
    : '?'

  let stopInfo = ''
  if (grad) {
    const stops = [...grad[1].matchAll(/offset="([\d.]+)"\s+android:color="([^"]+)"/g)]
    stopInfo = ' stops=' + stops.length + ' ' + stops.map((s) => s[2]).join(',')
  }
  console.log(`#${i}  fill=${fill || (grad ? '[gradient]' : 'NONE')}${stopInfo}  |  ${bb}`)
  i++
}
console.log('总计', i, '个 path')
