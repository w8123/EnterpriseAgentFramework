<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import type { PageRegistryView } from '@/api/embedOps'

defineProps<{
  pageRegistry: PageRegistryView[]
  selectedPageIdentity: string
  resolvePageIdentity: (page: PageRegistryView) => string
  resolveActionCount: (pageKey: string) => number
}>()

const emit = defineEmits<{
  openManualDialog: []
  selectPage: [page: PageRegistryView]
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 2</span>
        <h2>选择业务页面</h2>
      </div>
      <el-button :icon="Plus" @click="emit('openManualDialog')">手工声明动作</el-button>
    </div>

    <div v-if="pageRegistry.length" class="page-list">
      <button
        v-for="page in pageRegistry"
        :key="page.id || page.pageKey"
        class="page-row"
        :class="{ selected: resolvePageIdentity(page) === selectedPageIdentity }"
        type="button"
        @click="emit('selectPage', page)"
      >
        <span>
          <strong>{{ page.name || page.pageKey }}</strong>
          <small>{{ page.routePattern || '-' }}</small>
        </span>
        <el-tag size="small" effect="plain">{{ resolveActionCount(page.pageKey) }} 动作</el-tag>
      </button>
    </div>
    <el-empty v-else description="暂无页面上报，可先查看手动接入或手工声明动作" :image-size="88" />

    <div class="step-footer-note">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="业务页面选择完成后"
        description="可通过向下翻页进入动作选择。"
      />
    </div>
  </div>
</template>
