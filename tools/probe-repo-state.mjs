// 只读地看一眼 GitHub 仓库的当前状态：默认分支、顶层文件、release/ 目录内容、已有的 Release。
//
// 为什么单独写一个：发布前必须先确认「远端到底是什么样」，
// 而不是照着本机目录猜 —— 本机 release/ 里堆着 1.2.1~1.2.4，
// 远端可能还停在 1.2.0。
//
// 用法：
//   set GITHUB_TOKEN=...   (Windows)
//   node tools/probe-repo-state.mjs wangwangrr/TrailRun
//
// token 只从环境变量读，不落盘、不回显。
const [owner, repoName] = (process.argv[2] || '').split('/')
if (!owner || !repoName) {
  console.error('用法: node tools/probe-repo-state.mjs <owner>/<repo>')
  process.exit(2)
}
const token = process.env.GITHUB_TOKEN || ''

async function api(path) {
  const res = await fetch(`https://api.github.com${path}`, {
    headers: {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'trailrun-probe',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  const text = await res.text()
  let body = null
  try { body = JSON.parse(text) } catch { body = text.slice(0, 300) }
  return { status: res.status, body }
}

const repo = await api(`/repos/${owner}/${repoName}`)
if (repo.status !== 200) {
  console.error(`仓库读取失败 ${repo.status}:`, repo.body)
  process.exit(1)
}
const branch = repo.body.default_branch
console.log(`仓库: ${owner}/${repoName}  默认分支: ${branch}  公开: ${!repo.body.private}`)

const head = await api(`/repos/${owner}/${repoName}/commits/${branch}`)
console.log(`HEAD: ${head.body?.sha?.slice(0, 10)}  ${head.body?.commit?.message?.split('\n')[0]}`)
console.log(`      ${head.body?.commit?.committer?.date}`)

const root = await api(`/repos/${owner}/${repoName}/contents/?ref=${branch}`)
if (Array.isArray(root.body)) {
  console.log(`\n顶层 (${root.body.length}):`)
  for (const e of root.body) console.log(`  ${e.type === 'dir' ? 'd' : '-'} ${e.name}`)
}

const rel = await api(`/repos/${owner}/${repoName}/contents/release?ref=${branch}`)
console.log('\nrelease/:')
if (Array.isArray(rel.body)) {
  for (const e of rel.body) console.log(`  ${e.name}  ${e.size ?? ''}`)
} else {
  console.log(`  ${rel.status} ${typeof rel.body === 'string' ? rel.body : ''}`)
}

const releases = await api(`/repos/${owner}/${repoName}/releases?per_page=20`)
console.log('\nReleases:')
if (Array.isArray(releases.body)) {
  for (const r of releases.body) {
    console.log(`  ${r.tag_name}  ${r.draft ? '(draft) ' : ''}${r.published_at}  assets=${r.assets.length}`)
    for (const a of r.assets) console.log(`      - ${a.name}  ${a.size}`)
  }
} else {
  console.log(`  ${releases.status}`, releases.body)
}
