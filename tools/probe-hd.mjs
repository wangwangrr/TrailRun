// 验证 FAU osmhd（512px 高清源）：确认 User-Agent 策略、最大缩放级，并落盘样张供人眼检查。
import https from 'node:https'
import { writeFileSync, mkdirSync } from 'node:fs'

const OUT = 'D:\\dsh\\TrailRun\\.toolchain\\tile-samples'
mkdirSync(OUT, { recursive: true })

function tileX(lon, z) {
  return Math.floor(((lon + 180) / 360) * Math.pow(2, z))
}
function tileY(lat, z) {
  const r = (lat * Math.PI) / 180
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * Math.pow(2, z))
}

function get(url, headers, timeoutMs = 12000) {
  return new Promise((resolve) => {
    const started = Date.now()
    const chunks = []
    let settled = false
    const done = (r) => {
      if (!settled) {
        settled = true
        resolve(Object.assign({ ms: Date.now() - started }, r))
      }
    }
    const req = https.get(url, { headers, timeout: timeoutMs }, (res) => {
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => {
        const buf = Buffer.concat(chunks)
        let px = 'not-png'
        if (buf.length > 24 && buf[0] === 0x89 && buf[1] === 0x50) {
          px = buf.readUInt32BE(16) + 'x' + buf.readUInt32BE(20)
        }
        done({ status: res.statusCode, px, bytes: buf.length, buf })
      })
      res.on('error', () => {})
    })
    req.on('timeout', () => {
      req.destroy()
      done({ status: 'TIMEOUT' })
    })
    req.on('error', (e) => done({ status: 'ERR', note: String(e.code || e.message) }))
  })
}

const UAS = [
  ['osmdroid 实际会发的 UA (包名)', 'com.trailrun.mockgps'],
  ['普通 Android Chrome', 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36'],
  ['osmdroid 默认', 'osmdroid'],
  ['明确标识的应用 UA', 'TrailRun/1.0 (Android; +contact: student project)'],
]

const Z = 16
const X = tileX(116.3975, Z) // 天安门
const Y = tileY(39.9087, Z)
const URL_HD = `https://osm.rrze.fau.de/osmhd/${Z}/${X}/${Y}.png`

console.log('=== User-Agent 策略 (osmhd z16 天安门) ===')
for (const [label, ua] of UAS) {
  const r = await get(URL_HD, { 'User-Agent': ua, Accept: 'image/png,image/*,*/*;q=0.8' })
  console.log(`  ${label.padEnd(34)} ${r.status}  ${r.px || '-'}  ${r.bytes || '-'}B  ${r.ms}ms ${r.note || ''}`)
}

console.log('\n=== 最大缩放级 (osmhd) ===')
for (const z of [17, 18, 19, 20]) {
  const url = `https://osm.rrze.fau.de/osmhd/${z}/${tileX(116.3975, z)}/${tileY(39.9087, z)}.png`
  const r = await get(url, { 'User-Agent': 'com.trailrun.mockgps' })
  console.log(`  z=${z}  ${r.status}  ${r.px || '-'}  ${r.bytes || '-'}B  ${r.ms}ms`)
}

console.log('\n=== 样张落盘 ===')
const shots = [
  ['osmhd-512', `https://osm.rrze.fau.de/osmhd/16/${X}/${Y}.png`],
  ['osmfr-hot-256', `https://a.tile.openstreetmap.fr/hot/16/${X}/${Y}.png`],
]
for (const [name, url] of shots) {
  const r = await get(url, {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36',
  })
  if (r.status === 200 && r.buf) {
    const p = `${OUT}\\${name}.png`
    writeFileSync(p, r.buf)
    console.log(`  ${p}  ${r.px}  ${r.bytes}B`)
  } else {
    console.log(`  ${name} 失败 ${r.status}`)
  }
}
