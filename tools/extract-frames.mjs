// 把视频抽成帧并输出卡片图（contact sheet），用于在电脑上「看」用户录屏。
//
// 两种模式：
//   1. 固定间隔：按 fps 均匀抽帧，适合短录屏、想看清全过程
//   2. 场景变化：只在画面明显变化时抽帧（屏幕录制的静止段会大量重复），
//      适合界面操作类视频 —— 每次点击/切换都会产生一个变化点
//
// 用法:
//   node tools/extract-frames.mjs <视频> <输出目录> [模式=scene|even] [每行帧数=4]
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'

const ffmpeg = process.env.FFMPEG || resolve('.toolchain/ffmpeg/ffmpeg.exe')
const ffprobe = process.env.FFPROBE || resolve('.toolchain/ffmpeg/ffprobe.exe')

const video = process.argv[2]
const outDir = process.argv[3] || '.toolchain/frames'
const mode = (process.argv[4] || 'scene').toLowerCase()
const cols = parseInt(process.argv[5] || '4', 10)

if (!video) {
  console.error('用法: node tools/extract-frames.mjs <视频> [输出目录] [scene|even] [每行帧数]')
  process.exit(2)
}
if (!existsSync(video)) {
  console.error('找不到视频文件:', video)
  process.exit(2)
}
if (!existsSync(ffmpeg)) {
  console.error('找不到 ffmpeg:', ffmpeg)
  process.exit(2)
}

// ---------- 1. 先读元信息 ----------
function probe(file) {
  const out = execFileSync(
    ffprobe,
    [
      '-v', 'error',
      '-select_streams', 'v:0',
      '-show_entries', 'format=duration,size,format_name',
      '-show_entries', 'stream=width,height,avg_frame_rate,codec_name,nb_frames',
      '-of', 'json',
      file,
    ],
    { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }
  )
  const j = JSON.parse(out)
  const s = (j.streams && j.streams[0]) || {}
  const f = j.format || {}
  const [num, den] = String(s.avg_frame_rate || '0/1').split('/').map(Number)
  return {
    duration: Number(f.duration || 0),
    sizeBytes: Number(f.size || 0),
    container: f.format_name || '?',
    width: s.width || 0,
    height: s.height || 0,
    fps: den ? num / den : 0,
    codec: s.codec_name || '?',
    frames: Number(s.nb_frames || 0),
  }
}

const info = probe(video)
const mmss = (t) => {
  const m = Math.floor(t / 60)
  const s = t % 60
  return `${String(m).padStart(2, '0')}:${s.toFixed(1).padStart(4, '0')}`
}

console.log('=== 视频信息 ===')
console.log('  文件  :', basename(video))
console.log('  容器/编码:', info.container, '/', info.codec)
console.log('  分辨率:', `${info.width}x${info.height}`)
console.log('  帧率  :', info.fps.toFixed(2), 'fps')
console.log('  时长  :', info.duration.toFixed(1), 's')
console.log('  大小  :', (info.sizeBytes / 1048576).toFixed(1), 'MB')

// ---------- 2. 抽帧 ----------
if (existsSync(outDir)) rmSync(outDir, { recursive: true, force: true })
mkdirSync(outDir, { recursive: true })

// 缩到宽 720，既看得清 UI 又不会太大；统一转 jpg 控制体积
const scale = 'scale=720:-2'
const args = ['-hide_banner', '-loglevel', 'error', '-i', video]

if (mode === 'scene') {
  // 场景变化阈值 0.25：界面切换/弹窗这种变化远超此值，而轻微动画不会触发
  args.push('-vf', `${scale},select='gt(scene,0.25)'`, '-vsync', 'vfr')
} else {
  // 均匀抽帧：最多 60 张，避免长视频产出过多图片
  const fps = info.duration > 0 ? Math.min(1, 60 / info.duration) : 1
  args.push('-vf', `${scale},fps=${fps.toFixed(4)}`)
}
args.push('-q:v', '3', join(outDir, 'f%03d.jpg'))

execFileSync(ffmpeg, args, { stdio: ['ignore', 'ignore', 'inherit'] })

const frames = readdirSync(outDir).filter((f) => f.endsWith('.jpg')).sort()
console.log(`\n=== 抽帧结果（${mode === 'scene' ? '场景变化' : '固定间隔'}模式）===`)
console.log('  共', frames.length, '张 ->', outDir)

// ---------- 3. 生成卡片图，便于一次看多帧 ----------
if (frames.length > 0) {
  const perSheet = cols * cols
  const sheets = Math.ceil(frames.length / perSheet)
  for (let s = 0; s < sheets; s++) {
    const sheet = join(outDir, `sheet-${String(s + 1).padStart(2, '0')}.jpg`)
    const start = s * perSheet + 1
    execFileSync(
      ffmpeg,
      [
        '-hide_banner', '-loglevel', 'error',
        '-start_number', String(start),
        '-i', join(outDir, 'f%03d.jpg'),
        '-frames:v', '1',
        '-vf', `tile=${cols}x${cols}:margin=6:padding=4:color=white`,
        '-q:v', '3',
        sheet,
      ],
      { stdio: ['ignore', 'ignore', 'inherit'] }
    )
    const kb = (statSync(sheet).size / 1024).toFixed(0)
    console.log(`  卡片图 ${basename(sheet)}  含第 ${start}~${Math.min(start + perSheet - 1, frames.length)} 帧  ${kb} KB`)
  }
}

// ---------- 4. 打印时间轴提示 ----------
console.log('\n=== 各帧对应的大致时刻 ===')
if (mode === 'scene') {
  console.log('  （场景变化模式下帧序号与时刻不是线性关系，下面按平均分布估算）')
}
frames.forEach((f, i) => {
  const t = info.duration > 0 ? (info.duration * (i + 0.5)) / frames.length : 0
  if (i < 24) console.log(`  ${f}  ≈ ${mmss(t)}`)
})
if (frames.length > 24) console.log(`  ... 共 ${frames.length} 帧`)
