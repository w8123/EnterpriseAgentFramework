<template>
  <AppDrawer
    v-model="open"
    title="Workflow 源码"
    size="50%"
    destroy-on-close
    @closed="$emit('reset')"
  >
    <LiquidGlassNotice
      type="info"
      class="json-source-alert"
      title="GraphSpec 是运行语义；Canvas 只保存布局。修改不会立即影响画布，点击“校验并应用”后才会进入当前工作副本。"
      source="backdrop"
    />
    <LiquidGlassTabs v-model="activeTab">
      <el-tab-pane label="GraphSpec" name="graph">
        <el-input
          v-model="graphSpecSource"
          type="textarea"
          :autosize="{ minRows: 24 }"
          :disabled="readOnly || applying"
          spellcheck="false"
          class="json-editor"
        />
      </el-tab-pane>
      <el-tab-pane label="Canvas" name="canvas">
        <el-input
          v-model="canvasSource"
          type="textarea"
          :autosize="{ minRows: 24 }"
          :disabled="readOnly || applying"
          spellcheck="false"
          class="json-editor"
        />
      </el-tab-pane>
    </LiquidGlassTabs>
    <template #footer>
      <div class="json-source-footer">
        <span>{{ changed ? '源码有尚未应用的修改' : '源码与当前画布一致' }}</span>
        <div>
          <LiquidGlassButton @click="$emit('reset')">重置</LiquidGlassButton>
          <LiquidGlassButton
            type="primary"
            :loading="applying"
            :disabled="readOnly || !changed"
            @click="$emit('apply')"
          >
            校验并应用
          </LiquidGlassButton>
        </div>
      </div>
    </template>
  </AppDrawer>
</template>

<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import { LiquidGlassButton, LiquidGlassNotice, LiquidGlassTabs } from '@/components/common/liquid-glass'
defineProps<{
  readOnly: boolean
  applying: boolean
  changed: boolean
}>()

defineEmits<{
  (event: 'reset'): void
  (event: 'apply'): void
}>()

const open = defineModel<boolean>('open', { required: true })
const activeTab = defineModel<string>('activeTab', { required: true })
const graphSpecSource = defineModel<string>('graphSpecSource', { required: true })
const canvasSource = defineModel<string>('canvasSource', { required: true })
</script>

<style scoped lang="scss">
.json-editor :deep(textarea) {
  min-height: 620px !important;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.55;
}

.json-source-alert {
  margin-bottom: 12px;
}

.json-source-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.json-source-footer > div {
  display: flex;
  gap: 8px;
}
</style>
