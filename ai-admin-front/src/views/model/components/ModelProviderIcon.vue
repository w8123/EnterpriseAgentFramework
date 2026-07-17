<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { resolveProviderIconKey } from '../modelCenterUi'

const props = withDefaults(
  defineProps<{
    provider?: string | null
    iconKey?: string | null
    size?: number
    alt?: string
  }>(),
  {
    size: 36,
  },
)

const iconModules = import.meta.glob('@/assets/model-providers/*.{svg,png,webp}', {
  eager: true,
  import: 'default',
}) as Record<string, string>

const failed = ref(false)

const resolvedKey = computed(() => resolveProviderIconKey(props.iconKey || props.provider))

const iconSrc = computed(() => {
  const key = resolvedKey.value
  if (!key || failed.value) return ''
  const preferred = [`/${key}.png`, `/${key}.webp`, `/${key}.svg`]
  for (const suffix of preferred) {
    const match = Object.entries(iconModules).find(([path]) => path.endsWith(suffix))
    if (match?.[1]) return match[1]
  }
  return ''
})

const fallbackMark = computed(() => {
  const raw = String(props.provider || props.iconKey || '?').trim()
  if (!raw) return '?'
  const parts = raw.replace(/[-_]+/g, ' ').split(/\s+/).filter(Boolean)
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase()
  }
  return raw.slice(0, 2).toUpperCase()
})

watch([() => props.provider, () => props.iconKey], () => {
  failed.value = false
})

function handleError() {
  failed.value = true
}
</script>

<template>
  <span
    class="model-provider-icon"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.max(11, Math.round(size * 0.34))}px` }"
    :aria-label="alt || provider || iconKey || 'provider'"
  >
    <img
      v-if="iconSrc"
      :src="iconSrc"
      :alt="alt || provider || ''"
      class="model-provider-icon__img"
      @error="handleError"
    />
    <span v-else class="model-provider-icon__fallback">{{ fallbackMark }}</span>
  </span>
</template>

<style scoped lang="scss">
.model-provider-icon {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--border-glass);
  border-radius: 12px;
  background: var(--surface-glass-control);
  color: var(--text-primary);
  box-shadow: var(--shadow-panel);
}

.model-provider-icon__img {
  display: block;
  width: 72%;
  height: 72%;
  object-fit: contain;
}

.model-provider-icon__fallback {
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text-secondary);
}
</style>
