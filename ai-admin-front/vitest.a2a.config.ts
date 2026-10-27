import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src') } },
  test: {
    environment: 'happy-dom',
    css: false,
    include: ['src/views/a2a-hub/**/*.test.ts', 'src/utils/a2aHub.test.ts'],
  },
})
