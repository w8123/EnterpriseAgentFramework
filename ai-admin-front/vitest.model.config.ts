import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    include: [
      'src/views/model/**/*.test.ts',
      'src/components/model/**/*.test.ts',
      'src/utils/modelSelection.test.ts',
    ],
    css: false,
  },
})
