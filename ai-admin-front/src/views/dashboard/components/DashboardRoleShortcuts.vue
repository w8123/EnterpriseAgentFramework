<template>
  <section v-if="groups.length" class="role-shortcuts" aria-label="我的常用入口">
    <div v-for="group in groups" :key="group.key" class="role-shortcuts__group">
      <h2>{{ group.title }}</h2>
      <div class="role-shortcuts__items">
        <a
          v-for="shortcut in group.shortcuts"
          :key="shortcut.to"
          :href="shortcut.to"
          class="role-shortcuts__item"
          @click.prevent="emit('open', shortcut.to)"
        >
          <strong>{{ shortcut.label }}</strong>
          <span>{{ shortcut.detail }}</span>
        </a>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { roleShortcutGroups } from '../roleShortcuts'

const props = defineProps<{ permissions: readonly string[] }>()
const emit = defineEmits<{ open: [path: string] }>()

const groups = computed(() => roleShortcutGroups(props.permissions))
</script>

<style scoped lang="scss">
.role-shortcuts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}

.role-shortcuts__group {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-control);

  h2 {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-secondary);
  }
}

.role-shortcuts__items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}

.role-shortcuts__item {
  display: grid;
  gap: 2px;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  color: inherit;
  text-decoration: none;

  strong {
    font-size: 14px;
    color: var(--text-primary);
  }

  span {
    font-size: 12px;
    color: var(--text-secondary);
  }

  &:hover,
  &:focus-visible {
    background: var(--bg-card-hover);
    outline: none;
  }
}
</style>
