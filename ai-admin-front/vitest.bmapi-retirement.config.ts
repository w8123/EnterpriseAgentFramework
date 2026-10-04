import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()], resolve: { alias: { '@': resolve(__dirname, 'src') } },
  test: { environment: 'happy-dom', css: false, include: [
    'src/views/workflow/studio-panels/ToolConfigPanel.test.ts',
    'src/views/workflow/studio-panels/InteractionConfigPanel.retirement.test.ts',
    'src/views/scan/scanProjectOwnerRoutes.test.ts',
  ] },
})
