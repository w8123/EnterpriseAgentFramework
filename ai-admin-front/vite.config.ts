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
    // 3000 落在系统排除范围 2931–3030 内（Hyper-V/WSL 等常见），会 EACCES
    port: 5173,
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
