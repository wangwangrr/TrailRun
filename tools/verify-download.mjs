// 完全匿名（不带任何 token）验证：别人打开仓库时，能不能真的把 APK 下下来。
// 关键在重定向链：github.com/.../releases/download/... 会 302 到另一个域名，
// 那个域名通不通，才是「他人能否下载」的真正答案。
import https from 'node:https'

const OWNER = 'wangwangrr'
const REPO = 'TrailRun'
const TAG = 'v1.0.0'
const ASSET = 'TrailRun-1.0.0.apk'

function get(url, { follow = 0, maxBytes = 0, timeoutMs = 20000 } = {}) {
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
          // 故意用普通浏览器 UA、不带 Authorization —— 完全模拟一个路人
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36',
          Accept: '*/*',
        },
        timeout: timeoutMs,
      },
      (res) => {
        const loc = res.headers.location
        if (res.statusCode >= 300 && res.statusCode < 400) {
          res.resume()
          done({ status: res.statusCode, location: loc })
          if (follow > 0 && loc) {
            const next = new URL(loc, url).toString()
            get(next, { follow: follow - 1, maxBytes, timeoutMs }).then((r2) =>
              done({ ...r2, via: next, redirectedFrom: res.statusCode })
            )
          }
          return
        }
        let got = 0
        res.on('data', (c) => {
          got += c.length
          if (maxBytes && got >= maxBytes) {
            res.destroy()
            done({ status: res.statusCode, bytes: got, type: res.headers['content-type'], partial: true })
          }
        })
        res.on('end', () =>
          done({
            status: res.statusCode,
            bytes: got,
            type: res.headers['content-type'],
            len: res.headers['content-length'],
          })
        )
        res.on('error', (e) => done({ status: 'ERR', note: e.message }))
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

console.log('=== 1) 仓库本身是否匿名可见 ===')
let r = await get(`https://api.github.com/repos/${OWNER}/${REPO}`)
console.log(`  API 仓库信息        ${r.status}  ${r.ms}ms`)
r = await get(`https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`)
console.log(`  API 最新 Release    ${r.status}  ${r.ms}ms`)

console.log('\n=== 2) Release 页面 ===')
r = await get(`https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}`)
console.log(`  页面                ${r.status}  ${r.ms}ms`)

console.log('\n=== 3) 下载链接的完整重定向链 ===')
const dl = `https://github.com/${OWNER}/${REPO}/releases/download/${TAG}/${ASSET}`
console.log(`  起点 host: github.com`)
r = await get(dl, { follow: 0 })
console.log(`  第一跳              ${r.status}  ->  ${r.location ? new URL(r.location).host : '(无)'}`)
if (r.location) {
  const hop2 = new URL(r.location).toString()
  const r2 = await get(hop2, { follow: 0, maxBytes: 0 })
  console.log(`  第二跳              ${r2.status}  ${r2.type || ''}  ${r2.len ? (Number(r2.len) / 1048576).toFixed(2) + ' MB' : ''}  ${r2.ms}ms`)
  if (r2.status === 'ERR' || r2.status === 'TIMEOUT') {
    console.log(`     !! ${new URL(hop2).host} 不可达：${r2.note || r2.status}`)
  }
}

console.log('\n=== 4) 真正下载前 256 KB（模拟别人点下载）===')
r = await get(dl, { follow: 5, maxBytes: 262144 })
if (r.status === 200 || r.partial) {
  console.log(`  成功收到 ${(r.bytes / 1024).toFixed(0)} KB  ${r.type}  ${r.ms}ms`)
  console.log('  => 这个网络下，别人点下载是能下到的。')
} else {
  console.log(`  失败：${r.status}  ${r.note || ''}  ${r.host || ''}`)
}

console.log('\n=== 5) 相关域名当前连通性 ===')
for (const [name, url] of [
  ['github.com', `https://github.com/${OWNER}/${REPO}`],
  ['codeload.github.com (下载 ZIP)', `https://codeload.github.com/${OWNER}/${REPO}/zip/refs/heads/main`],
  ['raw.githubusercontent.com', `https://raw.githubusercontent.com/${OWNER}/${REPO}/main/README.md`],
  ['objects.githubusercontent.com', 'https://objects.githubusercontent.com/'],
]) {
  const x = await get(url, { maxBytes: 1024 })
  console.log(`  ${pad(name, 32)} ${pad(x.status, 9)} ${x.ms}ms ${x.note || ''}`)
}
