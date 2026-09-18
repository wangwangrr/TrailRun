// 逐个端点、逐个瓦片坐标验证「osmdroid 实际会请求的 URL」是否可用。
// 目的：区分「端点整体不可达」与「个别瓦片不存在」。
import https from 'node:https'

// 与 OsmEndpoints.kt 保持一致
const endpoints = [
  ['法国 hot', 'https://a.tile.openstreetmap.fr/hot/'],
  ['法国 osmfr', 'https://a.tile.openstreetmap.fr/osmfr/'],
  ['德国镜像', 'https://tile.openstreetmap.de/'],
  ['官方站', 'https://tile.openstreetmap.org/'],
]

// 多个真实坐标（北京/上海/广州/成都附近 + 一个低缩放级）
const tiles = [
  { z: 16, x: 53979, y: 24800, name: '北京 z16' },
  { z: 16, x: 54641, y: 26782, name: '上海 z16' },
  { z: 16, x: 53544, y: 28002, name: '广州 z16' },
  { z: 12, x: 3373, y: 1550, name: '北京 z12' },
  { z: 5, x: 26, y: 12, name: '中国 z5' },
]

function head(url, timeout = 9000) {
  return new Promise((resolve) => {
    const t = Date.now()
    const req = https.get(
      url,
      { headers: { 'User-Agent': 'TrailRun/1.0 (Android)' }, timeout },
      (res) => {
        let bytes = 0
        res.on('data', (c) => {
          bytes += c.length
          if (bytes > 256) {
            res.destroy()
            resolve({ code: res.statusCode, ms: Date.now() - t, type: res.headers['content-type'] })
          }
        })
        res.on('end', () =>
          resolve({ code: res.statusCode, ms: Date.now() - t, type: res.headers['content-type'], bytes })
        )
        res.on('error', () => {})
      }
    )
    req.on('error', (e) => resolve({ code: e.code || 'ERR', ms: Date.now() - t }))
    req.on('timeout', () => {
      req.destroy()
      resolve({ code: 'TIMEOUT', ms: Date.now() - t })
    })
  })
}

;(async () => {
  for (const [name, base] of endpoints) {
    const marks = []
    for (const t of tiles) {
      const url = `${base}${t.z}/${t.x}/${t.y}.png`
      const r = await head(url)
      const ok = r.code === 200
      marks.push(`${t.name}:${ok ? 'OK' : r.code}`)
    }
    const okCount = marks.filter((m) => m.endsWith('OK')).length
    console.log(`${name.padEnd(12)} ${okCount}/${tiles.length}  ${marks.join('  ')}`)
  }

  console.log('\n=== 对照：官方站换用 https 直连的备用域名 ===')
  for (const u of [
    'https://tile.openstreetmap.org/5/26/12.png',
    'https://a.tile.openstreetmap.org/5/26/12.png',
  ]) {
    const r = await head(u)
    console.log(`  ${u}  ->  ${r.code}  ${r.ms}ms`)
  }
})()
