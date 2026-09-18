// 验证 GitHub 上发布的 APK 与本地构建产物是同一个文件。
//
// 三条校验，逐级加严：
//   1. 大小
//   2. git blob sha（sha1("blob <长度>\0" + 内容)）—— 能证明仓库里那一份没被改动
//   3. 把 Release 资源整个下载下来算 SHA256 —— 这才是别人实际拿到的东西
//
// 用法: node tools/verify-apk.mjs
import { readFileSync, statSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import https from 'node:https'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const OWNER = 'wangwangrr'
const REPO = 'TrailRun'
const TAG = 'v1.0.0'
const ASSET = 'TrailRun-1.0.0.apk'
const localPath = join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')

const local = readFileSync(localPath)
const localSha256 = createHash('sha256').update(local).digest('hex')
const localBlobSha = createHash('sha1')
  .update(Buffer.from(`blob ${local.length}\0`, 'utf8'))
  .update(local)
  .digest('hex')

console.log('本地构建产物')
console.log(`  路径    : app/build/outputs/apk/debug/app-debug.apk`)
console.log(`  大小    : ${local.length} B`)
console.log(`  SHA256  : ${localSha256}`)
console.log(`  blob sha: ${localBlobSha}`)

function get(url, { timeoutMs = 60000, binary = false } = {}) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let got = 0
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
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume()
          const next = new URL(res.headers.location, url).toString()
          return get(next, { timeoutMs, binary }).then(resolve, reject)
        }
        if (res.statusCode !== 200) {
          res.resume()
          return reject(new Error(`HTTP ${res.statusCode}`))
        }
        res.on('data', (c) => {
          chunks.push(c)
          got += c.length
          if (binary && Math.floor(got / (4 * 1048576)) !== Math.floor((got - c.length) / (4 * 1048576))) {
            process.stdout.write(`\r  已下载 ${(got / 1048576).toFixed(1)} MB`)
          }
        })
        res.on('end', () => {
          if (binary) process.stdout.write('\r')
          const buf = Buffer.concat(chunks)
          resolve({ status: res.statusCode, buf, text: binary ? null : buf.toString('utf8') })
        })
        // 关键：把「已经下了多少」带进错误里 —— 断在 0.3 MB 和断在 16 MB 是完全不同的两件事
        res.on('error', (e) =>
          reject(new Error(`响应流中断（已收 ${(got / 1048576).toFixed(2)} MB）：${e.code || e.message}`))
        )
      }
    )
    req.on('timeout', () => req.destroy(new Error(`请求超时（已收 ${(got / 1048576).toFixed(2)} MB）`)))
    req.on('error', (e) =>
      reject(new Error(`连接错误（已收 ${(got / 1048576).toFixed(2)} MB）：${e.code || e.message || '未知'}`))
    )
  })
}

/** 17.6 MB 在这条链路上值得重试几次 —— 中断通常发生在半途。 */
async function downloadWithRetry(url, tries = 3) {
  for (let i = 1; i <= tries; i++) {
    try {
      return await get(url, { binary: true, timeoutMs: 600000 })
    } catch (e) {
      console.log(`  第 ${i}/${tries} 次失败：${e.message}`)
      if (i < tries) await new Promise((s) => setTimeout(s, 4000))
    }
  }
  return null
}

// ---- 2) 仓库里那一份 ----
console.log('\n仓库内副本 release/' + ASSET)
try {
  const t = JSON.parse(
    (await get(`https://api.github.com/repos/${OWNER}/${REPO}/git/trees/main?recursive=1`)).text
  )
  const hit = (t.tree || []).find((e) => e.path === `release/${ASSET}`)
  if (!hit) {
    console.log('  !! 仓库里找不到这个文件')
  } else {
    console.log(`  大小    : ${hit.size} B   ${hit.size === local.length ? '✓ 一致' : '✗ 不一致'}`)
    console.log(`  blob sha: ${hit.sha}   ${hit.sha === localBlobSha ? '✓ 与本地逐字节一致' : '✗ 不一致'}`)
  }
} catch (e) {
  console.log(`  读取失败：${e.message}`)
}

// ---- 3) Release 资源：整个下载下来 ----
// ---- 3) Release 资源 ----
// 先查 API 里的 digest —— GitHub 自己算的 sha256，走 api.github.com（这条链路稳定）。
// 比「完整下载再算」可靠得多：github.com 时通时断，17.6 MB 常常连不上。
console.log('\nRelease 资源（别人点「下载」实际拿到的东西）')
let apiDigestOk = false
try {
  const rel = JSON.parse(
    (await get(`https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${TAG}`)).text
  )
  const a = (rel.assets || []).find((x) => x.name === ASSET)
  if (!a) {
    console.log('  !! Release 里找不到这个资源')
  } else {
    console.log(`  名称    : ${a.name}`)
    console.log(`  大小    : ${a.size} B   ${a.size === local.length ? '✓ 一致' : '✗ 不一致'}`)
    console.log(`  下载次数: ${a.download_count}`)
    if (a.digest) {
      const listed = String(a.digest).replace(/^sha256:/, '')
      apiDigestOk = listed === localSha256
      console.log(`  digest  : ${a.digest}`)
      console.log(
        `  结论    : ${apiDigestOk ? '✓ GitHub 记录的 SHA256 与本地构建产物完全相同' : '✗ digest 不匹配！'}`
      )
    } else {
      console.log('  digest  : （GitHub 未返回该字段）')
    }
    console.log(`  下载页  : ${a.browser_download_url}`)
  }
} catch (e) {
  console.log(`  查询失败：${e.message}`)
}

// 再试一次真下载作为旁证（连不上不算失败，网络问题）
console.log('\n（附加）完整下载比对')
try {
  const url = `https://github.com/${OWNER}/${REPO}/releases/download/${TAG}/${ASSET}`
  const started = Date.now()
  const r = await downloadWithRetry(url, 2)
  if (!r) {
    console.log('  未下完 —— github.com 当前不可达。这不影响上面的 digest 结论。')
  } else {
    const secs = ((Date.now() - started) / 1000).toFixed(1)
    const dlSha256 = createHash('sha256').update(r.buf).digest('hex')
    console.log(`  大小    : ${r.buf.length} B   ${r.buf.length === local.length ? '✓ 一致' : '✗ 不一致'}`)
    console.log(`  SHA256  : ${dlSha256}   用时 ${secs}s`)
    console.log(`  结论    : ${dlSha256 === localSha256 ? '✓ 与本地完全相同' : '✗ 不是同一个文件！'}`)
  }
} catch (e) {
  console.log(`  ${e.message}`)
}
