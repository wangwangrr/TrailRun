// 从 npmmirror 下载 ffmpeg/ffprobe 的 Windows 二进制（gzip 压缩版，各约 28MB），
// 并用 Node 原生 zlib 就地解压。比 gyan.dev 的 106MB zip 快得多。
import { createWriteStream, existsSync, mkdirSync, rmSync, statSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'
import https from 'node:https'
import { createGunzip } from 'node:zlib'
import { pipeline } from 'node:stream/promises'

const BASE = 'https://registry.npmmirror.com/-/binary/ffmpeg-static/b6.1.1/'
const OUT_DIR = resolve('.toolchain/ffmpeg')
const MIN_BYTES = 20 * 1024 * 1024

mkdirSync(OUT_DIR, { recursive: true })

function fetchTo(url, dest, redirects = 0) {
  return new Promise((res, rej) => {
    https
      .get(url, { headers: { 'User-Agent': 'Mozilla/5.0', Accept: '*/*' }, timeout: 60000 }, (r) => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location && redirects < 5) {
          r.resume()
          res(fetchTo(new URL(r.headers.location, url).toString(), dest, redirects + 1))
          return
        }
        if (r.statusCode !== 200) {
          r.resume()
          rej(new Error('HTTP ' + r.statusCode))
          return
        }
        const total = Number(r.headers['content-length'] || 0)
        let got = 0
        let last = 0
        r.on('data', (c) => {
          got += c.length
          const now = Date.now()
          if (now - last > 4000) {
            last = now
            const pct = total ? ((got / total) * 100).toFixed(0) + '%' : ''
            console.log(`    ${(got / 1048576).toFixed(1)} MB ${pct}`)
          }
        })
        pipeline(r, createGunzip(), createWriteStream(dest)).then(res).catch(rej)
      })
      .on('error', rej)
      .on('timeout', function () {
        this.destroy()
        rej(new Error('超时'))
      })
  })
}

const targets = [
  ['ffmpeg-win32-x64.gz', 'ffmpeg.exe'],
  ['ffprobe-win32-x64.gz', 'ffprobe.exe'],
]

for (const [remote, local] of targets) {
  const dest = join(OUT_DIR, local)
  if (existsSync(dest) && statSync(dest).size > MIN_BYTES) {
    console.log(`已存在，跳过: ${local} (${(statSync(dest).size / 1048576).toFixed(1)} MB)`)
    continue
  }
  console.log(`下载并解压 ${remote} -> ${local}`)
  try {
    await fetchTo(BASE + remote, dest)
    const mb = statSync(dest).size / 1048576
    if (mb * 1048576 < MIN_BYTES) throw new Error('文件过小: ' + mb.toFixed(1) + ' MB')
    console.log(`  完成: ${local}  ${mb.toFixed(1)} MB`)
  } catch (e) {
    console.error(`  失败: ${e.message}`)
    rmSync(dest, { force: true })
    process.exitCode = 1
  }
}
