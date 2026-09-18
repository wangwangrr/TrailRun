// 验证「别人能不能下载到 APK」—— 完全匿名，不带任何 token。
//
// 三条路径各自的瓶颈不同：
//   - GitHub Release  : 下载会 302 到 objects.githubusercontent.com，且入口是 github.com（国内常不可达）
//   - raw 直链        : raw.githubusercontent.com
//   - jsDelivr CDN    : cdn.jsdelivr.net，国内有节点，但单文件限制 20 MB
//
// 用法: node tools/verify-download.mjs [owner/repo] [tag] [asset]
import https from 'node:https'

const owner = process.argv[2] || 'wangwangrr'
const repo = process.argv[3] || 'TrailRun'
const tag = process.argv[4] || 'v1.0.0'
const asset = process.argv[5] || 'TrailRun-1.0.0.apk'
const branch = 'main'
const assetPath = `release/${asset}`

/** 只读前 maxBytes 就断开 —— 验证可达性不需要真把 17 MB 拉完。 */
function probe(url, { maxBytes = 0, follow = 0, timeoutMs = 25000 } = {}) {
  return new Promise((resolve) => {
    const started = Date.now()
    let settled = false
    const done = (r) => {
      if (!settled) {
        settled = true
        resolve(Object.assign({ ms: Date.now() - started }, r))
      }
    }
    const req = https.get(
      url,
      {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36',
          Accept: '*/*',
        },
        timeout: timeoutMs,
      },
      (res) => {
        const loc = res.headers.location
        if (res.statusCode >= 300 && res.statusCode < 400) {
          res.resume()
          if (follow > 0 && loc) {
            const next = new URL(loc, url).toString()
            probe(next, { maxBytes, follow: follow - 1, timeoutMs }).then((r) =>
              done(Object.assign(r, { hop: res.statusCode, landedOn: new URL(next).host }))
            )
          } else {
            done({ status: res.statusCode, location: loc, landedOn: loc ? new URL(loc, url).host : null })
          }
          return
        }
        let got = 0
        res.on('data', (c) => {
          got += c.length
          if (maxBytes && got >= maxBytes) {
            res.destroy()
            done({ status: res.statusCode, bytes: got, partial: true, type: res.headers['content-type'], len: res.headers['content-length'] })
          }
        })
        res.on('end', () =>
          done({ status: res.statusCode, bytes: got, type: res.headers['content-type'], len: res.headers['content-length'] })
        )
        res.on('error', () => {})
      }
    )
    req.on('timeout', () => {
      req.destroy()
      done({ status: 'TIMEOUT', host: new URL(url).host })
    })
    req.on('error', (e) => done({ status: 'ERR', host: new URL(url).host, note: String(e.code || e.message) }))
  })
}

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - String(s).length))
const mb = (n) => (Number(n) / 1048576).toFixed(2) + ' MB'

function report(label, r, expectBytes) {
  const ok = r.status === 200 || r.partial
  console.log(`\n${label}`)
  console.log(`  host    : ${r.host || '-'}${r.landedOn ? '  (经 ' + r.hop + ' 落到 ' + r.landedOn + ')' : ''}`)
  console.log(`  结果    : ${ok ? '可达' : '不可达'}   HTTP ${r.status}   ${r.ms}ms`)
  if (r.len) console.log(`  文件大小: ${mb(r.len)}${expectBytes ? (Number(r.len) === expectBytes ? '  ✓ 与本地一致' : '  ✗ 与本地不符') : ''}`)
  if (r.note) console.log(`  错误    : ${r.note}`)
  return ok
}

// 先确认仓库里那个文件真实存在、大小对得上
const apiRes = await new Promise((resolve) => {
  https
    .get(
      `https://api.github.com/repos/${owner}/${repo}/git/trees/${branch}?recursive=1`,
      { headers: { 'User-Agent': 'TrailRun-verify', Accept: 'application/vnd.github+json' }, timeout: 30000 },
      (res) => {
        const c = []
        res.on('data', (d) => c.push(d))
        res.on('end', () => resolve(Buffer.concat(c).toString('utf8')))
      }
    )
    .on('error', () => resolve('{}'))
})
let expectBytes = null
try {
  const j = JSON.parse(apiRes)
  const hit = (j.tree || []).find((e) => e.path === assetPath)
  console.log(`仓库内文件: ${assetPath}  ${hit ? hit.size + ' B (' + mb(hit.size) + ')' : '不存在'}`)
  expectBytes = hit ? hit.size : null
} catch {
  console.log('读取仓库 tree 失败')
}

console.log('\n============ 三条下载路径 ============')

const releaseOk = report(
  '[1] GitHub Release（官方渠道）',
  await probe(`https://github.com/${owner}/${repo}/releases/download/${tag}/${asset}`, { follow: 5, maxBytes: 262144 }),
  expectBytes
)

const rawOk = report(
  '[2] 仓库 raw 直链',
  await probe(`https://raw.githubusercontent.com/${owner}/${repo}/${branch}/${assetPath}`, { follow: 2, maxBytes: 262144 }),
  expectBytes
)

const jsdOk = report(
  '[3] jsDelivr CDN（国内推荐）',
  await probe(`https://cdn.jsdelivr.net/gh/${owner}/${repo}@${branch}/${assetPath}`, { follow: 3, maxBytes: 262144 }),
  expectBytes
)

console.log('\n============ 结论 ============')
const names = ['GitHub Release', 'raw 直链', 'jsDelivr CDN']
const results = [releaseOk, rawOk, jsdOk]
const usable = names.filter((_, i) => results[i])
console.log(`当前网络下可用: ${usable.length ? usable.join(' / ') : '（无 —— 三个域名全部不可达）'}`)
if (!jsdOk) {
  console.log('\njsDelivr 失败时可以先在浏览器里访问一次（触发回源），再重试；')
  console.log('也可以换节点：fastly.jsdelivr.net / gcore.jsdelivr.net，把 cdn 换成对应前缀即可。')
}
