import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  define: {
    __REACHAI_EMBED_SDK__: false,
  },
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
      /** reachai-control-service：统一收口 /api，避免前端直连 Runtime/Capability 内部服务 */
      '/api': {
        target: process.env.VITE_CONTROL_API_TARGET || 'http://localhost:18603',
        changeOrigin: true,
        timeout: 600_000,
        proxyTimeout: 600_000,
        bypass(req) {
          // SPA pages such as /api-market and /api-graph share the /api prefix.
          // Browser document navigations must still resolve to Vue Router; XHR/fetch
          // requests use JSON-oriented Accept headers and continue to Control.
          const accept = String(req.headers.accept || '')
          if (accept.includes('text/html')) {
            return '/index.html'
          }
        },
        configure(proxy) {
          // SSE：禁止中间层缓冲，保证 message.delta 按帧到达浏览器
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            const contentType = String(proxyRes.headers['content-type'] || '')
            if (!contentType.includes('text/event-stream')) return
            res.setHeader('Cache-Control', 'no-cache, no-transform')
            res.setHeader('X-Accel-Buffering', 'no')
            // 压缩会缓冲整包，SSE 必须 identity
            if (proxyRes.headers['content-encoding']) {
              delete proxyRes.headers['content-encoding']
            }
          })
        },
      },
      '^/model/(templates|instances|chat)(/.*)?(\\?.*)?$': {
        target: 'http://localhost:18601',
        changeOrigin: true,
        bypass(req) {
          // SPA routes share /model/instances*; keep HTML navigations on the Vite app.
          const accept = String(req.headers.accept || '')
          if (accept.includes('text/html')) {
            return '/index.html'
          }
        },
      },
    },
  },
})
