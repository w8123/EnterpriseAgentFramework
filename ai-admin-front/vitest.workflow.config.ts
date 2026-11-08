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
      'src/views/workflow/**/*.test.ts',
      'src/views/agent/**/*.test.ts',
      'src/components/agent/**/*.test.ts',
      'src/utils/studio*.test.ts',
      'src/utils/workflowTemplates.test.ts',
      'src/utils/workflowExchange.test.ts',
    ],
    css: false,
  },
})
