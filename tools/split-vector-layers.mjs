// 逐层渲染：每次只画前 N 个 path，输出多张图，用于定位哪一层出错。
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const src = readFileSync(process.argv[2], 'utf8')
const outDir = process.argv[3]

// 把 vector 里的 path 逐个拆出来，生成「只含前 N 个」的临时文件
const pathRe = /<path\b[\s\S]*?(?:\/>|<\/path>)/g
const paths = []
let mm
while ((mm = pathRe.exec(src))) paths.push(mm[0])
console.log('共', paths.length, '个 path')

// 注意：必须按 match.index 截断，而不是 indexOf('<path')。
// 前者拿到的是完整的开头片段，后者会把 '<path' 这几个字符本身也切进来，
// 导致生成的文件残缺、解析不到任何 path。
const firstIdx = src.search(/<path\b/)
const openTag = src.slice(0, firstIdx)
const closeTag = '</vector>'

for (let n = 1; n <= paths.length; n++) {
  const body = paths.slice(0, n).join('\n')
  const tmp = join(outDir, `layer-${String(n).padStart(2, '0')}.xml`)
  writeFileSync(tmp, openTag + body + '\n' + closeTag)
}
console.log('已生成', paths.length, '个分层文件到', outDir)
