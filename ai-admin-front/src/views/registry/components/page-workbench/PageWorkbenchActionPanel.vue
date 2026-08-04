<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  EditPen,
  Lock,
  Plus,
  Search,
  Tools,
  VideoPlay,
} from '@element-plus/icons-vue'
import type {
  ProjectPage,
  ProjectPageAction,
} from '@/types/pageWorkbench'
import {
  pageWorkbenchActionTitle,
  pageWorkbenchHumanText,
  pageWorkbenchRiskLabel,
} from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  page: ProjectPage
}>()

const emit = defineEmits<{
  edit: [page: ProjectPage]
  debug: [page: ProjectPage, action: ProjectPageAction]
}>()

const selectedActionId = ref<number | null>(null)

watch(
  [
    () => props.page.id,
    () => props.page.actions.map((action) => action.id).join(','),
  ],
  () => {
    if (props.page.actions.some((action) => action.id === selectedActionId.value)) return
    selectedActionId.value = props.page.actions[0]?.id || null
  },
  { immediate: true },
)

const selectedAction = computed(() => props.page.actions.find(
  (action) => action.id === selectedActionId.value,
) || props.page.actions[0] || null)

const manualActionCount = computed(() => props.page.actions.filter(
  (action) => action.sourceType === 'MANUAL',
).length)

function jsonText(value: unknown) {
  if (value == null) return '{}'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function isWriteAction(action: ProjectPageAction) {
  return action.riskLevel !== 'READ'
}

function actionExecutionText(action: ProjectPageAction) {
  return isWriteAction(action)
    ? `写操作 · ${action.confirmRequired ? '执行前确认' : '确认规则待补全'}`
    : '只读操作 · 无需确认'
}

function actionSourceText(action: ProjectPageAction) {
  if (action.sourceType === 'SDK') return 'SDK 注册'
  if (action.sourceType === 'AI_SCAN') return 'AI 扫描'
  return '手动维护'
}
</script>

<template>
  <section
    class="page-action-inline foundation-section-card"
    aria-labelledby="page-action-inline-title"
  >
    <header class="foundation-section-heading">
      <span class="foundation-section-heading__icon"><el-icon><Tools /></el-icon></span>
      <div>
        <small>供 AI 调用</small>
        <h3 id="page-action-inline-title">可调用操作</h3>
        <p>登记页面可执行契约，供 AI 实施、调试验证和后续运行时调用。</p>
      </div>
      <div class="foundation-section-heading__actions">
        <span class="foundation-section-count"><strong>{{ page.actions.length }}</strong> 个操作</span>
        <el-button type="primary" plain :icon="Plus" @click="emit('edit', page)">新增手动操作</el-button>
      </div>
    </header>

    <section v-if="page.actions.length" class="resource-action-pane is-actions">
      <aside class="operation-catalog">
        <header>
          <div>
            <small>供 AI 调用</small>
            <h3>操作目录</h3>
            <p>{{ page.actions.length }} 个有效操作 · {{ manualActionCount }} 个手动维护</p>
          </div>
        </header>

        <div class="operation-catalog-list">
          <article
            v-for="action in page.actions"
            :key="action.id"
            :class="{ 'is-active': selectedAction?.id === action.id }"
            class="operation-catalog-item"
          >
            <button
              class="operation-catalog-select"
              type="button"
              :aria-pressed="selectedAction?.id === action.id"
              @click="selectedActionId = action.id"
            >
              <span class="operation-action-icon" :class="{ 'is-write': isWriteAction(action) }">
                <el-icon><Tools v-if="isWriteAction(action)" /><Search v-else /></el-icon>
              </span>
              <span class="operation-action-copy">
                <strong>{{ pageWorkbenchActionTitle(action) }}</strong>
                <code>{{ action.actionKey }}</code>
                <small>{{ actionExecutionText(action) }}</small>
              </span>
              <el-tag
                :class="{ 'is-write': isWriteAction(action) }"
                size="small"
                effect="light"
                :title="pageWorkbenchRiskLabel(action.riskLevel)"
              >{{ action.riskLevel }}</el-tag>
            </button>
            <button
              class="operation-catalog-debug"
              type="button"
              :aria-label="`调试：${pageWorkbenchActionTitle(action)}`"
              title="调试与验证"
              @click="emit('debug', page, action)"
            ><VideoPlay /></button>
          </article>
        </div>
      </aside>

      <article v-if="selectedAction" class="operation-contract-detail">
        <header>
          <div>
            <small>操作详情 · {{ actionSourceText(selectedAction) }}</small>
            <h3>{{ pageWorkbenchActionTitle(selectedAction) }}</h3>
            <code>{{ selectedAction.actionKey }}</code>
          </div>
          <el-tag
            :class="{ 'is-write': isWriteAction(selectedAction) }"
            effect="light"
            :title="pageWorkbenchRiskLabel(selectedAction.riskLevel)"
          >{{ selectedAction.riskLevel }}</el-tag>
        </header>
        <nav class="operation-contract-tabs" aria-label="操作详情">
          <button class="is-active" type="button">操作契约</button>
          <button type="button" @click="emit('debug', page, selectedAction)">调试与验证</button>
        </nav>
        <div class="operation-contract-scroll">
          <section class="operation-contract-card">
            <p>{{ pageWorkbenchHumanText(selectedAction.description, '当前操作尚未提供中文说明。') }}</p>
            <dl>
              <div><dt>操作类型</dt><dd>{{ selectedAction.actionType }}</dd></div>
              <div><dt>执行确认</dt><dd>{{ selectedAction.confirmRequired ? '必须由用户确认' : '无需额外确认' }}</dd></div>
              <div><dt>权限键</dt><dd>{{ selectedAction.permissionKey || '未单独声明' }}</dd></div>
              <div><dt>实现引用</dt><dd>{{ selectedAction.implementationRef || '尚未同步' }}</dd></div>
            </dl>
            <div class="operation-schema-grid">
              <section><strong>输入契约</strong><pre>{{ jsonText(selectedAction.inputSchema) }}</pre></section>
              <section><strong>输出契约</strong><pre>{{ jsonText(selectedAction.outputSchema) }}</pre></section>
            </div>
          </section>
        </div>
        <footer>
          <span><el-icon><Lock /></el-icon>SDK 与扫描操作由来源同步；此处只维护手动操作。</span>
          <div>
            <el-button :icon="EditPen" @click="emit('edit', page)">维护手动操作</el-button>
            <el-button type="primary" :icon="VideoPlay" @click="emit('debug', page, selectedAction)">在真实页面调试</el-button>
          </div>
        </footer>
      </article>
    </section>

    <div v-else class="foundation-action-empty">
      <span class="foundation-action-empty__icon"><el-icon><Tools /></el-icon></span>
      <div>
        <strong>尚未登记可调用操作</strong>
        <p>登记后即可在 AI 实施和调试验证中使用，不需要先进入独立操作页面。</p>
      </div>
    </div>
  </section>
</template>
