// 用 Node 的 OpenSSL 栈做下载（沙箱里 schannel 不可用，但 node 可以）。
// 用法: node dl.mjs <url> <输出文件> [预期最小字节数]
import { createWriteStream, existsSync, mkdirSync, statSync, unlinkSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import https from 'node:https'
import http from 'node:http'

const [, , url, outArg, minBytesArg] = process.argv
if (!url || !outArg) {
  console.error('用法: node dl.mjs <url> <输出文件> [最小字节数]')
  process.exit(2)
}

const out = resolve(outArg)
const minBytes = minBytesArg ? Number(minBytesArg) : 0

mkdirSync(dirname(out), { recursive: true })

if (existsSync(out)) {
  const size = statSync(out).size
  if (size >= minBytes && size > 0) {
    console.log(`已存在且大小合格，跳过: ${out} (${(size / 1048576).toFixed(1)} MB)`)
    process.exit(0)
  }
  unlinkSync(out)
}

const MAX_REDIRECT = 10

function fetchTo(url, redirects = 0) {
  return new Promise((resolvePromise, rejectPromise) => {
    const mod = url.startsWith('https:') ? https : http
    const req = mod.get(
      url,
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
          Accept: '*/*',
        },
        timeout: 60000,
      },
      (res) => {
        const { statusCode, headers } = res
        if (statusCode >= 300 && statusCode < 400 && headers.location) {
          res.resume()
          if (redirects >= MAX_REDIRECT) {
            rejectPromise(new Error('重定向次数过多'))
            return
          }
          const next = new URL(headers.location, url).toString()
          console.log(`  ${statusCode} -> ${next}`)
          resolvePromise(fetchTo(next, redirects + 1))
          return
        }
        if (statusCode !== 200) {
          res.resume()
          rejectPromise(new Error(`HTTP ${statusCode}`))
          return
        }

        const total = Number(headers['content-length'] || 0)
        let received = 0
        let lastPrint = 0
        const started = Date.now()
        const ws = createWriteStream(out)

        res.on('data', (chunk) => {
          received += chunk.length
          const now = Date.now()
          if (now - lastPrint > 5000) {
            lastPrint = now
            const mb = received / 1048576
            const secs = (now - started) / 1000
            const speed = mb / secs
            const pct = total ? ` (${((received / total) * 100).toFixed(1)}%)` : ''
            console.log(`  ${mb.toFixed(1)} MB${pct} @ ${speed.toFixed(2)} MB/s`)
          }
        })

        res.pipe(ws)
        ws.on('finish', () => {
          ws.close(() => {
            const size = statSync(out).size
            const secs = (Date.now() - started) / 1000
            if (total && size !== total) {
              rejectPromise(new Error(`大小不符: 期望 ${total}, 实际 ${size}`))
              return
            }
            console.log(
              `完成: ${out}  ${(size / 1048576).toFixed(1)} MB  用时 ${secs.toFixed(0)}s`
            )
            resolvePromise(size)
          })
        })
        ws.on('error', rejectPromise)
        res.on('error', rejectPromise)
      }
    )
    req.on('timeout', () => {
      req.destroy(new Error('连接超时'))
    })
    req.on('error', rejectPromise)
  })
}

try {
  const size = await fetchTo(url)
  if (minBytes && size < minBytes) {
    console.error(`文件过小，可能不是有效安装包: ${size} < ${minBytes}`)
    process.exit(1)
  }
  process.exit(0)
} catch (e) {
  console.error('下载失败:', e.message)
  process.exit(1)
}
