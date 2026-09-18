import https from 'node:https'

const cases = [
  ['Photon 无 lang', 'https://photon.komoot.io/api/?q=Peking%20University&limit=3'],
  ['Photon 中文 q', `https://photon.komoot.io/api/?q=${encodeURIComponent('北京大学')}&limit=3`],
  ['Photon 拼音', 'https://photon.komoot.io/api/?q=beijing%20daxue&limit=3'],
  ['Photon 简单词', 'https://photon.komoot.io/api/?q=beijing&limit=2'],
]

function probe(name, url) {
  return new Promise((resolve) => {
    const t = Date.now()
    const req = https.get(
      url,
      { headers: { 'User-Agent': 'TrailRun/1.0 (Android; local dev)' }, timeout: 9000 },
      (res) => {
        let b = ''
        res.setEncoding('utf8')
        res.on('data', (c) => {
          b += c
          if (b.length > 2000) res.destroy()
        })
        res.on('end', () => {
          const feats = (b.match(/"type"\s*:/g) || []).length
          const hasName = /"name"\s*:/.test(b)
          console.log(
            `${name.padEnd(18)} HTTP ${res.statusCode}  ${Date.now() - t}ms  features=${feats} hasName=${hasName}`
          )
          if (res.statusCode !== 200) console.log('     body: ' + b.slice(0, 180).replace(/\s+/g, ' '))
          else if (feats > 0) {
            const m = b.match(/"name"\s*:\s*"([^"]+)"/)
            const c = b.match(/"country"\s*:\s*"([^"]+)"/)
            console.log(`     首个结果: ${m ? m[1] : '?'} / ${c ? c[1] : '?'}`)
          }
          resolve()
        })
      }
    )
    req.on('error', (e) => {
      console.log(`${name.padEnd(18)} 失败 ${e.code || e.message}`)
      resolve()
    })
    req.on('timeout', () => {
      req.destroy()
      console.log(`${name.padEnd(18)} 超时`)
      resolve()
    })
  })
}

;(async () => {
  for (const [n, u] of cases) await probe(n, u)
})()
