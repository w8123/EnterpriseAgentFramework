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
      'src/utils/documentImport.test.ts',
      'src/components/DocumentImportJobCard.test.ts',
      'src/components/ChunkPreview.test.ts',
    ],
    css: false,
  },
})
