import { copyFile, mkdir, readdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { basename, dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outDir = resolve(root, 'dist-sdk', 'embed-chat')
const typesSource = resolve(root, 'src', 'sdk', 'embed-chat.d.ts')
const sharedAssetsDir = resolve(root, 'src', 'conversation', 'assets')

await mkdir(outDir, { recursive: true })
await copyFile(typesSource, resolve(outDir, 'index.d.ts'))

// 确保共享 WebP 始终随 SDK 分发（即使 Vite 哈希文件名不同）
for (const name of await readdir(sharedAssetsDir)) {
  if (!/\.webp$/i.test(name)) continue
  await copyFile(resolve(sharedAssetsDir, name), resolve(outDir, name))
}

const assetFiles = (await readdir(outDir))
  .filter((name) => /\.(webp|png|jpe?g|gif|svg)$/i.test(name))
  .sort()
  .map((name) => basename(name))
const manifest = {
  name: '@reachai/embed-chat',
  version: '1.0.0-SNAPSHOT',
  private: false,
  type: 'module',
  types: './index.d.ts',
  main: './index.cjs',
  module: './index.mjs',
  exports: {
    '.': {
      types: './index.d.ts',
      import: './index.mjs',
      require: './index.cjs',
    },
    './style.css': './style.css',
  },
  files: [
    'index.mjs',
    'index.cjs',
    'index.d.ts',
    'reachai-chat-embed.umd.js',
    'style.css',
    ...assetFiles,
  ],
}

await writeFile(resolve(outDir, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`)

if (!assetFiles.length) {
  console.warn('[write-embed-chat-package] warning: no image assets found next to style.css')
} else {
  console.log(`[write-embed-chat-package] packaged assets: ${assetFiles.join(', ')}`)
}
