#!/usr/bin/env node
// 重新生成操作手册里的截图（docs/images/manual/*.webp）。
//
// 截图来自本机一键体验环境（deploy/quickstart），界面改了以后重跑一次即可换成新图：
//   cd deploy/quickstart && docker compose up -d --build
//   node scripts/capture-manual-screenshots.mjs                 # 全部
//   node scripts/capture-manual-screenshots.mjs business-       # 只拍名字以 business- 开头的
//   node scripts/capture-manual-screenshots.mjs --list          # 列出全部截图
//
// 需要 Node.js、Playwright（require('playwright') 可用）和 Python 3 + Pillow（把 PNG 转成 WebP）。
// 地址和账号默认是一键体验的演示值，可用环境变量覆盖：
//   REACHAI_URL=http://localhost:18080 YUDAO_URL=http://localhost:48080
//   REACHAI_ADMIN=admin/admin123 YUDAO_ADMIN=admin/admin123
//
// 带 fresh 标记的截图拍的是「刚装好、什么都没配」的平台，要在 reachai-init 运行前拍；
// 其余截图需要一键体验初始化完成（芋道已接入、ERP 助手已发布）。

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { shots } from './manual-screenshots/shots.mjs'

const require = createRequire(import.meta.url)
const { chromium } = require('playwright')

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const outDir = path.join(repoRoot, 'docs/images/manual')
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-manual-'))

const args = process.argv.slice(2)
if (args.includes('--list')) {
  for (const shot of shots) console.log(`${shot.name}${shot.fresh ? '  (fresh)' : ''}  ${shot.title}`)
  process.exit(0)
}
const filters = args.filter(arg => !arg.startsWith('--'))
const selected = shots.filter(shot => !filters.length || filters.some(prefix => shot.name.startsWith(prefix)))
if (!selected.length) {
  console.error('没有匹配的截图，用 --list 查看全部名字')
  process.exit(1)
}

const env = {
  reachai: (process.env.REACHAI_URL || 'http://localhost:18080').replace(/\/$/, ''),
  yudao: (process.env.YUDAO_URL || 'http://localhost:48080').replace(/\/$/, ''),
  reachaiAdmin: (process.env.REACHAI_ADMIN || 'admin/admin123').split('/'),
  yudaoAdmin: (process.env.YUDAO_ADMIN || 'admin/admin123').split('/'),
}

function toWebp(pngFile, name) {
  const target = path.join(outDir, `${name}.webp`)
  const script = [
    'import sys',
    'from PIL import Image',
    'image = Image.open(sys.argv[1]).convert("RGB")',
    'if image.width > 1800: image = image.resize((1800, round(image.height * 1800 / image.width)), Image.Resampling.LANCZOS)',
    'image.save(sys.argv[2], format="WEBP", quality=82, method=6)',
  ].join('\n')
  const result = spawnSync('python3', ['-c', script, pngFile, target], { stdio: 'inherit' })
  if (result.status !== 0) throw new Error(`转换 WebP 失败：${name}`)
  return target
}

const browser = await chromium.launch()
const failed = []
try {
  for (const shot of selected) {
    const context = await browser.newContext({
      viewport: shot.viewport || { width: 1440, height: 900 },
      deviceScaleFactor: 2,
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai',
    })
    const page = await context.newPage()
    const pngFile = path.join(tmpDir, `${shot.name}.png`)
    try {
      let clip = await shot.run({ page, context, env })
      // 打开了新标签页（例如员工个人页）的截图返回 { __page }，拍那个页面。
      let target = page
      if (clip && clip.__page) {
        target = clip.__page
        const { __page, ...rest } = clip
        clip = Object.keys(rest).length ? rest : null
      }
      await target.waitForTimeout(400)
      if (clip && typeof clip.screenshot === 'function') await clip.screenshot({ path: pngFile })
      else await target.screenshot({ path: pngFile, ...(clip || {}) })
      const file = toWebp(pngFile, shot.name)
      console.log(`✓ ${shot.name}  ${path.relative(repoRoot, file)}`)
    } catch (error) {
      failed.push(shot.name)
      console.error(`✗ ${shot.name}: ${error.message.split('\n')[0]}`)
      await page.screenshot({ path: path.join(tmpDir, `${shot.name}.failed.png`) }).catch(() => {})
    } finally {
      await context.close()
    }
  }
} finally {
  await browser.close()
}
if (failed.length) {
  console.error(`\n${failed.length} 张截图失败：${failed.join(', ')}。失败时的页面截图在 ${tmpDir}`)
  process.exit(1)
}
