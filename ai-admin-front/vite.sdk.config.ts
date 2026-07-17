import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  publicDir: false,
  define: {
    __REACHAI_EMBED_SDK__: true,
    // UMD 浏览器宿主没有 Node process；必须编译期替换，否则 ReachAI 全局导出为空
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  plugins: [
    vue({
      customElement: /ReachAiChatElement\.ce\.vue$/,
    }),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    outDir: 'dist-sdk/embed-chat',
    emptyOutDir: true,
    // Prism 氛围图 / 头像必须作为独立资源分发，禁止 base64 打进 style.css
    assetsInlineLimit: 0,
    lib: {
      entry: resolve(__dirname, 'src/sdk/index.ts'),
      name: 'ReachAI',
      formats: ['es', 'cjs', 'umd'],
      cssFileName: 'style',
      fileName: (format) => {
        if (format === 'umd') return 'reachai-chat-embed.umd.js'
        if (format === 'cjs') return 'index.cjs'
        return 'index.mjs'
      },
    },
    rollupOptions: {
      external: ['element-plus'],
      output: {
        inlineDynamicImports: true,
        assetFileNames: (assetInfo) => {
          const name = assetInfo.names?.[0] || assetInfo.name || 'asset'
          if (/\.(webp|png|jpe?g|gif|svg)$/i.test(name)) {
            return '[name][extname]'
          }
          if (name.endsWith('.css') || assetInfo.name === 'style.css') {
            return 'style.css'
          }
          return 'assets/[name]-[hash][extname]'
        },
      },
    },
  },
})
