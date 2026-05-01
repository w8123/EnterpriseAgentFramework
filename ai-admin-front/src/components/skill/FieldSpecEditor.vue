<template>
  <div class="field-spec-editor" :class="{ nested: depth > 0 }">
    <el-row :gutter="16">
      <el-col :span="12">
        <el-form-item label="key" required>
          <el-input
            :model-value="modelValue.key"
            :disabled="readonly"
            placeholder="字段名，如 teamName 或 body"
            @update:model-value="(v: string) => emitPatch({ key: v })"
          />
        </el-form-item>
      </el-col>
      <el-col :span="12">
        <el-form-item label="label" required>
          <el-input
            :model-value="modelValue.label"
            :disabled="readonly"
            placeholder="展示标签"
            @update:model-value="(v: string) => emitPatch({ label: v })"
          />
        </el-form-item>
      </el-col>
      <template v-if="!isGroupNode">
        <el-col :span="12">
          <el-form-item label="type">
            <el-select
              :model-value="modelValue.type"
              :disabled="readonly"
              style="width: 100%"
              @update:model-value="(v: string) => emitPatch({ type: v })"
            >
              <el-option label="text" value="text" />
              <el-option label="number" value="number" />
              <el-option label="date" value="date" />
              <el-option label="select" value="select" />
              <el-option label="multi_select" value="multi_select" />
              <el-option label="radio" value="radio" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="必填">
            <el-switch
              :model-value="modelValue.required"
              :disabled="readonly"
              @update:model-value="(v: boolean) => emitPatch({ required: v })"
            />
          </el-form-item>
        </el-col>
      </template>
    </el-row>

    <template v-if="!isGroupNode">
      <el-collapse v-model="advOpen" class="adv-collapse">
        <el-collapse-item title="高级选项" name="adv">
          <el-form-item label="validateRegex">
            <el-input
              :model-value="modelValue.validateRegex || ''"
              :disabled="readonly"
              placeholder="可选正则"
              @update:model-value="(v: string) => emitPatch({ validateRegex: v || undefined })"
            />
          </el-form-item>
          <el-form-item label="llmExtractHint">
            <el-input
              :model-value="modelValue.llmExtractHint || ''"
              :disabled="readonly"
              type="textarea"
              :rows="2"
              placeholder="LLM 抽取提示"
              @update:model-value="(v: string) => emitPatch({ llmExtractHint: v || undefined })"
            />
          </el-form-item>
          <el-form-item label="defaultValue">
            <el-input
              :model-value="defaultValueStr"
              :disabled="readonly"
              placeholder="字符串或留空"
              @update:model-value="onDefaultValueInput"
            />
          </el-form-item>
        </el-collapse-item>
      </el-collapse>

      <el-divider content-position="left">数据来源 source</el-divider>
      <FieldSourceEditor
        :model-value="modelValue.source"
        :tool-options="toolOptions"
        :readonly="readonly"
        @update:model-value="(s) => emitPatch({ source: s })"
      />
    </template>
    <el-alert
      v-else
      type="info"
      :closable="false"
      show-icon
      class="group-alert"
      title="分组节点"
      description="不参与直接填值；提交 targetTool 时按路径把子字段组装为嵌套对象。槽位与校验仅针对叶子字段。"
    />

    <div v-if="depth < maxChildDepth" class="children-section">
      <el-divider content-position="left">子字段 (children)</el-divider>
      <div v-if="!readonly" class="children-toolbar">
        <span class="subtle">用于嵌套参数（如 body 下的 DTO 字段）</span>
        <el-button type="primary" link size="small" @click="addChild">新增子字段</el-button>
      </div>
      <el-collapse v-if="(modelValue.children?.length ?? 0) > 0" v-model="childOpenNames" class="child-collapse">
        <el-collapse-item
          v-for="(ch, ci) in modelValue.children"
          :key="childKeys[ci]"
          :name="String(ci)"
        >
          <template #title>
            <span>{{ ch.key || '(未命名)' }} <span class="subtle">({{ ch.label || '-' }})</span></span>
          </template>
          <div v-if="!readonly" class="field-actions">
            <el-button link type="primary" size="small" :disabled="ci === 0" @click.stop="moveChild(ci, -1)">
              上移
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              :disabled="ci >= (modelValue.children?.length ?? 0) - 1"
              @click.stop="moveChild(ci, 1)"
            >
              下移
            </el-button>
            <el-button link type="danger" size="small" @click.stop="removeChild(ci)">删除</el-button>
          </div>
          <FieldSpecEditor
            :model-value="ch"
            :tool-options="toolOptions"
            :readonly="readonly"
            :depth="depth + 1"
            @update:model-value="(nv) => replaceChild(ci, nv)"
          />
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FieldSpec } from '@/types/skill'
import { emptyFieldSource } from '@/types/skill'
import type { ToolInfo } from '@/types/tool'
import FieldSourceEditor from './FieldSourceEditor.vue'

defineOptions({ name: 'FieldSpecEditor' })

const maxChildDepth = 12

const props = withDefaults(
  defineProps<{
    modelValue: FieldSpec
    toolOptions: ToolInfo[]
    readonly?: boolean
    /** 嵌套深度，根为 0 */
    depth?: number
  }>(),
  { readonly: false, depth: 0 },
)

const emit = defineEmits<{
  'update:modelValue': [v: FieldSpec]
}>()

const advOpen = ref<string[]>([])
const childOpenNames = ref<string[]>([])
const childKeys = ref<string[]>([])

/** children!=null 即为分组节点；[] 表示空嵌套对象（如无字段的 body_json），仍为分组而非叶子 */
const isGroupNode = computed(() => props.modelValue.children != null)

watch(
  () => props.modelValue.children?.length ?? 0,
  (len) => {
    while (childKeys.value.length < len) {
      childKeys.value.push(`c-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)
    }
    if (childKeys.value.length > len) childKeys.value = childKeys.value.slice(0, len)
  },
  { immediate: true },
)

function emitPatch(p: Partial<FieldSpec>) {
  emit('update:modelValue', { ...props.modelValue, ...p })
}

function addChild() {
  if (props.readonly) return
  const cur = [...(props.modelValue.children || [])]
  cur.push({
    key: `sub_${cur.length + 1}`,
    label: '子字段',
    type: 'text',
    required: false,
    source: emptyFieldSource('NONE'),
  })
  childKeys.value.push(`c-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)
  emitPatch({ children: cur })
}

function replaceChild(index: number, nv: FieldSpec) {
  if (props.readonly) return
  const cur = [...(props.modelValue.children || [])]
  cur[index] = nv
  emitPatch({ children: cur })
}

function removeChild(index: number) {
  if (props.readonly) return
  const cur = (props.modelValue.children || []).filter((_, i) => i !== index)
  childKeys.value.splice(index, 1)
  emitPatch({ children: cur.length ? cur : [] })
}

function moveChild(index: number, delta: number) {
  if (props.readonly) return
  const j = index + delta
  const arr = [...(props.modelValue.children || [])]
  if (j < 0 || j >= arr.length) return
  ;[arr[index], arr[j]] = [arr[j], arr[index]]
  const keys = [...childKeys.value]
  ;[keys[index], keys[j]] = [keys[j], keys[index]]
  childKeys.value = keys
  emitPatch({ children: arr })
}

const defaultValueStr = computed(() => {
  const v = props.modelValue.defaultValue
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
})

function onDefaultValueInput(s: string) {
  const t = s.trim()
  if (!t) {
    emitPatch({ defaultValue: undefined })
    return
  }
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try {
      emitPatch({ defaultValue: JSON.parse(t) as unknown })
    } catch {
      emitPatch({ defaultValue: t })
    }
    return
  }
  if (t === 'true') {
    emitPatch({ defaultValue: true })
    return
  }
  if (t === 'false') {
    emitPatch({ defaultValue: false })
    return
  }
  const n = Number(t)
  if (!Number.isNaN(n) && String(n) === t) {
    emitPatch({ defaultValue: n })
    return
  }
  emitPatch({ defaultValue: t })
}
</script>

<style scoped lang="scss">
.field-spec-editor {
  width: 100%;
}

.field-spec-editor.nested {
  margin-left: 8px;
  padding-left: 12px;
  border-left: 2px solid #ebeef5;
}

.group-alert {
  margin-bottom: 8px;
}

.children-section {
  margin-top: 4px;
}

.children-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.subtle {
  font-size: 12px;
  color: #909399;
}

.field-actions {
  margin-bottom: 8px;
}

.child-collapse {
  margin-bottom: 8px;
}

.adv-collapse {
  margin-top: 4px;
  border: none;
}

.adv-collapse :deep(.el-collapse-item__header) {
  height: 36px;
  line-height: 36px;
  font-size: 13px;
  color: #606266;
  border: none;
  background: transparent;
}

.adv-collapse :deep(.el-collapse-item__wrap) {
  border: none;
}

.adv-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 8px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .subtle {
    color: #94a3b8;
  }

  .adv-collapse :deep(.el-collapse-item__header) {
    color: #475569;
  }
}
</style>
