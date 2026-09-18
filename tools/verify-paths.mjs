// 逐条验证下载链路是否真的能拿到**指定这一版**的 APK。
//
// 与 verify-download.mjs 的区别：那个脚本把「仓库内文件名」写死成了 1.0.0，
// 而且只试 cdn.jsdelivr.net 一个域名 —— 而本机实测只有
// fastly.jsdelivr.net / gcore.jsdelivr.net 稳定，cdn.jsdelivr.net 会超时。
// 验证脚本本身给出错误结论，比不验证更糟，所以单独写一个。
//
// 用法: node tools/verify-paths.mjs <owner/repo> <tag> <文件名>
//   例: node tools/verify-paths.mjs wangwangrr/TrailRun v1.2.8 TrailRun-1.2.8.apk
const [slug, tag, fileName] = process.argv.slice(2)
if (!slug || !tag || !fileName) {
  console.error('用法: node tools/verify-paths.mjs <owner/repo> <tag> <文件名>')
  process.exit(2)
}

const urls = [
  ['GitHub Release 附件', `https://github.com/${slug}/releases/download/${tag}/${fileName}`],
  ['仓库 raw 直链', `https://raw.githubusercontent.com/${slug}/main/release/${fileName}`],
  ['jsDelivr fastly', `https://fastly.jsdelivr.net/gh/${slug}@main/release/${fileName}`],
  ['jsDelivr gcore', `https://gcore.jsdelivr.net/gh/${slug}@main/release/${fileName}`],
  ['jsDelivr cdn', `https://cdn.jsdelivr.net/gh/${slug}@main/release/${fileName}`],
]

// 只读前 64 KB 就断开：既能确认「拿得到」，又不用把 2.5 MB 拖完。
async function probe(url) {
  const started = Date.now()
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 25000)
  try {
    const res = await fetch(url, { signal: ctrl.signal, redirect: 'follow' })
    const len = res.headers.get('content-length')
    const total = res.headers.get('x-total-size') || len
    const head = await res.arrayBuffer()
    const magic = Buffer.from(head.slice(0, 2)).toString('latin1')
    return {
      ok: res.ok,
      status: res.status,
      host: new URL(res.url).host,
      total: total ? Number(total) : head.byteLength,
      zip: magic === 'PK', // APK 就是 zip
      ms: Date.now() - started,
    }
  } catch (e) {
    return { ok: false, status: 'ERR', host: '-', error: e.name + ': ' + e.message, ms: Date.now() - started }
  } finally {
    clearTimeout(timer)
  }
}

const mb = (n) => (n / 1024 / 1024).toFixed(2) + ' MB'
let usable = 0

for (const [label, url] of urls) {
  const r = await probe(url)
  if (r.ok && r.zip) {
    usable++
    console.log(`[可用] ${label}`)
    console.log(`       ${r.status}  落到 ${r.host}  共 ${mb(r.total)}  用时 ${r.ms}ms`)
  } else {
    console.log(`[不可用] ${label}`)
    console.log(`       ${r.status}  ${r.error || ''}  用时 ${r.ms}ms`)
  }
}

console.log(`\n本次网络下可用路径: ${usable} / ${urls.length}`)
if (usable === 0) {
  console.log('注意：全部失败不代表文件不存在 —— 先确认 api.github.com 能列出该 Release，')
  console.log('      再考虑是网络问题。jsDelivr 新文件有时要等回源，可先浏览器打开一次。')
}
