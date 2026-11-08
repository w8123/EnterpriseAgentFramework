<template>
  <AppDialog
    :model-value="modelValue"
    title="快捷键"
    width="420px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <table class="shortcut-table">
      <tbody>
        <tr v-for="item in SHORTCUTS" :key="item.keys">
          <td><kbd>{{ item.keys }}</kbd></td>
          <td>{{ item.label }}</td>
        </tr>
      </tbody>
    </table>
    <p class="shortcut-hint">Mac 上用 ⌘ 代替 Ctrl。在输入框里打字时快捷键不生效。</p>
  </AppDialog>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const SHORTCUTS = [
  { keys: 'Ctrl + S', label: '保存' },
  { keys: 'Ctrl + Z', label: '撤销' },
  { keys: 'Ctrl + Shift + Z / Ctrl + Y', label: '重做' },
  { keys: 'Ctrl + C / Ctrl + V', label: '复制 / 粘贴选中节点' },
  { keys: 'Ctrl + F', label: '搜索节点' },
  { keys: 'Ctrl + O', label: '自动整理画布' },
  { keys: 'Ctrl + 1', label: '聚焦画布' },
  { keys: 'Delete / Backspace', label: '删除选中的节点或连线' },
]
</script>

<style scoped>
.shortcut-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.shortcut-table td { padding: 6px 4px; border-bottom: 1px solid var(--border-subtle); }
.shortcut-table kbd { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; padding: 2px 6px; border-radius: 4px; border: 1px solid var(--border-subtle); background: var(--surface-solid-control); }
.shortcut-hint { margin: 12px 0 0; color: var(--text-muted); font-size: 12px; }
</style>
