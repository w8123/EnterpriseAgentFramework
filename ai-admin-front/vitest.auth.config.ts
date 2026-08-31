import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    css: false,
    include: [
      'src/auth/**/*.test.ts',
      'src/api/requestAuthFailure.test.ts',
      'src/components/common/sidebarMenu.test.ts',
      'src/utils/platformAuth.test.ts',
    ],
  },
})
