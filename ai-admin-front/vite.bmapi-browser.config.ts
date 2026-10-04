import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

/**
 * Isolated BMAPI-2D browser gate: the Vite process talks only to the ephemeral
 * Control BFF.  It deliberately has no direct Runtime, Capability, Model, or
 * Knowledge proxy and does not provide mocked routes or responses.
 */
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
    host: '127.0.0.1',
    port: Number(process.env.BMAPI_BROWSER_VITE_PORT || 5200),
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_CONTROL_API_TARGET || 'http://127.0.0.1:1',
        changeOrigin: true,
        timeout: 600_000,
        proxyTimeout: 600_000,
        bypass(req) {
          // Vue routes such as /api-market share the API prefix.  Document
          // navigations stay in the SPA; JSON fetches remain real Control calls.
          const accept = String(req.headers.accept || '')
          if (accept.includes('text/html')) {
            return '/index.html'
          }
        },
      },
    },
  },
})
