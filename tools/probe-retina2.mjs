// 第二轮探针：还有没有「真的 512px」的底图源，以及备用 OSM 镜像的质量。
// 第一轮结论：法国/德国镜像都是 256px 且不支持 @2x，Carto 与官方站全部超时。
import https from 'node:https'
import http from 'node:http'

const Z = 13
const X = 6744
const Y = 3104

const CANDIDATES = [
  ['fau osmhd       512?', `https://osm.rrze.fau.de/osmhd/${Z}/${X}/${Y}.png`],
  ['fau osm-carto', `https://osm.rrze.fau.de/osm-carto/${Z}/${X}/${Y}.png`],
  ['osm.jp', `https://tile.openstreetmap.jp/${Z}/${X}/${Y}.png`],
  ['osm.jp osm', `https://tile.openstreetmap.jp/osm/${Z}/${X}/${Y}.png`],
  ['osm.bzh', `https://tile.openstreetmap.bzh/br/${Z}/${X}/${Y}.png`],
  ['opentopomap', `https://a.tile.opentopomap.org/${Z}/${X}/${Y}.png`],
  ['fr hot  b', `https://b.tile.openstreetmap.fr/hot/${Z}/${X}/${Y}.png`],
  ['fr hot  c', `https://c.tile.openstreetmap.fr/hot/${Z}/${X}/${Y}.png`],
  ['de  a', `https://a.tile.openstreetmap.de/${Z}/${X}/${Y}.png`],
  ['de  b', `https://b.tile.openstreetmap.de/${Z}/${X}/${Y}.png`],
  ['openfreemap vector', `https://tiles.openfreemap.org/planet/${Z}/${X}/${Y}.pbf`],
  ['maplibre demotiles', `https://demotiles.maplibre.org/tiles/0/0/0.pbf`],
  ['fr hot z19', `https://a.tile.openstreetmap.fr/hot/19/${X * 64}/${Y * 64}.png`],
]

function pngSize(buf) {
  if (buf.length < 24) return null
  if (buf[0] !== 0x89 || buf[1] !== 0x50 || buf[2] !== 0x4e || buf[3] !== 0x47) return null
  return buf.readUInt32BE(16) + 'x' + buf.readUInt32BE(20)
}

function probe(url, timeoutMs = 10000) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https:') ? https : http
    const started = Date.now()
    const chunks = []
    let settled = false
    const done = (r) => {
      if (!settled) {
        settled = true
        resolve(Object.assign({ ms: Date.now() - started }, r))
      }
    }
    const req = mod.get(
      url,
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36',
          Accept: 'image/png,image/*,*/*;q=0.8',
        },
        timeout: timeoutMs,
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400) {
          res.resume()
          done({ status: res.statusCode, note: 'redirect' })
          return
        }
        res.on('data', (c) => {
          if (chunks.length < 4) chunks.push(c)
        })
        res.on('end', () => {
          const buf = Buffer.concat(chunks)
          const px = pngSize(buf)
          done({
            status: res.statusCode,
            bytes: Number(res.headers['content-length'] || buf.length),
            px: px || (buf.length >= 2 && buf[0] === 0x1f && buf[1] === 0x8b ? 'gzip(pbf)' : 'not-png'),
          })
        })
        res.on('error', () => {})
      }
    )
    req.on('timeout', () => {
      req.destroy()
      done({ status: 'TIMEOUT' })
    })
    req.on('error', (e) => done({ status: 'ERR', note: String(e.code || e.message) }))
  })
}

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - String(s).length))
console.log(pad('endpoint', 24) + pad('status', 9) + pad('pixels', 12) + pad('bytes', 9) + 'ms')
for (const [label, url] of CANDIDATES) {
  const r = await probe(url)
  console.log(pad(label, 24) + pad(r.status, 9) + pad(r.px || '-', 12) + pad(r.bytes || '-', 9) + r.ms + 'ms ' + (r.note || ''))
}
