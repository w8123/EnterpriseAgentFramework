<template>
  <div class="code-shell">
    <div class="code-toolbar">
      <span>{{ title }}</span>
      <el-button size="small" text :icon="DocumentCopy" @click="emit('copy', code)">
        {{ copyLabel }}
      </el-button>
    </div>
    <pre class="code-panel"><code v-html="renderedCode" /></pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DocumentCopy } from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{
  title: string
  code: string
  highlightedCode?: string
  copyLabel?: string
}>(), {
  highlightedCode: '',
  copyLabel: '复制',
})

const emit = defineEmits<{
  copy: [code: string]
}>()

const renderedCode = computed(() => props.highlightedCode || escapeHtml(props.code))

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}
</script>

<style scoped lang="scss">
.code-shell {
  overflow: hidden;
  border: 1px solid rgba(96, 165, 250, 0.2);
  border-radius: 8px;
  background: rgba(2, 6, 23, 0.72);
  box-shadow: 0 0 0 1px rgba(125, 211, 252, 0.05), 0 20px 52px rgba(0, 0, 0, 0.28);
}

.code-toolbar {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  background: rgba(15, 23, 42, 0.76);
  color: #dbeafe;
  font-size: 13px;
  font-weight: 700;

  :deep(.el-button) {
    color: #d9e2ef;
  }
}

.code-panel {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 18px 0 0;
  border-radius: 0;
  background:
    linear-gradient(90deg, rgba(59, 130, 246, 0.05), transparent 16%),
    rgba(2, 6, 23, 0.78);
  color: #e5e7eb;
  font-family: Consolas, "JetBrains Mono", monospace;
  font-size: 13px;
  line-height: 1.6;

  code {
    display: block;
    min-width: 100%;
    color: #dbeafe;
  }

  :deep(.code-tag),
  :deep(.code-key) {
    color: #7dd3fc;
    font-weight: 650;
  }

  :deep(.code-string) {
    color: #fcd34d;
  }

  :deep(.code-keyword) {
    color: #c4b5fd;
    font-weight: 650;
  }

  :deep(.code-function) {
    color: #67e8f9;
    font-weight: 650;
  }

  :deep(.code-property) {
    color: #93c5fd;
  }

  :deep(.code-comment) {
    color: #64748b;
    font-style: italic;
  }

  :deep(.code-env) {
    color: #f0abfc;
  }

  :deep(.code-boolean) {
    color: #86efac;
    font-weight: 650;
  }

  :deep(.code-url) {
    color: #93c5fd;
  }

  :deep(.code-punctuation) {
    color: #94a3b8;
  }
}
</style>
