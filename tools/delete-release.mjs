// 删除一个 GitHub Release（可选连同 git tag）—— 用于撤下有问题的版本。
//
// 为什么需要：发出去的坏包只要还留在 Releases 页面上，别人照样会下载到。
// 既然发布是脚本化的，撤回也该是。
//
// 用法：
//   node tools/delete-release.mjs <owner>/<repo> <tag>              删除 Release 和 tag
//   node tools/delete-release.mjs <owner>/<repo> <tag> --keep-tag   只删 Release，保留 tag
//
// token：读环境变量 GITHUB_TOKEN（需要 repo scope；走 api.github.com，该域名通常可达）。
import https from 'node:https'

const argv = process.argv.slice(2)
const keepTag = argv.includes('--keep-tag')
const positional = argv.filter((a) => !a.startsWith('--'))
const target = positional[0]
const tag = positional[1]

if (!target || !tag) {
  console.error('用法: node tools/delete-release.mjs <owner>/<repo> <tag> [--keep-tag]')
  process.exit(2)
}
const [owner, repo] = target.split('/')
const token = process.env.GITHUB_TOKEN || ''
if (!token) {
  console.error('需要环境变量 GITHUB_TOKEN')
  process.exit(2)
}

function api(method, path) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: 'api.github.com',
        path,
        method,
        headers: {
          'User-Agent': 'TrailRun-release-admin',
          Accept: 'application/vnd.github+json',
          Authorization: `Bearer ${token}`,
        },
        timeout: 60000,
      },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = JSON.parse(text)
          } catch {}
          resolve({ status: res.statusCode, json, text })
        })
        res.on('error', reject)
      }
    )
    req.on('timeout', () => req.destroy(new Error('超时')))
    req.on('error', reject)
    req.end()
  })
}

console.log(`目标: ${owner}/${repo}  tag: ${tag}\n`)

const found = await api('GET', `/repos/${owner}/${repo}/releases/tags/${tag}`)
if (found.status === 404) {
  console.log('这个 tag 没有对应的 Release，跳过。')
} else if (found.status !== 200) {
  console.error(`查询失败：HTTP ${found.status} ${found.text.slice(0, 200)}`)
  process.exit(1)
} else {
  const rel = found.json
  console.log(`找到 Release #${rel.id}「${rel.name}」，含 ${rel.assets.length} 个资源：`)
  for (const a of rel.assets) {
    console.log(`  - ${a.name}  ${(a.size / 1048576).toFixed(2)} MB  下载 ${a.download_count} 次`)
  }
  const del = await api('DELETE', `/repos/${owner}/${repo}/releases/${rel.id}`)
  if (del.status !== 204) {
    console.error(`删除 Release 失败：HTTP ${del.status} ${del.text.slice(0, 200)}`)
    process.exit(1)
  }
  console.log('Release 已删除。')
}

if (!keepTag) {
  const delTag = await api('DELETE', `/repos/${owner}/${repo}/git/refs/tags/${tag}`)
  if (delTag.status === 204) console.log(`git tag ${tag} 已删除。`)
  else if (delTag.status === 404) console.log(`git tag ${tag} 不存在，跳过。`)
  else console.error(`删除 tag 失败：HTTP ${delTag.status} ${delTag.text.slice(0, 200)}`)
} else {
  console.log('按 --keep-tag 要求保留 git tag。')
}

console.log('\n完成。')
