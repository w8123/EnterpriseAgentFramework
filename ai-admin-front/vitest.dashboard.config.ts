import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// 看板按本地时间分桶，测试固定按北京时间断言，避免 CI（UTC）结果不同
process.env.TZ = 'Asia/Shanghai'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/views/dashboard/**/*.test.ts'],
    css: false,
  },
})
