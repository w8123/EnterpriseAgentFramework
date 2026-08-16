import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

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
  test: {
    environment: 'happy-dom',
    include: [
      'src/components/ai-coding/**/*.test.ts',
      'src/composables/useAiCodingTask.test.ts',
      'src/utils/aiCoding*.test.ts',
      'src/utils/pageWorkbenchPresentation.test.ts',
      'src/utils/toolTestArgument.test.ts',
      'src/views/registry/composables/useBusinessPageWorkbench.test.ts',
      'src/views/registry/composables/useRegistryProjectAiCodingAccess.test.ts',
      'src/views/registry/composables/useRegistryProjectDetailData.test.ts',
      'src/views/registry/composables/useSdkAccessWizardData.test.ts',
      'src/views/registry/composables/useSdkAccessWizardProgress.test.ts',
      'src/views/registry/composables/useSdkAccessWizardSnippets.test.ts',
    ],
    css: false,
  },
})
