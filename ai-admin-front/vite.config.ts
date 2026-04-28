import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    // 常见排除段：如 2931–3030、5100–5199（本机 netsh 可见），落在段内会 EACCES
    port: 5200,
    proxy: {
      '/ai': {
        target: 'http://localhost:8602',
        changeOrigin: true,
      },
      /** ai-agent-service：统一走 8603，避免新增 /api/skills、/api/tool-acl 等路径漏配代理 */
      '/api': {
        target: 'http://localhost:8603',
        changeOrigin: true,
      },
      '/model': {
        target: 'http://localhost:8601',
        changeOrigin: true,
      },
    },
  },
})
