// 探测 ffmpeg Windows 免安装版的下载源可达性。这台机器 GitHub 不通，所以优先非 GitHub 源。
import https from 'node:https'

const candidates = [
  ['gyan.dev essentials', 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'],
  ['gyan.dev full', 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-full.7z'],
  ['BtbN latest (GitHub)', 'https://github.com/BtbN/FFmpeg-Builds/releases/latest'],
  ['huaweicloud 镜像', 'https://repo.huaweicloud.com/ffmpeg/'],
]

function probe(name, url, redirects = 0) {
  return new Promise((resolve) => {
    const started = Date.now()
    const req = https.get(
      url,
      { method: 'HEAD', headers: { 'User-Agent': 'Mozilla/5.0' }, timeout: 15000 },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 4) {
          res.resume()
          resolve(probe(name, new URL(res.headers.location, url).toString(), redirects + 1))
          return
        }
        const len = res.headers['content-length']
        const mb = len ? (Number(len) / 1048576).toFixed(1) + ' MB' : '?'
        console.log(
          `${name.padEnd(24)} HTTP ${String(res.statusCode).padEnd(4)} ${mb.padEnd(10)} ${Date.now() - started}ms`
        )
        res.resume()
      }
    )
    req.on('error', (e) => console.log(`${name.padEnd(24)} 失败 ${e.code || e.message}`))
    req.on('timeout', () => {
      req.destroy()
      console.log(`${name.padEnd(24)} 超时`)
    })
  })
}

;(async () => {
  for (const [n, u] of candidates) await probe(n, u)
})()
