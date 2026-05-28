import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'src/sdk/index.ts'),
      name: 'ReachAI',
      formats: ['es', 'cjs', 'umd'],
      cssFileName: 'style',
      fileName: (format) => {
        if (format === 'umd') return 'reachai-chat-embed.umd.js'
        if (format === 'cjs') return 'index.cjs'
        return 'index.mjs'
      },
    },
  },
})
