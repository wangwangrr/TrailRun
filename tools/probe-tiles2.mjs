// 瓦片可达性实测：区分「浏览器式请求头」与「App/osmdroid 式请求头」。
// 上一轮探测加了 Referer 才成功，可能是防盗链导致的假阳性，这里做对照实验。
import https from 'node:https'
import http from 'node:http'

const Z = 16
const X = 53979
const Y = 24800

// 高德：osmdroid 实际拼出的 URL
const gaodeRoad = `https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=${X}&y=${Y}&z=${Z}`
const gaodeSat = `https://webst01.is.autonavi.com/appmaptile?style=6&x=${X}&y=${Y}&z=${Z}`
const osmDe = `https://tile.openstreetmap.de/${Z}/${X}/${Y}.png`
const osmFr = `https://a.tile.openstreetmap.fr/osmfr/${Z}/${X}/${Y}.png`

const headerSets = {
  '浏览器式(带Referer)': {
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
    Referer: 'https://www.openstreetmap.org/',
  },
  'App式(无Referer)': {
    'User-Agent':
      'Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36',
  },
  'osmdroid默认': {
    'User-Agent': 'osmdroid',
  },
}

function probe(name, url, headers) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https') ? https : http
    const started = Date.now()
    const req = mod.get(url, { headers, timeout: 12000 }, (res) => {
      let got = 0
      let first = null
      res.on('data', (c) => {
        if (first === null) first = c
        got += c.length
        if (got > 256) {
          res.destroy()
          const type = res.headers['content-type'] || '?'
          const isImage = type.startsWith('image/')
          const ms = Date.now() - started
          resolve(
            `${isImage ? '图片 OK ' : '非图片!! '} ${String(res.statusCode).padEnd(3)} ${type.padEnd(12)} ${String(got).padStart(5)}B ${String(ms).padStart(5)}ms`
          )
        }
      })
      res.on('end', () => {
        if (got === 0) resolve(`空响应 ${res.statusCode}`)
      })
      res.on('error', () => {})
    })
    req.on('error', (e) => resolve(`失败 ${e.code || e.message}`))
    req.on('timeout', () => {
      req.destroy()
      resolve('超时')
    })
  })
}

const targets = [
  ['高德 路网', gaodeRoad],
  ['高德 卫星', gaodeSat],
  ['OSM 德国', osmDe],
  ['OSM 法国', osmFr],
]

;(async () => {
  for (const [hName, headers] of Object.entries(headerSets)) {
    console.log(`\n########## ${hName} ##########`)
    for (const [tName, url] of targets) {
      const r = await probe(tName, url, headers)
      console.log(`  ${tName.padEnd(12)} ${r}`)
    }
  }
})()
