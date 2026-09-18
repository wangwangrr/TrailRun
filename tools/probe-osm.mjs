// 只保留 OSM 的前提下，确定该用哪个 OSM 端点。
// 多个瓦片坐标取样，避免单个瓦片被 CDN 缓存误导。
import https from 'node:https'

const ends = [
  ['OSM 官方 a', 'https://a.tile.openstreetmap.org'],
  ['OSM 官方 tile', 'https://tile.openstreetmap.org'],
  ['OSM 德国', 'https://tile.openstreetmap.de'],
  ['OSM 法国 osmfr', 'https://a.tile.openstreetmap.fr/osmfr'],
  ['OSM 法国(标准)', 'https://a.tile.openstreetmap.fr/hot'],
]

// 北京/上海/广州/成都 附近的瓦片，z=16
const tiles = [
  [16, 53979, 24800],
  [16, 54641, 26782],
  [16, 53544, 28002],
  [15, 26801, 13436],
]

function fetchTile(url) {
  return new Promise((resolve) => {
    const t = Date.now()
    const req = https.get(
      url,
      {
        headers: {
          // 贴近 App 真实请求：不伪造 Referer，只带可识别的 UA
          'User-Agent': 'TrailRun/1.0 (Android; +https://example.invalid)',
          Accept: 'image/*,*/*;q=0.8',
        },
        timeout: 9000,
      },
      (res) => {
        let got = 0
        res.on('data', (c) => {
          got += c.length
          if (got > 512) {
            res.destroy()
            resolve({ ok: res.statusCode === 200, code: res.statusCode, ms: Date.now() - t })
          }
        })
        res.on('end', () => resolve({ ok: false, code: res.statusCode, ms: Date.now() - t }))
        res.on('error', () => {})
      }
    )
    req.on('error', (e) => resolve({ ok: false, code: e.code || e.message, ms: Date.now() - t }))
    req.on('timeout', () => {
      req.destroy()
      resolve({ ok: false, code: 'TIMEOUT', ms: Date.now() - t })
    })
  })
}

;(async () => {
  for (const [name, base] of ends) {
    let ok = 0
    const notes = []
    for (const [z, x, y] of tiles) {
      const r = await fetchTile(`${base}/${z}/${x}/${y}.png`)
      if (r.ok) ok++
      else notes.push(`${r.code}@${r.ms}ms`)
    }
    const verdict = ok === tiles.length ? '全部可用' : ok === 0 ? '全部失败' : `部分可用 ${ok}/${tiles.length}`
    console.log(
      `${name.padEnd(16)} ${String(ok).padStart(2)}/${tiles.length}  ${verdict.padEnd(14)} ${notes.slice(0, 2).join(' ')}`
    )
  }
})()
