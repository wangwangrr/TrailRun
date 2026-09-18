// 把 APK 作为 Release 资源发布，让任何人都能从仓库页面直接下载。
//
// 为什么用 Release 而不是把 APK 提交进仓库：
//   1. 仓库首页右侧会出现醒目的 Releases 区域，点开就是下载链接；
//   2. 资源不计入仓库体积，不会让 clone 越来越慢（每次更新 APK 都会在 git 历史里留一份）；
//   3. 可以写安装说明，别人不用翻 README。
//
// 用法：
//   node tools/release-apk.mjs --dry                     只检查 token 与 uploads.github.com 连通性
//   node tools/release-apk.mjs <owner>/<repo>            发布（默认 tag v1.0.0）
//   node tools/release-apk.mjs <owner>/<repo> --tag v1.1.0 --apk <路径>
import https from 'node:https'
import { readFileSync, statSync, existsSync } from 'node:fs'
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
const tag = flag('--tag') || 'v1.0.0'

const apkPath =
  flag('--apk') || join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')

if (!dry && !target) {
  console.error('用法: node tools/release-apk.mjs <owner>/<repo> [--tag v1.0.0] [--apk 路径]')
  process.exit(2)
}
if (!existsSync(apkPath)) {
  console.error(`找不到 APK: ${apkPath}\n先跑 tools\\build-apk.ps1 生成。`)
  process.exit(2)
}
const [owner, repoName] = dry ? ['wangwangrr', 'TrailRun'] : target.split('/')
const apk = readFileSync(apkPath)
const apkName = `TrailRun-${tag.replace(/^v/, '')}.apk`

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

/** 通用请求。host 可切到 uploads.github.com —— 它和 api 是不同的域名，连通性要单独验。 */
function request({ host, method, path, body, contentType, extraHeaders = {}, tries = 3 }) {
  // 先把 body 统一成 Buffer。
  // 之前直接把对象丢给 Buffer.byteLength，会抛 ERR_INVALID_ARG_TYPE ——
  // 上传 APK 走的是 Buffer 分支，创建 Release 走的是对象分支，两条路都得通。
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
      const req = https.request({ hostname: host, path, method, headers, timeout: 300000 }, (res) => {
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
  console.error('\ntoken 无效或已被撤销（401）。')
  console.error('如果是为了安全把它删掉了，那是对的 —— 重新生成一个再跑本脚本即可。')
  process.exit(1)
}
if (me.status !== 200) {
  console.error(`读取账号失败：HTTP ${me.status} ${me.text.slice(0, 200)}`)
  process.exit(1)
}
console.log(`目标    : ${owner}/${repoName}`)
console.log(`账号    : ${me.json.login}`)
console.log(`APK     : ${basename(apkPath)}  ${(apk.length / 1048576).toFixed(2)} MB`)
console.log(`资源名  : ${apkName}`)
console.log(`标签    : ${tag}`)

// ---- 2) 检查 uploads.github.com ----
console.log('\n测试 uploads.github.com 连通性...')
let uploadsOk = false
try {
  const probe = await request({
    host: 'uploads.github.com',
    method: 'GET',
    path: '/',
    tries: 2,
  })
  console.log(`  HTTP ${probe.status}（能收到响应即视为可达）`)
  uploadsOk = true
} catch (e) {
  console.log(`  不可达：${e.message}`)
}
if (!uploadsOk) {
  console.error('\nuploads.github.com 不可达，无法上传 Release 资源。')
  console.error('替代方案：把 APK 直接提交进仓库（不需要这个域名）。')
  process.exit(1)
}

if (dry) {
  console.log('\n--dry：检查通过，未创建任何东西。')
  process.exit(0)
}

// ---- 3) 创建（或复用）Release ----
let release = null
const existing = await api('GET', `/repos/${owner}/${repoName}/releases/tags/${tag}`)
if (existing.status === 200) {
  release = existing.json
  console.log(`\nRelease ${tag} 已存在，复用它。`)
} else {
  const body = [
    '## 下载安装',
    '',
    `1. 点下面的 **${apkName}** 下载（${(apk.length / 1048576).toFixed(1)} MB）`,
    '2. 手机上打开这个文件安装；系统会提示「未知来源」，允许即可',
    '3. 打开应用后，按「说明」页的 4 步设置完成首次配置',
    '',
    '## 说明',
    '',
    '- Android 8.0 及以上（minSdk 26）',
    '- 无需 Root。首次使用需要在系统的**开发者选项**里，把本应用设为「模拟位置信息应用」',
    '- 底图使用 OpenStreetMap，需要联网加载瓦片；也可以先浏览一遍再开启离线模式',
    '- 这是 debug 签名版本，供个人测试与学习使用；请遵守所在学校与平台的规定',
    '',
    `源码：https://github.com/${owner}/${repoName}`,
  ].join('\n')

  const created = await api('POST', `/repos/${owner}/${repoName}/releases`, {
    tag_name: tag,
    name: `轨迹跑 TrailRun ${tag}`,
    body,
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

// ---- 4) 上传 APK ----
// 先看同名资源在不在，在就删掉 —— 否则 GitHub 会报 422 already_exists。
const assets = await api('GET', `/repos/${owner}/${repoName}/releases/${release.id}/assets`)
const dup = (assets.json || []).find((a) => a.name === apkName)
if (dup) {
  console.log(`已存在同名资源（${dup.name}），先删除。`)
  await api('DELETE', `/repos/${owner}/${repoName}/releases/assets/${dup.id}`)
}

console.log(`上传 ${apkName}（${(apk.length / 1048576).toFixed(2)} MB）...`)
const started = Date.now()
const up = await request({
  host: 'uploads.github.com',
  method: 'POST',
  path: `/repos/${owner}/${repoName}/releases/${release.id}/assets?name=${encodeURIComponent(apkName)}`,
  body: apk,
  contentType: 'application/vnd.android.package-archive',
  tries: 3,
})
if (up.status !== 201) {
  console.error(`上传失败：HTTP ${up.status} ${up.text.slice(0, 300)}`)
  process.exit(1)
}
const secs = ((Date.now() - started) / 1000).toFixed(0)
console.log(`  完成，用时 ${secs}s`)
console.log(`  下载地址：${up.json.browser_download_url}`)
console.log(`  大小校验：上传 ${up.json.size} B / 本地 ${apk.length} B ${up.json.size === apk.length ? '一致' : '不一致！'}`)

console.log(`\nRelease 页面：${release.html_url}`)
console.log('任何人打开仓库首页，右侧 Releases 区就能看到并直接下载，不需要登录。')
