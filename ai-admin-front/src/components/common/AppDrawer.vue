<script setup lang="ts">
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  modelValue: boolean
  title: string
  description?: string
  size?: string | number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-drawer
    v-bind="$attrs"
    class="app-drawer glass-surface-overlay"
    :model-value="props.modelValue"
    :title="props.title"
    :size="props.size"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="$slots.header" #header="headerProps">
      <slot name="header" v-bind="headerProps" />
    </template>

    <p v-if="props.description" class="app-drawer__description">
      {{ props.description }}
    </p>
    <hr v-if="props.description" class="app-drawer__divider" />

    <slot />

    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.app-drawer__description {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.app-drawer__divider {
  margin: var(--section-gap) 0;
  border: 0;
  border-block-start: 1px solid var(--border-divider);
}
</style>
