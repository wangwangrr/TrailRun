// 抓取影梭（ZCShou/GoGoGo）的关键源码，弄清它注入位置的方式。
//
// 背景：本项目的模拟位置在高德、小米运动健康上有效，在步道乐跑上无效。
// 真机诊断证实：模拟位置已覆盖系统全部 provider（gps/network/fused/passive），
// 且抹除 isMock 标志的反射调用成功但被系统覆盖。而影梭在同样免 root 的条件下能生效。
// 所以必须看它的实现，而不是继续猜。
//
// 用法: node tools/fetch-gogogo.mjs
import https from 'node:https'
import { writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(ROOT, '.toolchain', 'gogogo')
mkdirSync(OUT, { recursive: true })

const REPO = 'ZCShou/GoGoGo'
const BRANCH = 'master'

const FILES = [
  'app/src/main/java/com/zcshou/service/ServiceGo.java',
  'app/src/main/java/com/zcshou/utils/GoUtils.java',
  'app/src/main/AndroidManifest.xml',
  'app/build.gradle',
  'README.md',
]

function get(url, timeoutMs = 30000, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 5) return reject(new Error('重定向过多'))
    const mod = url.startsWith('https:') ? https : null
    if (!mod) return reject(new Error('仅支持 https'))
    const req = mod.get(
      url,
      {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36',
          Accept: '*/*',
        },
        timeout: timeoutMs,
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume()
          const next = new URL(res.headers.location, url).toString()
          return get(next, timeoutMs, depth + 1).then(resolve, reject)
        }
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => resolve({ status: res.statusCode, buf: Buffer.concat(chunks) }))
        res.on('error', reject)
      }
    )
    req.on('timeout', () => req.destroy(new Error('超时')))
    req.on('error', reject)
    req.end()
  })
}

/** 依次尝试多个源：GitHub 直连经常不通，jsDelivr 与 API 作为后备。 */
async function fetchFile(relPath) {
  const sources = [
    ['raw', `https://raw.githubusercontent.com/${REPO}/${BRANCH}/${relPath}`],
    ['fastly', `https://fastly.jsdelivr.net/gh/${REPO}@${BRANCH}/${relPath}`],
    ['gcore', `https://gcore.jsdelivr.net/gh/${REPO}@${BRANCH}/${relPath}`],
    ['statically', `https://cdn.statically.io/gh/${REPO}/${BRANCH}/${relPath}`],
  ]
  for (const [name, url] of sources) {
    try {
      const r = await get(url)
      if (r.status === 200 && r.buf.length > 0) {
        return { source: name, buf: r.buf }
      }
    } catch {
      /* 换下一个源 */
    }
  }
  return null
}

for (const rel of FILES) {
  const name = rel.split('/').pop()
  const got = await fetchFile(rel)
  if (!got) {
    console.log(`✗ ${name}  —— 所有源都不通`)
    continue
  }
  writeFileSync(join(OUT, name), got.buf)
  console.log(`✓ ${name}  ${got.buf.length} B  (来源 ${got.source})`)
}
console.log(`\n保存于 ${OUT}`)
