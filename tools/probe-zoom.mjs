// 各镜像的最大缩放级：osmdroid 必须知道每个源的 maxZoom，否则 z19 会整片空白。
import https from 'node:https'

const ZS = [17, 18, 19]
const BASES = [
  ['fau osmhd', 'https://osm.rrze.fau.de/osmhd/'],
  ['fr hot', 'https://a.tile.openstreetmap.fr/hot/'],
  ['osm-de', 'https://tile.openstreetmap.de/'],
  ['osm-jp', 'https://tile.openstreetmap.jp/'],
]

function tx(lon, z) {
  return Math.floor(((lon + 180) / 360) * Math.pow(2, z))
}
function ty(lat, z) {
  const r = (lat * Math.PI) / 180
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * Math.pow(2, z))
}

function get(url, timeoutMs = 9000) {
  return new Promise((resolve) => {
    const chunks = []
    let settled = false
    const done = (r) => {
      if (!settled) {
        settled = true
        resolve(r)
      }
    }
    const req = https.get(
      url,
      { headers: { 'User-Agent': 'com.trailrun.mockgps' }, timeout: timeoutMs },
      (res) => {
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          const b = Buffer.concat(chunks)
          let px = '-'
          if (b.length > 24 && b[0] === 0x89 && b[1] === 0x50) {
            px = b.readUInt32BE(16) + 'x' + b.readUInt32BE(20)
          }
          done({ status: res.statusCode, px, bytes: b.length })
        })
        res.on('error', () => {})
      }
    )
    req.on('timeout', () => {
      req.destroy()
      done({ status: 'TIMEOUT' })
    })
    req.on('error', (e) => done({ status: 'ERR', note: e.code }))
  })
}

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - String(s).length))
for (const [name, base] of BASES) {
  const cells = []
  for (const z of ZS) {
    const r = await get(`${base}${z}/${tx(116.3975, z)}/${ty(39.9087, z)}.png`)
    cells.push(`z${z}:${r.status}${r.px && r.px !== '-' ? '/' + r.px : ''}`)
  }
  console.log(pad(name, 14) + cells.join('   '))
}
