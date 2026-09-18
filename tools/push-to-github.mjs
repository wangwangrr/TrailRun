#!/usr/bin/env node
// 用 GitHub REST API 把本项目推上去 —— 不需要 git，也不需要能打开 github.com。
//
// 为什么不用 git push：
//   这台机器没装 git；github.com 时通时不通（实测 5 分钟内出现过 200 / 10s 超时 /
//   DNS 解析失败三种结果）；而 api.github.com 一直稳定可用。
//   所以直接调 Git Data API：blob → tree → commit → ref，一次提交，干净利落。
//
// 用法：
//   node tools/push-to-github.mjs --dry                     只列出会提交哪些文件，不联网
//   node tools/push-to-github.mjs <owner>/<repo>            推送到已有仓库
//   node tools/push-to-github.mjs <owner>/<repo> --create   顺便创建公开仓库
//
// token：优先后台读环境变量 GITHUB_TOKEN；没有就交互式输入（不回显、不落盘、不进命令历史）。
//
// 需要 token 的权限：
//   - 推到已有仓库：fine-grained token 给该仓库 Contents: Read and write，或 classic token 勾 repo
//   - 加 --create  ：另外需要 Account permissions → Administration: Read and write
//   - 要推 .github/workflows/ 下的文件：必须**额外**有 workflow scope。
//     缺了它，整棵 tree 都会被拒绝，而且 GitHub 把它伪装成 `404 Not Found`
//     （不是 403）—— 看起来像仓库不存在或端点写错。脚本会自动跳过这些文件并提示。
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative, sep, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import https from 'node:https'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

// ---------------------------------------------------------------- .gitignore

/**
 * 把 .gitignore 的一行转成正则。
 *
 * 只实现本项目实际用到的语法（`*` 通配、`/` 开头锚定、`/` 结尾只匹配目录）。
 * 完整实现 gitignore 语义是另一个量级的工作，而这里只需要保证
 * 「.toolchain / .gradle-home / app/build 这些重量级目录一定被排除」——
 * 漏一个就是几个 GB 的误提交，所以下面还有一道体积兜底。
 */
function toRegex(line) {
  let p = line
  let dirOnly = false
  let anchored = false
  if (p.endsWith('/')) {
    dirOnly = true
    p = p.slice(0, -1)
  }
  if (p.startsWith('/')) {
    anchored = true
    p = p.slice(1)
  }
  const rx = p
    .replace(/[.+^${}()|[\]\\]/g, '\\$&') // 转义正则元字符（* 和 ? 留着，下面单独处理）
    .replace(/\*/g, '[^/]*')
    .replace(/\?/g, '[^/]')
  return new RegExp((anchored ? '^' : '(^|/)') + rx + (dirOnly ? '/' : '(/|$)'))
}

const ignoreRules = readFileSync(join(ROOT, '.gitignore'), 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#'))
  .map((line) => {
    const negated = line.startsWith('!')
    return { rx: toRegex(negated ? line.slice(1) : line), negated }
  })

/**
 * 按 .gitignore 语义判断：**后面的规则覆盖前面的**，`!` 表示取消忽略。
 *
 * 这一点是必需的，不是锦上添花：`*.apk` 会忽略所有 APK，
 * 而 `!release/*.apk` 要把 release 目录里那一份捞回来 ——
 * 那是给国内网络准备的下载副本，漏掉它整个方案就没意义了。
 */
const isIgnored = (relPath) => {
  let ignored = false
  for (const rule of ignoreRules) {
    if (rule.rx.test(relPath)) ignored = !rule.negated
  }
  return ignored
}

// ---------------------------------------------------------------- 收集文件

const MAX_FILE_BYTES = 40 * 1024 * 1024 // GitHub 单文件硬上限是 100MB，这里留足余量
const MAX_TOTAL_BYTES = 60 * 1024 * 1024

function walk(dir, out) {
  let entries
  try {
    entries = readdirSync(dir)
  } catch {
    return out
  }
  for (const name of entries) {
    if (name === '.git') continue
    const full = join(dir, name)
    const rel = relative(ROOT, full).split(sep).join('/')
    let st
    try {
      st = statSync(full) // 断掉的符号链接会在这里抛，直接跳过
    } catch {
      continue
    }
    if (st.isDirectory()) {
      if (isIgnored(rel + '/')) continue
      walk(full, out)
    } else if (st.isFile()) {
      if (isIgnored(rel)) continue
      out.push({ full, rel, size: st.size })
    }
  }
  return out
}

const files = walk(ROOT, []).sort((a, b) => a.rel.localeCompare(b.rel))
const totalBytes = files.reduce((s, f) => s + f.size, 0)

// 体积兜底：忽略规则一旦写错，这里必须拦住，而不是把几 GB 传上去。
const oversize = files.filter((f) => f.size > MAX_FILE_BYTES)
if (oversize.length) {
  console.error('以下文件超过单文件上限，请把它们加进 .gitignore：')
  for (const f of oversize) console.error(`  ${f.rel}  ${(f.size / 1048576).toFixed(1)} MB`)
  process.exit(1)
}
if (totalBytes > MAX_TOTAL_BYTES) {
  console.error(`总大小 ${(totalBytes / 1048576).toFixed(1)} MB 超过上限，忽略规则可能漏了目录：`)
  const byTop = {}
  for (const f of files) {
    const top = f.rel.split('/')[0]
    byTop[top] = (byTop[top] || 0) + f.size
  }
  for (const [k, v] of Object.entries(byTop).sort((a, b) => b[1] - a[1]).slice(0, 8)) {
    console.error(`  ${k}  ${(v / 1048576).toFixed(1)} MB`)
  }
  process.exit(1)
}

const kb = (n) => (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' KB'

if (process.argv.includes('--dry')) {
  console.log(`将要提交 ${files.length} 个文件，共 ${(totalBytes / 1024).toFixed(1)} KB\n`)
  const byTop = {}
  for (const f of files) {
    const top = f.rel.includes('/') ? f.rel.split('/')[0] + '/' : '(根目录)'
    byTop[top] = byTop[top] || { n: 0, b: 0 }
    byTop[top].n++
    byTop[top].b += f.size
  }
  for (const [k, v] of Object.entries(byTop).sort((a, b) => b[1].b - a[1].b)) {
    console.log(`  ${k.padEnd(14)} ${String(v.n).padStart(4)} 个  ${kb(v.b)}`)
  }
  console.log('\n最大的 8 个文件：')
  for (const f of [...files].sort((a, b) => b.size - a.size).slice(0, 8)) {
    console.log(`  ${kb(f.size).padStart(9)}  ${f.rel}`)
  }
  console.log('\n已确认排除（不应出现在上面）：')
  for (const probe of ['.toolchain/', '.gradle-home/', 'app/build/', '.gradle/', 'local.properties']) {
    const hit = files.some((f) => f.rel === probe.slice(0, -1) || f.rel.startsWith(probe))
    console.log(`  ${probe.padEnd(20)} ${hit ? '!! 仍在列表中' : '已排除'}`)
  }
  process.exit(0)
}

// ---------------------------------------------------------------- GitHub API

/**
 * 调一次 GitHub API。
 *
 * 带重试是必需的，不是保险 —— 而且重试范围必须覆盖**所有非 2xx**，
 * 不能只重试 5xx。实测（2026-09，本机网络）：
 *   - 94 个 blob 上传中，第 81 个开始返回
 *     400 "We received a malformed request from your client"；
 *   - 同样的请求重跑一遍，同样的文件却全部成功；
 *   - 紧接着的 `POST /git/trees` 还会凭空返回 404 Not Found，
 *     而同一端点用同样的 token 单独调用是 201。
 * 也就是说这条链路上，400/404 都可能是**传输被干扰**的产物，而不是真的错误。
 *
 * 代价可控：就算真是权限问题，重试 4 次也只多花十来秒。
 */
function api(method, path, token, body, tries = 4) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : JSON.stringify(body)

    const attempt = (n) => {
      let settled = false
      const fail = (err) => {
        if (settled) return
        settled = true
        if (n > 1) {
          process.stderr.write(`  网络错误，重试（还剩 ${n - 1} 次）：${err.message}\n`)
          setTimeout(() => attempt(n - 1), 1500)
        } else {
          reject(err)
        }
      }

      const headers = {
        'User-Agent': 'TrailRun-push-script',
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        Authorization: `Bearer ${token}`,
      }
      if (data) {
        headers['Content-Type'] = 'application/json'
        headers['Content-Length'] = Buffer.byteLength(data)
      }

      const req = https.request(
        { hostname: 'api.github.com', path, method, headers, timeout: 60000 },
        (res) => {
          const chunks = []
          res.on('data', (c) => chunks.push(c))
          res.on('end', () => {
            if (settled) return
            const text = Buffer.concat(chunks).toString('utf8')
            let json = null
            try {
              json = JSON.parse(text)
            } catch {
              /* 有的响应（比如 204）没有 body */
            }
            const ok = res.statusCode >= 200 && res.statusCode < 300
            if (!ok && n > 1) {
              settled = true
              const why = json?.message || text.slice(0, 60)
              process.stderr.write(`  HTTP ${res.statusCode}（${why}），重试（还剩 ${n - 1} 次）\n`)
              setTimeout(() => attempt(n - 1), 1200)
              return
            }
            settled = true
            resolve({ status: res.statusCode, json, text, headers: res.headers })
          })
          res.on('error', fail)
        }
      )
      req.on('timeout', () => req.destroy(new Error('请求超时')))
      req.on('error', fail)
      if (data) req.write(data)
      req.end()
    }

    attempt(tries)
  })
}

/** 失败时把 GitHub 的 message 原样带出来 —— 认证/权限问题全靠它定位。 */
function must(res, what) {
  if (res.status >= 200 && res.status < 300) return res.json
  const msg = res.json?.message || res.text.slice(0, 200)
  const err = new Error(`${what} 失败：HTTP ${res.status} — ${msg}`)
  if (res.status === 401) err.hint = 'token 无效或已过期。'
  if (res.status === 403) err.hint = 'token 权限不足，或触发了 API 限流。'
  if (res.status === 404) err.hint = '仓库不存在，或 token 看不到它（fine-grained token 需要显式选中该仓库）。'
  throw err
}

function askHidden(prompt) {
  return new Promise((resolve) => {
    process.stdout.write(prompt)
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
      } else if (ch === '\u0003') {
        process.stdout.write('\n已取消\n')
        process.exit(1)
      } else if (ch === '\u007f' || ch === '\b') {
        if (buf) {
          buf = buf.slice(0, -1)
          process.stdout.write('\b \b')
        }
      } else {
        buf += ch
        process.stdout.write('*')
      }
    }
    stdin.on('data', onData)
  })
}

// ---------------------------------------------------------------- 主流程

const args = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const wantCreate = process.argv.includes('--create')
const target = args[0]

if (!target || !/^[^/\s]+\/[^/\s]+$/.test(target)) {
  console.error('用法: node tools/push-to-github.mjs <owner>/<repo> [--create]')
  console.error('      node tools/push-to-github.mjs --dry')
  process.exit(2)
}
const [owner, repoName] = target.split('/')

let token = process.env.GITHUB_TOKEN || ''
if (!token) token = await askHidden('粘贴 GitHub token（输入不回显）: ')
if (!token) {
  console.error('没有 token，退出。')
  process.exit(2)
}

console.log(`\n目标仓库: ${owner}/${repoName}`)
console.log(`文件数  : ${files.length}  共 ${(totalBytes / 1024).toFixed(1)} KB\n`)

const meRes = await api('GET', '/user', token)
const me = must(meRes, '读取账号信息')
console.log(`已认证  : ${me.login}`)

// GitHub 要求：通过 API 写入 `.github/workflows/` 下的文件，token 必须带 `workflow` scope。
// 缺了它，**整棵 tree 都会被拒绝**，而且伪装成 `404 Not Found` ——
// 看起来像「仓库不存在」或「端点写错」，极难排查。
// 实测：95 个文件里只要有那一个 build-apk.yml，全部失败；
// 把其余 94 个单独提交就一切正常，二分才把这一条揪出来。
// 这里提前判断，省得白白重试四轮。
const scopes = String(meRes.headers?.['x-oauth-scopes'] ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)
// fine-grained token 不带 x-oauth-scopes 头，判断不了 —— 那时只能失败了再退。
const scopeKnown = scopes.length > 0
const hasWorkflowScope = !scopeKnown || scopes.includes('workflow')

if (me.login.toLowerCase() !== owner.toLowerCase()) {
  console.log(`注意    : 登录账号是 ${me.login}，目标却是 ${owner} 名下 —— 若是组织仓库属正常，否则会 403。`)
}

if (wantCreate) {
  const created = await api('POST', '/user/repos', token, {
    name: repoName,
    private: false,
    has_issues: true,
    has_wiki: false,
    description: '轨迹跑 TrailRun —— 校园跑轨迹模拟与虚拟定位（Android / Kotlin / Compose）',
  })
  if (created.status === 201) console.log(`已创建公开仓库: ${created.json.html_url}`)
  else if (created.status === 422) console.log('仓库已存在，继续推送。')
  else must(created, '创建仓库')
}

const info = must(await api('GET', `/repos/${owner}/${repoName}`, token), '读取仓库信息')
const branch = info.default_branch || 'main'
console.log(`默认分支: ${branch}`)

// 空仓库没有 ref，会返回 404 —— 这不是错误。
const refRes = await api('GET', `/repos/${owner}/${repoName}/git/ref/heads/${branch}`, token)
const parentSha = refRes.status === 200 ? refRes.json.object.sha : null
console.log(parentSha ? `已有提交: ${parentSha.slice(0, 7)}（将作为父提交）` : '空仓库：这是第一个提交')

// 1) 上传 blob。
//
// 并发压到 4（原来是 8）：这条链路对大请求很敏感，同时开太多更容易被干扰。
// 文本文件改用 `utf-8` 编码直传 —— base64 会让体积膨胀 33%，
// 而 70 KB 的 MainRunScreen.kt 正是第一个被打回 400 的文件，能省就省。
console.log(`\n上传 ${files.length} 个 blob...`)

/** 二进制才用 base64；文本用 utf-8 直传（严格解码失败则回退 base64）。 */
function blobPayload(buf) {
  for (let i = 0; i < Math.min(buf.length, 8192); i++) {
    if (buf[i] === 0) return { content: buf.toString('base64'), encoding: 'base64' }
  }
  try {
    return { content: new TextDecoder('utf-8', { fatal: true }).decode(buf), encoding: 'utf-8' }
  } catch {
    return { content: buf.toString('base64'), encoding: 'base64' }
  }
}

const tree = new Array(files.length)
let done = 0
let cursor = 0
const CONCURRENCY = 4

async function worker() {
  while (true) {
    const i = cursor++
    if (i >= files.length) return
    const f = files[i]
    const res = await api(
      'POST',
      `/repos/${owner}/${repoName}/git/blobs`,
      token,
      blobPayload(readFileSync(f.full))
    )
    const j = must(res, `上传 ${f.rel}`)
    if (!j?.sha || !/^[0-9a-f]{40}$/.test(j.sha)) {
      throw new Error(`上传 ${f.rel} 返回的 sha 不合法：${JSON.stringify(j?.sha)}`)
    }
    tree[i] = { path: f.rel, mode: '100644', type: 'blob', sha: j.sha }
    done++
    if (done % 10 === 0 || done === files.length) {
      process.stdout.write(`\r  ${done}/${files.length}`)
    }
  }
}
await Promise.all(Array.from({ length: CONCURRENCY }, worker))
process.stdout.write('\n')

// 数组完整性自检：稀疏数组会被 JSON.stringify 变成 null，让整棵树报废。
// 这类错误在 GitHub 那侧只表现为一句含糊的 400/404，非常难查，所以在这里拦住。
const holes = []
for (let i = 0; i < tree.length; i++) if (!tree[i]) holes.push(i)
if (holes.length) {
  console.error(`内部错误：tree 有 ${holes.length} 个空洞（索引 ${holes.slice(0, 8).join(',')}…）`)
  process.exit(1)
}

// 2) 建树
const isWorkflowFile = (p) => p.startsWith('.github/workflows/')
const workflowEntries = tree.filter((e) => isWorkflowFile(e.path))

let treeList = tree
const skippedWorkflow = []
if (!hasWorkflowScope && workflowEntries.length) {
  console.warn(
    `\n注意：token 的 scope 是 [${scopes.join(', ')}]，不含 workflow，` +
      `无法通过 API 写入 .github/workflows/ 下的文件。\n` +
      `      本次先跳过 ${workflowEntries.length} 个：${workflowEntries.map((e) => e.path).join(', ')}`
  )
  skippedWorkflow.push(...workflowEntries.map((e) => e.path))
  treeList = tree.filter((e) => !isWorkflowFile(e.path))
}

console.log(`tree 请求体 ${(Buffer.byteLength(JSON.stringify({ tree: treeList })) / 1024).toFixed(1)} KB`)
let treeRes = await api('POST', `/repos/${owner}/${repoName}/git/trees`, token, { tree: treeList })

// 兜底：fine-grained token 读不到 scope，只能等它失败再退一步。
if (treeRes.status === 404 && !scopeKnown && treeList.length === tree.length) {
  console.warn('tree 被拒（404），疑似 workflow 权限问题，排除 .github/workflows/ 后重试。')
  treeList = tree.filter((e) => !isWorkflowFile(e.path))
  treeRes = await api('POST', `/repos/${owner}/${repoName}/git/trees`, token, { tree: treeList })
}
const treeJson = must(treeRes, '创建 tree')
console.log(`tree    : ${treeJson.sha.slice(0, 7)}`)

// 3) 建提交
const commitBody = {
  message:
    '轨迹跑 TrailRun：校园跑轨迹模拟与虚拟定位\n\n' +
    'Android / Kotlin / Jetpack Compose，无需 Root。\n\n' +
    '- 手绘或点选规划路线，可预设起点终点与轨迹\n' +
    '- 自定义速度、环线 / 折返、目标距离\n' +
    '- 高清 512px OSM 底图，端点可测速切换\n' +
    '- 启动页 → 口令页 → 主界面',
  tree: treeJson.sha,
}
if (parentSha) commitBody.parents = [parentSha]
const commitRes = must(
  await api('POST', `/repos/${owner}/${repoName}/git/commits`, token, commitBody),
  '创建 commit'
)
console.log(`commit  : ${commitRes.sha.slice(0, 7)}`)

// 4) 移动分支指针
if (parentSha) {
  must(
    await api('PATCH', `/repos/${owner}/${repoName}/git/refs/heads/${branch}`, token, {
      sha: commitRes.sha,
      force: false,
    }),
    '更新分支'
  )
} else {
  must(
    await api('POST', `/repos/${owner}/${repoName}/git/refs`, token, {
      ref: `refs/heads/${branch}`,
      sha: commitRes.sha,
    }),
    '创建分支'
  )
}

// 顺手补一个仓库描述：新建仓库时如果没填，页面顶部会是空的。
// 只在当前为空时才写，免得覆盖掉你后来手动改的描述。
if (!info.description) {
  const patched = await api('PATCH', `/repos/${owner}/${repoName}`, token, {
    description: '轨迹跑 TrailRun —— 校园跑轨迹模拟与虚拟定位（Android / Kotlin / Compose）',
  })
  if (patched.status >= 200 && patched.status < 300) console.log('已补上仓库描述。')
}

console.log(`\n完成 -> ${info.html_url}`)
console.log(`已提交 ${treeList.length} 个文件（本地共 ${files.length} 个）`)

if (skippedWorkflow.length) {
  console.log(
    '\n想让 GitHub Actions 自动编译 APK，还得把 workflow 文件弄上去，二选一：\n' +
      '  A) 重新生成 token 时把 workflow 和 repo 一起勾上，再跑一次本脚本；\n' +
      '  B) 在仓库网页上手动新建 .github/workflows/build-apk.yml，粘贴本地文件内容。'
  )
} else {
  console.log('\nGitHub Actions 会在 push 后自动编译 APK，在仓库的 Actions 页面可以下载构建产物。')
}
