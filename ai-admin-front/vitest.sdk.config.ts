import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  define: {
    __REACHAI_EMBED_SDK__: false,
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/sdk/**/*.test.ts', 'src/conversation/components/**/*.test.ts'],
    css: false,
  },
})
