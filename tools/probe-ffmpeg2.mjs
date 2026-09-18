// 并行探测国内可用的 ffmpeg 二进制来源。gyan.dev 太慢（0.06MB/s），找更快的。
import https from 'node:https'

const candidates = [
  // npmmirror 镜像的 npm 包：ffmpeg-static 内含预编译二进制
  ['npmmirror ffmpeg-static', 'https://registry.npmmirror.com/ffmpeg-static'],
  // 华为云 / 腾讯云镜像上的常见 ffmpeg 分发
  ['huaweicloud ffmpeg', 'https://repo.huaweicloud.com/ffmpeg/'],
  ['aliyun ffmpeg', 'https://mirrors.aliyun.com/ffmpeg/'],
  // gyan.dev 的另一个域名（有时更快）
  ['gyan.dev github mirror', 'https://github.com/GyanD/codexffmpeg/releases/latest'],
  // 直接试探 ffmpeg-static 的二进制资产
  ['npmmirror binary', 'https://registry.npmmirror.com/-/binary/ffmpeg-static/'],
]

function probe(name, url, redirects = 0) {
  return new Promise((resolve) => {
    const started = Date.now()
    const req = https.get(
      url,
      { method: 'GET', headers: { 'User-Agent': 'Mozilla/5.0', Accept: '*/*' }, timeout: 12000 },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 4) {
          res.resume()
          resolve(probe(name, new URL(res.headers.location, url).toString(), redirects + 1))
          return
        }
        let bytes = 0
        let first = null
        res.on('data', (c) => {
          bytes += c.length
          if (first === null) first = c
          if (bytes > 4000) {
            res.destroy()
            const len = res.headers['content-length']
            const mb = len ? (Number(len) / 1048576).toFixed(1) + 'MB' : '?'
            const speed = (bytes / 1024 / ((Date.now() - started) / 1000)).toFixed(0)
            console.log(
              `${name.padEnd(26)} HTTP ${res.statusCode}  ${mb.padEnd(9)} 首包 ${Date.now() - started}ms  ~${speed} KB/s`
            )
          }
        })
        res.on('end', () => {
          console.log(`${name.padEnd(26)} HTTP ${res.statusCode}  响应 ${bytes} 字节`)
        })
        res.on('error', () => {})
      }
    )
    req.on('error', (e) => console.log(`${name.padEnd(26)} 失败 ${e.code || e.message}`))
    req.on('timeout', () => {
      req.destroy()
      console.log(`${name.padEnd(26)} 超时`)
    })
  })
}

;(async () => {
  await Promise.all(candidates.map(([n, u]) => probe(n, u)))
})()
