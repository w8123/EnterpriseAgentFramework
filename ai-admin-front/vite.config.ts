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
        target: 'http://localhost:18602',
        changeOrigin: true,
      },
      /** ai-agent-service：统一走 18603，避免新增 /api/capabilities、/api/tool-acl 等路径漏配代理 */
      '/api': {
        target: 'http://localhost:18603',
        changeOrigin: true,
      },
      '^/model/(providers|chat)(/.*)?$': {
        target: 'http://localhost:18601',
        changeOrigin: true,
      },
    },
  },
})
