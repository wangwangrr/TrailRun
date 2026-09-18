// 探测合规底图源的连通性 —— 为「OSM 版图不合规」这个问题找可行方案。
// 只做只读探测，不改动应用代码。
import https from 'node:https'
import http from 'node:http'

const Z = 13
const X = 6744
const Y = 3104

const TARGETS = [
  // 天地图（国家地理信息公共服务平台）—— 官方、版图合规、CGCS2000
  ['天地图 主域名', 'https://www.tianditu.gov.cn/'],
  ['天地图 t0', 'https://t0.tianditu.gov.cn/'],
  ['天地图 矢量瓦片(无key)', `https://t0.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=${Z}&TILEROW=${Y}&TILECOL=${X}`],
  ['天地图 矢量瓦片(假key)', `https://t0.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=${Z}&TILEROW=${Y}&TILECOL=${X}&tk=00000000000000000000000000000000`],
  ['天地图 影像瓦片(假key)', `https://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=${Z}&TILEROW=${Y}&TILECOL=${X}&tk=00000000000000000000000000000000`],
  ['天地图 注记瓦片(假key)', `https://t0.tianditu.gov.cn/cva_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cva&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=${Z}&TILEROW=${Y}&TILECOL=${X}&tk=00000000000000000000000000000000`],
  // 备用官方源
  ['自然资源部标准地图服务', 'http://bzdt.ch.mnr.gov.cn/'],
  // 对照：当前用的 OSM 源
  ['（对照）当前 OSM 高清源', `https://osm.rrze.fau.de/osmhd/${Z}/${X}/${Y}.png`],
]

function probe(url, timeoutMs = 15000) {
  return new Promise((resolve) => {
    const started = Date.now()
    const mod = url.startsWith('https:') ? https : http
    let settled = false
    const chunks = []
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
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36',
          Accept: '*/*',
        },
        timeout: timeoutMs,
      },
      (res) => {
        res.on('data', (c) => {
          if (chunks.length < 3) chunks.push(c)
        })
        res.on('end', () => {
          const buf = Buffer.concat(chunks)
          let kind = ''
          if (buf.length > 24 && buf[0] === 0x89 && buf[1] === 0x50) {
            kind = `PNG ${buf.readUInt32BE(16)}x${buf.readUInt32BE(20)}`
          } else if (buf.length > 2 && buf[0] === 0xff && buf[1] === 0xd8) {
            kind = 'JPEG'
          } else {
            kind = buf.toString('utf8').replace(/\s+/g, ' ').slice(0, 70)
          }
          done({
            status: res.statusCode,
            type: (res.headers['content-type'] || '').split(';')[0],
            kind,
          })
        })
        res.on('error', (e) => done({ status: 'ERR', kind: e.message }))
      }
    )
    req.on('timeout', () => {
      req.destroy()
      done({ status: 'TIMEOUT' })
    })
    req.on('error', (e) => done({ status: 'ERR', kind: String(e.code || e.message) }))
  })
}

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - String(s).length))
for (const [name, url] of TARGETS) {
  const r = await probe(url)
  console.log(pad(name, 26) + pad(r.status, 10) + pad(r.ms + 'ms', 9) + (r.kind || ''))
}
