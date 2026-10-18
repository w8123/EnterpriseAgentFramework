// 把嵌入对话 SDK 的浏览器产物发布到管理端站点 /sdk/embed-chat/，业务系统可直接用 <script> 引用。
// 注意不能用 /embed/：Nginx 把 /embed/ 转给 Control。
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = join(root, 'dist-sdk', 'embed-chat')
const target = join(root, 'dist', 'sdk', 'embed-chat')

if (!existsSync(join(source, 'reachai-chat-embed.umd.js')) || !existsSync(join(source, 'style.css'))) {
  console.error(`[copy-embed-chat] missing SDK build output in ${source}; run npm run build:sdk first`)
  process.exit(1)
}
if (!existsSync(join(root, 'dist'))) {
  console.error('[copy-embed-chat] missing dist/; run vite build first')
  process.exit(1)
}

rmSync(target, { recursive: true, force: true })
mkdirSync(target, { recursive: true })
const browserFiles = new Set(['reachai-chat-embed.umd.js', 'style.css'])
for (const entry of readdirSync(source, { withFileTypes: true })) {
  const isAsset = entry.isFile() && /\.(webp|png|jpe?g|gif|svg)$/i.test(entry.name)
  if (browserFiles.has(entry.name) || isAsset || (entry.isDirectory() && entry.name === 'assets')) {
    cpSync(join(source, entry.name), join(target, entry.name), { recursive: true })
  }
}
console.log(`[copy-embed-chat] published SDK to ${target}`)
