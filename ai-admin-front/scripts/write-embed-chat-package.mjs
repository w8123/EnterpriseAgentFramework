import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outDir = resolve(root, 'dist-sdk', 'embed-chat')

await mkdir(outDir, { recursive: true })

const manifest = {
  name: '@reachai/embed-chat',
  version: '1.0.0-SNAPSHOT',
  private: false,
  type: 'module',
  main: './index.cjs',
  module: './index.mjs',
  exports: {
    '.': {
      import: './index.mjs',
      require: './index.cjs',
    },
    './style.css': './style.css',
  },
  files: [
    'index.mjs',
    'index.cjs',
    'reachai-chat-embed.umd.js',
    'style.css',
  ],
}

await writeFile(resolve(outDir, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`)
