import { resolve } from 'path'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    include: [
      'src/auth/**/*.test.ts',
      'src/api/requestAuthFailure.test.ts',
      'src/utils/platformAuth.test.ts',
    ],
  },
})
