<script setup lang="ts">
import { computed } from 'vue'
import type { A2aPublicationRevision, A2aRemoteAgentRevision } from '@/types/a2aHub'

const props = defineProps<{ revision?: A2aPublicationRevision | A2aRemoteAgentRevision | null }>()

const protocolVersion = computed(() => {
  const revision = props.revision
  if (!revision) return '-'
  return 'protocolVersion' in revision
    ? revision.protocolVersion
    : revision.supportedInterfaces[0]?.protocolVersion ?? '-'
})

const formatted = computed(() => {
  const raw = props.revision?.agentCardJson
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})
</script>

<template>
  <div v-if="props.revision" class="card-preview">
    <div class="card-preview__meta">
      <span>HTTP+JSON · {{ protocolVersion }}</span>
      <span>SHA-256 {{ props.revision.agentCardSha256.slice(0, 16) }}…</span>
    </div>
    <pre>{{ formatted }}</pre>
  </div>
  <el-empty v-else :image-size="48" description="选择一个修订查看 Agent Card" />
</template>

<style scoped lang="scss">
.card-preview {
  min-width: 0;
}

.card-preview__meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--text-muted);
  font-size: 12px;
}

pre {
  max-height: 460px;
  margin: 0;
  overflow: auto;
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: var(--surface-glass-control);
  font: 12px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
