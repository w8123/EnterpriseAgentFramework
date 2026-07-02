<script setup lang="ts">
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'

defineProps<{
  selectedPage: PageRegistryView | null
  filteredActions: PageActionRegistryView[]
  selectedActions: PageActionRegistryView[]
  resolveActionRowKey: (action: PageActionRegistryView) => string
  isActionSelected: (action: PageActionRegistryView) => boolean
}>()

const emit = defineEmits<{
  selectAll: []
  clearAll: []
  toggleAction: [action: PageActionRegistryView]
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 3</span>
        <h2>选择页面动作</h2>
      </div>
      <el-tag v-if="selectedPage" effect="plain">{{ selectedPage.name || selectedPage.pageKey }}</el-tag>
    </div>

    <div v-if="filteredActions.length" class="action-select-area">
      <div class="action-toolbar">
        <span><strong>{{ selectedActions.length }}</strong> / {{ filteredActions.length }} 个动作已选</span>
        <div class="action-toolbar-actions">
          <button type="button" @click="emit('selectAll')">全选</button>
          <button type="button" @click="emit('clearAll')">清空</button>
        </div>
      </div>
      <div class="action-card-list">
        <button
          v-for="action in filteredActions"
          :key="resolveActionRowKey(action)"
          class="action-card"
          :class="{ selected: isActionSelected(action) }"
          type="button"
          @click="emit('toggleAction', action)"
        >
          <span class="action-check" aria-hidden="true">
            <span v-if="isActionSelected(action)">✓</span>
          </span>
          <span class="action-main">
            <strong>{{ action.title || action.actionKey }}</strong>
            <small>{{ action.description || action.actionKey }}</small>
          </span>
          <span class="action-meta">
            <small>actionKey</small>
            <strong>{{ action.actionKey }}</strong>
          </span>
          <span class="action-flags">
            <el-tag size="small" effect="plain">{{ action.confirmRequired ? '需确认' : '免确认' }}</el-tag>
            <small>{{ action.lastSeenAt || '未上报时间' }}</small>
          </span>
        </button>
      </div>
    </div>
    <el-empty v-else description="当前页面暂无动作，可先手工声明动作草案" :image-size="88" />

    <div class="step-footer-note">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="动作选择完成后"
        description="可通过向下翻页进入草稿配置。"
      />
    </div>
  </div>
</template>
