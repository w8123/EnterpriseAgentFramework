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
    port: 3000,
    proxy: {
      '/ai': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/agent': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/chat': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/tools': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/scan-projects': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/tool-retrieval': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/model': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
})
