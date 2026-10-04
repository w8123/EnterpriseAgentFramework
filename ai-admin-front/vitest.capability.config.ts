import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src') } },
  test: { environment: 'happy-dom', css: false, include: [
    'src/views/capability/capabilityGovernance.test.ts',
    'src/views/capability/businessMethodInvocation.test.ts',
    'src/views/capability/capabilityDetail.test.ts',
    'src/views/capability/CapabilityKernel.test.ts',
    'src/views/capability/components/CapabilityDetailDialog.test.ts',
    'src/views/capability/components/BusinessMethodInvocationPanel.test.ts',
    'src/views/capability/components/BusinessMethodInvocationInputEditor.test.ts',
    'src/views/capability/composables/useBusinessMethodInvocation.test.ts',
    'src/views/capability/components/CapabilityParameterTable.test.ts',
    'src/views/registry/components/CapabilityReviewPanel.test.ts',
    'src/views/api/httpApiTrial.test.ts',
    'src/views/api/httpApiSourceComparison.test.ts',
    'src/views/api/HttpApiDetail.test.ts',
    'src/views/api/HttpApiDetail.write.test.ts',
    'src/composables/consoleInvocationReferences.test.ts',
  ] },
})
