// 把嵌入对话 SDK 的浏览器产物发布到管理端站点 /sdk/embed-chat/，业务系统可直接用 <script> 引用。
// 注意不能用 /embed/：Nginx 把 /embed/ 转给 Control。
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { requireBrowserBundleVersion, publishBrowserBundle } from './embed-chat-browser-bundle.mjs'

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

const manifestPath = join(target, 'manifest.json')
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
requireBrowserBundleVersion(source, manifest.version)
const version = publishBrowserBundle(source, target)
console.log(`[copy-embed-chat] published SDK ${version} to ${target}`)
