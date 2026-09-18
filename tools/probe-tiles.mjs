// 实测各瓦片服务器可达性：请求北京天安门附近一个真实瓦片，看能否拿到图片。
// 用 Node 的 OpenSSL 栈（本机 schannel 不可用）。
import https from 'node:https'
import http from 'node:http'

// z=16 时北京天安门附近的瓦片坐标（Web Mercator）
const Z = 16
const X = 53979
const Y = 24800

const candidates = [
  ['OpenStreetMap 官方', `https://a.tile.openstreetmap.org/${Z}/${X}/${Y}.png`],
  ['OSM 法国镜像', `https://a.tile.openstreetmap.fr/osmfr/${Z}/${X}/${Y}.png`],
  ['CartoDB Positron (浅色)', `https://a.basemaps.cartocdn.com/light_all/${Z}/${X}/${Y}.png`],
  ['CartoDB Voyager', `https://a.basemaps.cartocdn.com/rastertiles/voyager/${Z}/${X}/${Y}.png`],
  ['Esri 街道图', `https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/${Z}/${Y}/${X}`],
  ['Esri 卫星影像', `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${Z}/${Y}/${X}`],
  ['OpenTopoMap', `https://a.tile.opentopomap.org/${Z}/${X}/${Y}.png`],
  ['OSM 德国镜像', `https://tile.openstreetmap.de/${Z}/${X}/${Y}.png`],
  ['Wikimedia', `https://maps.wikimedia.org/osm-intl/${Z}/${X}/${Y}.png`],
  ['高德 卫星(无key直连)', `https://webst01.is.autonavi.com/appmaptile?style=6&x=${X}&y=${Y}&z=${Z}`],
  ['高德 路网(无key直连)', `https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=${X}&y=${Y}&z=${Z}`],
  ['腾讯 路网(无key直连)', `https://rt1.map.gtimg.com/tile?z=${Z}&x=${X}&y=${Y}&type=vector&styleid=3`],
]

function probe(name, url, redirects = 0) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https') ? https : http
    const req = mod.get(
      url,
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
          Referer: 'https://www.openstreetmap.org/',
          Accept: 'image/avif,image/webp,image/png,*/*',
        },
        timeout: 12000,
      },
      (res) => {
        const type = res.headers['content-type'] || '?'
        const len = res.headers['content-length'] || '?'
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 4) {
          res.resume()
          resolve(probe(name, new URL(res.headers.location, url).toString(), redirects + 1))
          return
        }
        // 读一点数据确认真的是图片而不是错误页
        let got = 0
        res.on('data', (c) => {
          got += c.length
          if (got > 512) {
            res.destroy()
            resolve(`${name.padEnd(26)} OK   ${res.statusCode}  ${type}  ${len}b  (收到${got}+字节)`)
          }
        })
        res.on('end', () => {
          if (got === 0) resolve(`${name.padEnd(26)} 空响应 ${res.statusCode}`)
        })
        res.on('error', () => {})
      }
    )
    req.on('error', (e) => resolve(`${name.padEnd(26)} 失败 ${e.code || e.message}`))
    req.on('timeout', () => {
      req.destroy()
      resolve(`${name.padEnd(26)} 超时`)
    })
  })
}

;(async () => {
  console.log(`测试瓦片 z=${Z} x=${X} y=${Y}（北京天安门附近）\n`)
  for (const [name, url] of candidates) {
    console.log(await probe(name, url))
  }
})()
