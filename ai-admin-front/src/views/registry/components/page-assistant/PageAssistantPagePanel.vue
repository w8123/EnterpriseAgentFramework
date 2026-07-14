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

<style scoped lang="scss">
/* This content is rendered by a child component, so its visual rules live here
 * instead of inheriting the parent wizard's scoped stylesheet. */
.step-screen {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 5px 0 0;
  color: #11183a;
  font-size: 28px;
  line-height: 1.18;
}

.step-kicker {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.24);
  border-radius: 999px;
  background: rgb(var(--brand-selected-rgb) / 0.68);
  color: var(--brand-active);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.page-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  margin: 14px 0;
}

.page-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-width: 0;
  padding: 16px;
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.3);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.72);
  box-shadow: 0 10px 22px rgb(var(--brand-primary-rgb) / 0.08);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.page-row:hover {
  border-color: rgb(var(--brand-hover-rgb) / 0.58);
  box-shadow: 0 14px 30px rgb(var(--brand-primary-rgb) / 0.12);
  transform: translateY(-1px);
}

.page-row > span {
  min-width: 0;
}

.page-row strong,
.page-row small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-row strong {
  color: #20314f;
  font-size: 15px;
}

.page-row small {
  margin-top: 4px;
  color: #667085;
  font-size: 12px;
}

.page-row.selected {
  border-color: rgb(var(--brand-hover-rgb) / 0.72);
  background:
    linear-gradient(135deg, rgb(255 255 255 / 0.9), rgb(var(--brand-selected-rgb) / 0.64)),
    radial-gradient(circle at 0% 0%, rgb(var(--brand-hover-rgb) / 0.18), transparent 42%);
  box-shadow:
    0 16px 34px rgb(var(--brand-primary-rgb) / 0.14),
    0 0 0 2px rgb(var(--brand-hover-rgb) / 0.1);
}

.step-footer-note {
  margin-top: auto;
  padding-top: 30px;
}

.step-footer-note :deep(.el-alert) {
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.44);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.64);
}

@media (max-width: 720px) {
  .panel-head {
    flex-direction: column;
    align-items: stretch;
  }

  .page-list {
    grid-template-columns: 1fr;
  }
}
</style>
