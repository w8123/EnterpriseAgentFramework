import { defineConfig } from 'vitest/config'
import { resolve } from 'path'

export default defineConfig({
  define: {
    __REACHAI_EMBED_SDK__: false,
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/conversation/**/*.test.ts'],
    exclude: ['src/conversation/components/**/*.test.ts'],
  },
})
