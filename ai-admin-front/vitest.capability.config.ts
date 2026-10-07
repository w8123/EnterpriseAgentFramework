import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src') } },
  test: { environment: 'happy-dom', css: false, include: [
    'src/views/capability/capabilityGovernance.test.ts',
    'src/views/capability/businessMethodInvocation.test.ts',
    'src/views/capability/businessMethodDetail.test.ts',
    'src/views/capability/BusinessMethodCatalog.test.ts',
    'src/views/capability/BusinessCapabilityWorkbench.test.ts',
    'src/views/capability/components/BusinessMethodDetailDialog.test.ts',
    'src/views/capability/components/BusinessMethodInvocationPanel.test.ts',
    'src/views/capability/components/BusinessMethodInvocationInputEditor.test.ts',
    'src/views/capability/composables/useBusinessMethodInvocation.test.ts',
    'src/views/capability/components/CapabilityParameterTable.test.ts',
    'src/views/registry/components/CapabilityReviewPanel.test.ts',
    'src/views/registry/components/ProjectSourceChanges.test.ts',
    'src/api/businessMethod.test.ts',
    'src/api/scanProject.test.ts',
    'src/utils/scanProjectBlockers.test.ts',
    'src/views/registry/composables/useRegistryProjectDetailActions.test.ts',
    'src/views/api/httpApiTrial.test.ts',
    'src/views/api/httpApiSourceComparison.test.ts',
    'src/views/api/HttpApiDetail.test.ts',
    'src/views/api/HttpApiCatalog.test.ts',
    'src/views/api/HttpApiDetail.write.test.ts',
    'src/composables/consoleInvocationReferences.test.ts',
    'src/composables/useCatalogQuery.test.ts',
  ] },
})
