// 把 APK 作为 Release 资源发布，让任何人都能从仓库页面直接下载。
//
// 为什么用 Release 而不是只把 APK 提交进仓库：
//   1. 仓库首页右侧会出现醒目的 Releases 区域，点开就是下载链接；
//   2. 可以写安装说明，别人不用翻 README；
//   3. 与仓库内副本互为备份 —— 两条路走的是不同域名，国内网络下总有一条能通。
//
// 用法：
//   node tools/release-apk.mjs --dry                     只检查 token 与 uploads.github.com 连通性
//   node tools/release-apk.mjs <owner>/<repo>            发布 release/ 目录下的全部 APK
//   node tools/release-apk.mjs <owner>/<repo> --tag v1.2.0
//   node tools/release-apk.mjs <owner>/<repo> --apk <路径> [--apk <路径> ...]
//
// 默认发布 release/ 下的所有 .apk，并**直接用文件名作为资源名** ——
// 与仓库内副本保持一致，免得两个地方的下载文件名对不上。
import https from 'node:https'
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join, basename, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)
const flag = (name) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : null
}
const dry = argv.includes('--dry')
const target = argv.find((a) => !a.startsWith('--') && /^[^/]+\/[^/]+$/.test(a))
const tag = flag('--tag') || 'v1.1.0'
const version = tag.replace(/^v/, '')

if (!dry && !target) {
  console.error('用法: node tools/release-apk.mjs <owner>/<repo> [--tag v1.1.0] [--apk 路径]...')
  process.exit(2)
}
const [owner, repoName] = dry && !target ? ['wangwangrr', 'TrailRun'] : target.split('/')

// ---- 收集要发布的 APK ----
const explicit = []
argv.forEach((a, i) => {
  if (a === '--apk' && argv[i + 1]) explicit.push(argv[i + 1])
})

function collectApks() {
  if (explicit.length) return explicit.map((p) => ({ path: p, name: basename(p) }))
  const dir = join(ROOT, 'release')
  if (existsSync(dir)) {
    const found = readdirSync(dir)
      .filter((f) => f.toLowerCase().endsWith('.apk'))
      .sort()
    if (found.length) return found.map((f) => ({ path: join(dir, f), name: f }))
  }
  const fallback = join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
  return existsSync(fallback)
    ? [{ path: fallback, name: `TrailRun-${version}.apk` }]
    : []
}

const apks = collectApks()
if (!apks.length) {
  console.error('没找到任何 APK。先构建，或把 APK 放进 release/ 目录。')
  process.exit(2)
}
for (const a of apks) {
  if (!existsSync(a.path)) {
    console.error(`找不到 APK: ${a.path}`)
    process.exit(2)
  }
  a.buf = readFileSync(a.path)
  a.size = a.buf.length
}

let token = process.env.GITHUB_TOKEN || ''
if (!token) {
  process.stdout.write('粘贴 GitHub token（输入不回显）: ')
  token = await new Promise((resolve) => {
    const stdin = process.stdin
    stdin.setRawMode(true)
    stdin.resume()
    stdin.setEncoding('utf8')
    let buf = ''
    const onData = (ch) => {
      if (ch === '\r' || ch === '\n' || ch === '\u0004') {
        stdin.setRawMode(false)
        stdin.pause()
        stdin.removeListener('data', onData)
        process.stdout.write('\n')
        resolve(buf.trim())
      } else if (ch === '\u0003') process.exit(1)
      else if (ch === '\u007f') buf = buf.slice(0, -1)
      else buf += ch
    }
    stdin.on('data', onData)
  })
}

/** 通用请求。host 可切到 uploads.github.com —— 它与 api 是不同域名，连通性要单独验。 */
function request({ host, method, path, body, contentType, extraHeaders = {}, tries = 3 }) {
  // 先把 body 统一成 Buffer：对象直接交给 Buffer.byteLength 会抛 ERR_INVALID_ARG_TYPE。
  let payload = null
  if (body !== undefined && body !== null) {
    if (Buffer.isBuffer(body)) payload = body
    else if (typeof body === 'string') payload = Buffer.from(body, 'utf8')
    else payload = Buffer.from(JSON.stringify(body), 'utf8')
  }

  return new Promise((resolve, reject) => {
    const attempt = (n) => {
      let settled = false
      const fail = (err) => {
        if (settled) return
        settled = true
        if (n > 1) {
          process.stderr.write(`  网络错误，重试（还剩 ${n - 1} 次）：${err.message}\n`)
          setTimeout(() => attempt(n - 1), 2000)
        } else reject(err)
      }
      const headers = {
        'User-Agent': 'TrailRun-release',
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${token}`,
        ...extraHeaders,
      }
      if (payload) {
        headers['Content-Type'] = contentType || 'application/json'
        headers['Content-Length'] = payload.length
      }
      const req = https.request({ hostname: host, path, method, headers, timeout: 600000 }, (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          if (settled) return
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = JSON.parse(text)
          } catch {}
          if (res.statusCode >= 500 && n > 1) {
            settled = true
            process.stderr.write(`  HTTP ${res.statusCode}，重试（还剩 ${n - 1} 次）\n`)
            setTimeout(() => attempt(n - 1), 2500)
            return
          }
          settled = true
          resolve({ status: res.statusCode, json, text })
        })
        res.on('error', fail)
      })
      req.on('timeout', () => req.destroy(new Error('请求超时')))
      req.on('error', fail)
      if (payload) req.write(payload)
      req.end()
    }
    attempt(tries)
  })
}

const api = (method, path, body) => request({ host: 'api.github.com', method, path, body })

// ---- 1) 检查 token ----
const me = await api('GET', '/user')
if (me.status === 401) {
  console.error('\ntoken 无效或已被撤销（401）。重新生成一个再跑本脚本即可。')
  process.exit(1)
}
if (me.status !== 200) {
  console.error(`读取账号失败：HTTP ${me.status} ${me.text.slice(0, 200)}`)
  process.exit(1)
}
console.log(`目标    : ${owner}/${repoName}`)
console.log(`账号    : ${me.json.login}`)
console.log(`标签    : ${tag}`)
console.log('待发布  :')
for (const a of apks) console.log(`  ${a.name.padEnd(30)} ${(a.size / 1048576).toFixed(2)} MB`)

// ---- 2) 检查 uploads.github.com ----
console.log('\n测试 uploads.github.com 连通性...')
try {
  const probe = await request({ host: 'uploads.github.com', method: 'GET', path: '/', tries: 2 })
  console.log(`  HTTP ${probe.status}（能收到响应即视为可达）`)
} catch (e) {
  console.error(`  不可达：${e.message}`)
  console.error('\nuploads.github.com 不可达，无法上传 Release 资源。')
  console.error('替代方案：APK 已经在仓库 release/ 目录里，可以走 jsDelivr / raw 下载。')
  process.exit(1)
}

if (dry) {
  console.log('\n--dry：检查通过，未创建任何东西。')
  process.exit(0)
}

// ---- 3) 创建（或复用）Release ----
const light = apks.filter((a) => !/debug/i.test(a.name))
const heavy = apks.filter((a) => /debug/i.test(a.name))

const bodyLines = [
  '## 下载安装',
  '',
  `共 ${apks.length} 个包，功能完全相同，**任选一个**：`,
  '',
  '| 文件 | 体积 | 说明 |',
  '| --- | --- | --- |',
  ...apks.map((a) => {
    const isDebug = /debug/i.test(a.name)
    return `| \`${a.name}\` | ${(a.size / 1048576).toFixed(1)} MB | ${
      isDebug ? '不裁剪、可调试；精简版出问题时用它回退' : '**推荐**，经 R8 裁剪'
    } |`
  }),
  '',
  '两个包**签名相同**，可以互相覆盖安装，路线预设不会丢。',
  '',
  '## 安装步骤',
  '',
  '1. 下载上面的 APK 到手机',
  '2. 点击安装；系统提示「未知来源」时允许即可',
  '3. 打开应用，按「说明」页的 4 步完成首次配置',
  '',
  '## 说明',
  '',
  '- Android 8.0 及以上（minSdk 26），无需 Root',
  '- 首次使用需要在系统**开发者选项**里，把本应用设为「模拟位置信息应用」',
  '- 底图使用 OpenStreetMap，需要联网加载瓦片；也可以先浏览一遍再开离线模式',
  '- 供个人测试与学习使用；请遵守所在学校与平台的规定',
  '',
  `源码：https://github.com/${owner}/${repoName}`,
]
if (light.length && heavy.length) {
  bodyLines.push(
    '',
    '> 体积差 9 倍的原因：调试版不做代码裁剪，`classes.dex` 解压后 54 MB ——',
    '> 光 `material-icons-extended` 一个库就带几千个图标，实际只用到十几个。',
    '> 精简版开 R8 后 dex 降到 2.7 MB。'
  )
}

let release = null
const existing = await api('GET', `/repos/${owner}/${repoName}/releases/tags/${tag}`)
if (existing.status === 200) {
  release = existing.json
  console.log(`\nRelease ${tag} 已存在，复用它。`)
} else {
  const created = await api('POST', `/repos/${owner}/${repoName}/releases`, {
    tag_name: tag,
    name: `轨迹跑 TrailRun ${tag}`,
    body: bodyLines.join('\n'),
    draft: false,
    prerelease: false,
  })
  if (created.status !== 201) {
    console.error(`创建 Release 失败：HTTP ${created.status} ${created.text.slice(0, 300)}`)
    process.exit(1)
  }
  release = created.json
  console.log(`\n已创建 Release: ${release.html_url}`)
}

// ---- 4) 逐个上传 ----
const assets = await api('GET', `/repos/${owner}/${repoName}/releases/${release.id}/assets`)
const existingAssets = assets.json || []

for (const a of apks) {
  // 同名资源已存在就先删掉，否则 GitHub 返回 422 already_exists
  const dup = existingAssets.find((x) => x.name === a.name)
  if (dup) {
    console.log(`已存在同名资源（${dup.name}），先删除。`)
    await api('DELETE', `/repos/${owner}/${repoName}/releases/assets/${dup.id}`)
  }

  process.stdout.write(`上传 ${a.name}（${(a.size / 1048576).toFixed(2)} MB）... `)
  const started = Date.now()
  const up = await request({
    host: 'uploads.github.com',
    method: 'POST',
    path: `/repos/${owner}/${repoName}/releases/${release.id}/assets?name=${encodeURIComponent(a.name)}`,
    body: a.buf,
    contentType: 'application/vnd.android.package-archive',
    tries: 3,
  })
  if (up.status !== 201) {
    console.error(`\n上传失败：HTTP ${up.status} ${up.text.slice(0, 300)}`)
    process.exit(1)
  }
  const secs = ((Date.now() - started) / 1000).toFixed(0)
  const okSize = up.json.size === a.size
  console.log(`完成 ${secs}s  大小 ${okSize ? '✓ 一致' : '✗ 不一致！'}`)
  if (up.json.digest) console.log(`  digest: ${up.json.digest}`)
}

console.log(`\nRelease 页面：${release.html_url}`)
console.log('任何人打开仓库首页，右侧 Releases 区就能看到并直接下载，不需要登录。')
