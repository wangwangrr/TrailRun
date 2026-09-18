// 把本工程的 vector drawable 渲染成 PNG，用于在电脑上预览效果。
// 只用 Node 标准库：自己解析 path、扁平化贝塞尔、扫描线填充、手写 PNG 编码。
// 支持：M/L/H/V/C/Q/Z 命令、纯色填充、linearGradient 填充、alpha 混合。
import { readFileSync, writeFileSync } from 'node:fs'
import zlib from 'node:zlib'

// ---------------- path 解析 ----------------
function parsePath(d) {
  const tokens = d.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e[-+]?\d+)?/gi) || []
  const subpaths = []
  let cur = null
  let x = 0, y = 0, sx = 0, sy = 0
  let i = 0
  const num = () => parseFloat(tokens[i++])
  let cmd = null

  const push = (px, py) => { if (cur) cur.pts.push([px, py]) }

  while (i < tokens.length) {
    const t = tokens[i]
    if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(t)) { cmd = t; i++ } else if (cmd === null) { i++; continue }

    switch (cmd) {
      case 'M': case 'm': {
        let nx = num(), ny = num()
        if (cmd === 'm') { nx += x; ny += y }
        cur = { pts: [[nx, ny]] }
        subpaths.push(cur)
        x = sx = nx; y = sy = ny
        cmd = cmd === 'M' ? 'L' : 'l'
        break
      }
      case 'L': case 'l': {
        let nx = num(), ny = num()
        if (cmd === 'l') { nx += x; ny += y }
        push(nx, ny); x = nx; y = ny
        break
      }
      case 'H': case 'h': {
        let nx = num()
        if (cmd === 'h') nx += x
        push(nx, y); x = nx
        break
      }
      case 'V': case 'v': {
        let ny = num()
        if (cmd === 'v') ny += y
        push(x, ny); y = ny
        break
      }
      case 'C': case 'c': {
        let x1 = num(), y1 = num(), x2 = num(), y2 = num(), nx = num(), ny = num()
        if (cmd === 'c') { x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y }
        flattenCubic(cur, x, y, x1, y1, x2, y2, nx, ny)
        x = nx; y = ny
        break
      }
      case 'Q': case 'q': {
        let x1 = num(), y1 = num(), nx = num(), ny = num()
        if (cmd === 'q') { x1 += x; y1 += y; nx += x; ny += y }
        flattenQuad(cur, x, y, x1, y1, nx, ny)
        x = nx; y = ny
        break
      }
      case 'Z': case 'z': {
        if (cur) cur.pts.push([sx, sy])
        x = sx; y = sy
        break
      }
      default:
        i++
    }
  }
  return subpaths
}

const STEPS = 24
function flattenCubic(sp, x0, y0, x1, y1, x2, y2, x3, y3) {
  for (let s = 1; s <= STEPS; s++) {
    const t = s / STEPS, u = 1 - t
    const px = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
    const py = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
    sp.pts.push([px, py])
  }
}
function flattenQuad(sp, x0, y0, x1, y1, x2, y2) {
  for (let s = 1; s <= STEPS; s++) {
    const t = s / STEPS, u = 1 - t
    sp.pts.push([u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2])
  }
}

// ---------------- 颜色 ----------------
function parseColor(s) {
  if (!s) return null
  s = s.trim().replace('#', '')
  if (s.length === 8) {
    const a = parseInt(s.slice(0, 2), 16)
    return [parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16), parseInt(s.slice(6, 8), 16), a / 255]
  }
  if (s.length === 6) return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16), 1]
  return null
}

// ---------------- 扫描线填充（非零环绕） ----------------
function fill(px, w, h, subpaths, colorAt) {
  for (const sp of subpaths) {
    const pts = sp.pts
    if (pts.length < 3) continue
    let minY = Infinity, maxY = -Infinity
    for (const p of pts) { if (p[1] < minY) minY = p[1]; if (p[1] > maxY) maxY = p[1] }
    const y0 = Math.max(0, Math.floor(minY)), y1 = Math.min(h - 1, Math.ceil(maxY))
    for (let y = y0; y <= y1; y++) {
      const cy = y + 0.5
      const xs = []
      for (let k = 0; k < pts.length; k++) {
        const a = pts[k], b = pts[(k + 1) % pts.length]
        if ((a[1] <= cy && b[1] > cy) || (b[1] <= cy && a[1] > cy)) {
          xs.push(a[0] + ((cy - a[1]) / (b[1] - a[1])) * (b[0] - a[0]))
        }
      }
      if (xs.length < 2) continue
      xs.sort((p, q) => p - q)
      for (let k = 0; k + 1 < xs.length; k += 2) {
        const xa = Math.max(0, Math.ceil(xs[k] - 0.5))
        const xb = Math.min(w - 1, Math.floor(xs[k + 1] - 0.5))
        for (let x = xa; x <= xb; x++) {
          const c = colorAt(x + 0.5, cy)
          if (!c) continue
          const idx = (y * w + x) * 4
          const a = c[3]
          px[idx] = px[idx] * (1 - a) + c[0] * a
          px[idx + 1] = px[idx + 1] * (1 - a) + c[1] * a
          px[idx + 2] = px[idx + 2] * (1 - a) + c[2] * a
          px[idx + 3] = 255
        }
      }
    }
  }
}

// ---------------- PNG 编码 ----------------
function crc32(buf) {
  let c, crc = 0xffffffff
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    crc = (crc >>> 8) ^ c
  }
  return (crc ^ 0xffffffff) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function encodePNG(px, w, h) {
  const raw = Buffer.alloc((w * 4 + 1) * h)
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0
    Buffer.from(px.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1)
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

// ---------------- 解析 vector XML + 渲染 ----------------
const xmlPath = process.argv[2]
const outPath = process.argv[3] || 'preview.png'
const scale = parseFloat(process.argv[4] || '1')
const xml = readFileSync(xmlPath, 'utf8')

const vw = parseFloat(xml.match(/viewportWidth="([\d.]+)"/)[1])
const vh = parseFloat(xml.match(/viewportHeight="([\d.]+)"/)[1])
const W = Math.round(vw * scale), H = Math.round(vh * scale)
const px = new Float32Array(W * H * 4)

const pathRe = /<path\b([\s\S]*?)(?:\/>|>([\s\S]*?)<\/path>)/g
let m
let drawn = 0
while ((m = pathRe.exec(xml))) {
  const attrs = m[1]
  const body = m[2] || ''
  const dMatch = attrs.match(/android:pathData="([^"]+)"/)
  if (!dMatch) continue
  const subpaths = parsePath(dMatch[1])

  // 缩放到输出尺寸
  for (const sp of subpaths) for (const p of sp.pts) { p[0] *= scale; p[1] *= scale }

  let colorAt = null
  const fillAttr = attrs.match(/android:fillColor="([^"]+)"/)
  const gradBlock = body.match(/<gradient\b([\s\S]*?)<\/gradient>/)
  if (gradBlock) {
    const g = gradBlock[1]
    const sx = parseFloat(g.match(/startX="([\d.]+)"/)[1]) * scale
    const sy = parseFloat(g.match(/startY="([\d.]+)"/)[1]) * scale
    const ex = parseFloat(g.match(/endX="([\d.]+)"/)[1]) * scale
    const ey = parseFloat(g.match(/endY="([\d.]+)"/)[1]) * scale
    const stops = [...g.matchAll(/offset="([\d.]+)"\s+android:color="([^"]+)"/g)]
      .map((s) => ({ o: parseFloat(s[1]), c: parseColor(s[2]) }))
    const dx = ex - sx, dy = ey - sy
    const len2 = dx * dx + dy * dy || 1
    colorAt = (x, y) => {
      let t = ((x - sx) * dx + (y - sy) * dy) / len2
      t = t < 0 ? 0 : t > 1 ? 1 : t
      let a = stops[0], b = stops[stops.length - 1]
      for (let k = 0; k + 1 < stops.length; k++) {
        if (t >= stops[k].o && t <= stops[k + 1].o) { a = stops[k]; b = stops[k + 1]; break }
      }
      const span = b.o - a.o || 1
      const f = (t - a.o) / span
      return [
        a.c[0] + (b.c[0] - a.c[0]) * f,
        a.c[1] + (b.c[1] - a.c[1]) * f,
        a.c[2] + (b.c[2] - a.c[2]) * f,
        a.c[3] + (b.c[3] - a.c[3]) * f,
      ]
    }
  } else if (fillAttr && fillAttr[1] !== 'none') {
    const c = parseColor(fillAttr[1])
    colorAt = c ? () => c : null
  }
  if (!colorAt) continue
  fill(px, W, H, subpaths, colorAt)
  drawn++
}

// 输出为不透明 PNG（未绘制处填白）
for (let i = 0; i < W * H; i++) if (px[i * 4 + 3] === 0) { px[i * 4] = 255; px[i * 4 + 1] = 255; px[i * 4 + 2] = 255; px[i * 4 + 3] = 255 }
writeFileSync(outPath, encodePNG(px, W, H))
console.log(`已渲染 ${drawn} 个 path -> ${outPath} (${W}x${H})`)
