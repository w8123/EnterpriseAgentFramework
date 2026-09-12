import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src') } },
  test: { environment: 'happy-dom', css: false, include: [
    'src/views/capability/capabilityGovernance.test.ts',
    'src/views/registry/components/CapabilityReviewPanel.test.ts',
  ] },
})
