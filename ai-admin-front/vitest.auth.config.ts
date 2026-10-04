import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'happy-dom',
    css: false,
    include: [
      'src/auth/**/*.test.ts',
      'src/api/requestAuthFailure.test.ts',
      'src/components/common/sidebarMenu.test.ts',
      'src/components/common/AppSidebar.projectScope.test.ts',
      'src/components/common/AppSidebar.keyboard.test.ts',
      'src/store/project.test.ts',
      'src/composables/usePageProjectScope.test.ts',
      'src/views/agent/AgentList.test.ts',
      'src/views/registry/RegistryProjectList.test.ts',
      'src/utils/platformAuth.test.ts',
      'src/utils/projectScope.test.ts',
    ],
  },
})
