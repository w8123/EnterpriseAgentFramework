<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import type { ModelType } from '@/types/model'
import { modelTypeDisplayLabel } from '@/utils/modelSelection'

const props = withDefaults(
  defineProps<{
    modelType: ModelType
    optionCount: number
    loading?: boolean
    loadError?: boolean
  }>(),
  {
    loading: false,
    loadError: false,
  },
)

const emit = defineEmits<{
  retry: []
}>()

const router = useRouter()
const modelTypeLabel = computed(() => modelTypeDisplayLabel(props.modelType))
const modelCenterHref = computed(() => router.resolve({
  name: 'ModelInstances',
  query: {
    create: '1',
    modelType: props.modelType,
  },
}).href)
</script>

<template>
  <div class="model-select-empty-state" role="status">
    <template v-if="loading">
      <span class="model-select-empty-state__message">正在加载模型实例…</span>
    </template>

    <template v-else-if="loadError">
      <span class="model-select-empty-state__message">模型列表加载失败</span>
      <button
        class="model-select-empty-state__action"
        type="button"
        @mousedown.stop.prevent
        @click.stop="emit('retry')"
      >
        <el-icon><Refresh /></el-icon>
        重新加载
      </button>
    </template>

    <template v-else-if="optionCount === 0">
      <span class="model-select-empty-state__message">
        暂无可用的 {{ modelTypeLabel }} 模型实例
      </span>
      <span class="model-select-empty-state__actions">
        <a
          class="model-select-empty-state__action"
          :href="modelCenterHref"
          :aria-label="`前往模型中心接入 ${modelTypeLabel} 模型`"
          target="_blank"
          rel="noopener noreferrer"
          @mousedown.stop
          @click.stop
        >
          前往模型中心接入
          <el-icon><ArrowRight /></el-icon>
        </a>
        <button
          class="model-select-empty-state__refresh"
          type="button"
          @mousedown.stop.prevent
          @click.stop="emit('retry')"
        >
          刷新列表
        </button>
      </span>
    </template>

    <template v-else>
      <span class="model-select-empty-state__message">没有匹配的模型实例</span>
    </template>
  </div>
</template>

<style scoped lang="scss">
.model-select-empty-state {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 7px;
  padding: 10px 14px;
  box-sizing: border-box;
  text-align: center;
}

.model-select-empty-state__message {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.model-select-empty-state__actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.model-select-empty-state__action,
.model-select-empty-state__refresh {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  line-height: 20px;
  text-decoration: none;
}

.model-select-empty-state__action:hover,
.model-select-empty-state__refresh:hover {
  color: var(--el-color-primary-light-3);
}

.model-select-empty-state__refresh {
  color: var(--el-text-color-secondary);
}

.model-select-empty-state__action .el-icon {
  font-size: 11px;
}
</style>
