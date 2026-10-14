<script setup lang="ts">
import { computed } from 'vue'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'

const props = defineProps<{ contentMd?: string | null }>()
const content = computed(() => {
  const template = document.createElement('template')
  template.innerHTML = renderSafeMarkdown(props.contentMd)
  for (const element of template.content.querySelectorAll('pre, table')) {
    element.setAttribute('tabindex', '0')
    element.setAttribute('role', 'region')
    element.setAttribute('aria-label', element.tagName === 'PRE' ? '代码，可横向滚动' : '说明表格，可横向滚动')
  }
  return template.innerHTML
})
</script>

<template>
  <div class="markdown-body capability-semantic-document" v-html="content" />
</template>

<style scoped lang="scss">
.capability-semantic-document { min-width: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.capability-semantic-document :deep(h1), .capability-semantic-document :deep(h2), .capability-semantic-document :deep(h3),
.capability-semantic-document :deep(h4), .capability-semantic-document :deep(h5), .capability-semantic-document :deep(h6) { margin: 22px 0 10px; font-weight: 600; line-height: 1.6; color: var(--text-primary); }
.capability-semantic-document :deep(h1) { font-size: 16px; }
.capability-semantic-document :deep(h2) { font-size: 15px; }
.capability-semantic-document :deep(h3) { font-size: 14px; }
.capability-semantic-document :deep(h4), .capability-semantic-document :deep(h5), .capability-semantic-document :deep(h6) { font-size: 13px; }
.capability-semantic-document :deep(> :first-child) { margin-top: 0; }
.capability-semantic-document :deep(p) { margin: 10px 0; }
.capability-semantic-document :deep(ul), .capability-semantic-document :deep(ol) { margin: 10px 0; padding-left: 22px; }
.capability-semantic-document :deep(li + li) { margin-top: 4px; }
.capability-semantic-document :deep(a) { color: var(--brand-active); }
.capability-semantic-document :deep(a:focus-visible) { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.capability-semantic-document :deep(blockquote) { margin: 12px 0; padding: 8px 12px; border-left: 3px solid var(--border-readable); background: var(--surface-solid-page); color: var(--text-secondary); }
.capability-semantic-document :deep(blockquote > :first-child), .capability-semantic-document :deep(blockquote > :last-child) { margin-block: 0; }
.capability-semantic-document :deep(table) { display: block; max-width: 100%; width: max-content; margin: 14px 0; overflow-x: auto; border-collapse: collapse; font-size: 12px; }
.capability-semantic-document :deep(th), .capability-semantic-document :deep(td) { padding: 8px 12px; border: 1px solid var(--border-divider); text-align: left; min-width: 100px; }
.capability-semantic-document :deep(th:nth-child(n+5)), .capability-semantic-document :deep(td:nth-child(n+5)) { min-width: 220px; }
.capability-semantic-document :deep(th) { background: var(--surface-solid-page); color: var(--text-primary); font-weight: 600; }
.capability-semantic-document :deep(td) { max-width: 420px; }
.capability-semantic-document :deep(pre) { max-width: 100%; overflow-x: auto; margin: 12px 0; padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-solid-control); }
.capability-semantic-document :deep(code) { font: 12px/1.7 ui-monospace, Consolas, monospace; overflow-wrap: anywhere; }
.capability-semantic-document :deep(pre code) { white-space: pre; overflow-wrap: normal; padding: 0; background: transparent; color: var(--text-primary); }
.capability-semantic-document :deep(pre:focus-visible), .capability-semantic-document :deep(table:focus-visible) { outline: 2px solid var(--border-focus); outline-offset: -2px; }
.capability-semantic-document :deep(img) { max-width: 100%; height: auto; }
.capability-semantic-document :deep(hr) { margin: 20px 0; border: 0; border-top: 1px solid var(--border-divider); }
</style>
