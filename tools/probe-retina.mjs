// 探针：哪些底图端点真的**返回 512px（@2x）瓦片**。
//
// 为什么必须实测像素尺寸而不是只测「通不通」：
// osmdroid 的 MapView.updateTileSizeForDensity() 在 setTilesScaledToDpi(true) 时，
// 绘制尺寸 = 256 * 屏幕密度，**与瓦片源声明的尺寸无关**：
//     float density = displayDensity * 256 / tile_size;
//     int size = (int)(tile_size * (scaledToDpi ? density : 1f));   // == 256 * displayDensity
// 所以 256px 的瓦片在 density=2.75 的手机上被放大 2.75 倍绘制 —— 这就是「糊」的根源。
// 换成 512px 的瓦片源，同一块屏幕区域仍按 256*density 绘制，但源分辨率翻倍，
// 放大倍率从 2.75x 降到 1.37x，清晰度直接翻倍，且视野范围、字号完全不变。
//
// 用法: node tools/probe-retina.mjs
import https from 'node:https'
import http from 'node:http'

const Z = 13
const LAT = 39.9042
const LON = 116.4074

const X = Math.floor(((LON + 180) / 360) * Math.pow(2, Z))
const rad = (LAT * Math.PI) / 180
const Y = Math.floor(
  ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * Math.pow(2, Z)
)

const CANDIDATES = [
  ['osm-fr hot', `https://a.tile.openstreetmap.fr/hot/${Z}/${X}/${Y}.png`],
  ['osm-fr hot @2x', `https://a.tile.openstreetmap.fr/hot/${Z}/${X}/${Y}@2x.png`],
  ['osm-fr osmfr', `https://a.tile.openstreetmap.fr/osmfr/${Z}/${X}/${Y}.png`],
  ['osm-fr osmfr @2x', `https://a.tile.openstreetmap.fr/osmfr/${Z}/${X}/${Y}@2x.png`],
  ['osm-de', `https://tile.openstreetmap.de/${Z}/${X}/${Y}.png`],
  ['carto voyager', `https://a.basemaps.cartocdn.com/rastertiles/voyager/${Z}/${X}/${Y}.png`],
  ['carto voyager @2x', `https://a.basemaps.cartocdn.com/rastertiles/voyager/${Z}/${X}/${Y}@2x.png`],
  ['carto positron @2x', `https://a.basemaps.cartocdn.com/light_all/${Z}/${X}/${Y}@2x.png`],
  ['carto positron', `https://a.basemaps.cartocdn.com/light_all/${Z}/${X}/${Y}.png`],
  ['carto dark @2x', `https://a.basemaps.cartocdn.com/dark_all/${Z}/${X}/${Y}@2x.png`],
  ['carto voyager-nolabel @2x', `https://a.basemaps.cartocdn.com/rastertiles/voyager_nolabels/${Z}/${X}/${Y}@2x.png`],
  ['osm official', `https://tile.openstreetmap.org/${Z}/${X}/${Y}.png`],
]

function pngSize(buf) {
  if (buf.length < 24) return null
  if (buf[0] !== 0x89 || buf[1] !== 0x50 || buf[2] !== 0x4e || buf[3] !== 0x47) return null
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }
}

function probe(url, timeoutMs = 12000) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https:') ? https : http
    const started = Date.now()
    const chunks = []
    let settled = false
    const done = (r) => {
      if (settled) return
      settled = true
      resolve(Object.assign({ ms: Date.now() - started }, r))
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
          const size = pngSize(buf)
          done({
            status: res.statusCode,
            bytes: Number(res.headers['content-length'] || buf.length),
            px: size ? size.w + 'x' + size.h : 'not-png',
          })
        })
        res.on('error', (e) => done({ status: 'ERR', note: e.message }))
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

console.log(`probe tile z=${Z} x=${X} y=${Y} (Beijing)\n`)
console.log(pad('endpoint', 28) + pad('status', 9) + pad('pixels', 11) + pad('bytes', 9) + pad('ms', 8) + 'note')
const rows = []
for (const [label, url] of CANDIDATES) {
  const r = await probe(url)
  rows.push({ label, url, r })
  console.log(
    pad(label, 28) + pad(r.status, 9) + pad(r.px || '-', 11) + pad(r.bytes || '-', 9) + pad(r.ms + 'ms', 8) + (r.note || '')
  )
}

console.log('\n=== usable retina (HTTP 200 and >= 512px) ===')
const good = rows.filter((x) => x.r.status === 200 && /^(\d+)x/.test(x.r.px) && Number(x.r.px.split('x')[0]) >= 512)
if (good.length === 0) console.log('NONE')
for (const g of good) console.log(pad(g.label, 28) + pad(g.r.px, 11) + pad(g.r.ms + 'ms', 9) + g.url)
