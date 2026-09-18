// 实测「地点搜索 / 地理编码」服务可达性。目标：找到国内网络下无需 API Key 就能用的搜索源。
import https from 'node:https'

const qZh = encodeURIComponent('北京大学')
const qEn = encodeURIComponent('Peking University')

const targets = [
  ['Photon (Komoot)', `https://photon.komoot.io/api/?q=${qEn}&limit=3&lang=zh`],
  ['Nominatim (OSM)', `https://nominatim.openstreetmap.org/search?q=${qEn}&format=json&limit=3`],
  ['Nominatim 法国', `https://nominatim.openstreetmap.fr/search?q=${qEn}&format=json&limit=3`],
  ['高德 inputtips', `https://restapi.amap.com/v3/assistant/inputtips?keywords=${qZh}&key=`],
  ['高德 web poiInfo', `https://www.amap.com/service/poiInfo?query_type=TQUERY&keywords=${qZh}`],
  ['腾讯 suggestion', `https://apis.map.qq.com/ws/place/v1/suggestion/?keyword=${qZh}&key=`],
  ['维基百科 geosearch', `https://zh.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=39.99%7C116.30&gsradius=10000&gslimit=3&format=json`],
]

function finish(name, res, body, started) {
  const ms = Date.now() - started
  const type = (res.headers['content-type'] || '?').split(';')[0]
  const trimmed = body.trimStart()
  const looksJson = trimmed.startsWith('{') || trimmed.startsWith('[')
  let verdict = ''
  if (!looksJson) {
    verdict = ' [非 JSON]'
  } else if (/INVALID_USER_KEY|INVALID_KEY|"status"\s*:\s*"0"/.test(body)) {
    verdict = ' [缺 API Key]'
  } else if (/"lat"|"latitude"|"location"|"title"|"name"|"address"|"display_name"/.test(body)) {
    verdict = ' [有结果字段 OK]'
  } else {
    verdict = ' [JSON 但无结果字段]'
  }
  console.log(
    `${name.padEnd(22)} HTTP ${String(res.statusCode).padEnd(3)} ${type.padEnd(16)} ${String(ms).padStart(5)}ms${verdict}`
  )
}

function probe(name, url, redirects = 0) {
  return new Promise((resolve) => {
    const started = Date.now()
    const req = https.get(
      url,
      {
        headers: {
          'User-Agent': 'TrailRun/1.0 (Android; local dev)',
          Accept: 'application/json, text/plain, */*',
          'Accept-Language': 'zh-CN,zh;q=0.9',
        },
        timeout: 9000,
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 4) {
          res.resume()
          resolve(probe(name, new URL(res.headers.location, url).toString(), redirects + 1))
          return
        }
        let body = ''
        let settled = false
        res.setEncoding('utf8')
        res.on('data', (c) => {
          body += c
          if (!settled && body.length > 3000) {
            settled = true
            res.destroy()
            resolve(finish(name, res, body, started))
          }
        })
        res.on('end', () => {
          if (!settled) {
            settled = true
            resolve(finish(name, res, body, started))
          }
        })
        res.on('error', () => {})
      }
    )
    req.on('error', (e) => resolve(`${name.padEnd(22)} 失败 ${e.code || e.message}`))
    req.on('timeout', () => {
      req.destroy()
      resolve(`${name.padEnd(22)} 超时`)
    })
  })
}

;(async () => {
  console.log('地点搜索服务实测（查询「北京大学」）\n')
  const results = []
  for (const [n, u] of targets) {
    results.push(await probe(n, u))
  }
  results.forEach((r) => console.log(r))
})()
