<template>
  <div class="interactive-form-spec-editor">
    <el-form label-width="140px" :disabled="readonly">
      <el-form-item label="targetTool" required>
        <el-select
          :model-value="modelValue.targetTool"
          :disabled="readonly"
          filterable
          clearable
          placeholder="表单提交后调用的 Tool"
          style="width: 100%"
          @update:model-value="onTargetToolChange"
        >
          <el-option
            v-for="t in toolSelectOptions"
            :key="t.name"
            :label="`${t.name} — ${(t.description || '').slice(0, 40)}`"
            :value="t.name"
          />
        </el-select>
        <div class="hint">选择将接收用户填写结果并执行业务的 Tool（通常为写操作）。</div>
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="batchSize">
            <el-input-number
              :model-value="modelValue.batchSize ?? 2"
              :disabled="readonly"
              :min="1"
              :max="10"
              :step="1"
              controls-position="right"
              style="width: 100%"
              @update:model-value="(v: number | undefined) => emitTop({ batchSize: v ?? 2 })"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="confirmTitle">
            <el-input
              :model-value="modelValue.confirmTitle || ''"
              :disabled="readonly"
              placeholder="确认弹窗标题"
              @update:model-value="(v: string) => emitTop({ confirmTitle: v })"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="successTemplate">
        <el-input
          :model-value="modelValue.successTemplate || ''"
          :disabled="readonly"
          placeholder="成功文案，可用 {{fieldKey}} 占位"
          @update:model-value="(v: string) => emitTop({ successTemplate: v })"
        />
      </el-form-item>
    </el-form>

    <div v-if="!readonly" class="fields-toolbar">
      <span class="section-title">表单字段</span>
      <el-button type="primary" size="small" @click="addField">新增字段</el-button>
    </div>
    <div v-else class="section-title">表单字段</div>

    <el-collapse v-model="openFieldNames" class="field-collapse">
      <el-collapse-item
        v-for="(field, index) in modelValue.fields"
        :key="fieldKeys[index]"
        :name="String(index)"
      >
        <template #title>
          <span class="collapse-title">{{ field.key || '(未命名)' }} <span class="sub">({{ field.label || '-' }})</span></span>
        </template>
        <div v-if="!readonly" class="field-actions">
          <el-button link type="primary" size="small" :disabled="index === 0" @click.stop="moveField(index, -1)">
            上移
          </el-button>
          <el-button
            link
            type="primary"
            size="small"
            :disabled="index >= modelValue.fields.length - 1"
            @click.stop="moveField(index, 1)"
          >
            下移
          </el-button>
          <el-button link type="danger" size="small" @click.stop="removeField(index)">删除</el-button>
        </div>
        <FieldSpecEditor
          :model-value="field"
          :tool-options="toolOptions"
          :readonly="readonly"
          @update:model-value="(f) => replaceField(index, f)"
        />
      </el-collapse-item>
    </el-collapse>
    <el-empty v-if="!modelValue.fields?.length" description="暂无字段" />

    <el-collapse v-if="!readonly" v-model="jsonOpen" class="json-collapse">
      <el-collapse-item title="高级 / 原始 JSON" name="json">
        <el-input
          v-model="jsonText"
          type="textarea"
          :rows="14"
          class="mono"
          placeholder="可直接粘贴 InteractiveFormSpec JSON，失焦后解析并同步到上方表单"
          @blur="onJsonBlur"
        />
        <el-alert v-if="jsonError" type="error" :closable="false" show-icon style="margin-top: 8px">
          {{ jsonError }}
        </el-alert>
      </el-collapse-item>
    </el-collapse>
    <el-collapse v-else v-model="jsonOpenRead" class="json-collapse">
      <el-collapse-item title="原始 JSON" name="jsonr">
        <el-input :model-value="jsonReadonlyText" type="textarea" :rows="10" readonly class="mono" />
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FieldSpec, InteractiveFormSpec } from '@/types/skill'
import { emptyFieldSource, mapToolToFields, normalizeInteractiveFormSpec } from '@/types/skill'
import type { ToolInfo } from '@/types/tool'
import FieldSpecEditor from './FieldSpecEditor.vue'

const props = withDefaults(
  defineProps<{
    modelValue: InteractiveFormSpec
    toolOptions: ToolInfo[]
    readonly?: boolean
  }>(),
  { readonly: false },
)

const emit = defineEmits<{
  'update:modelValue': [v: InteractiveFormSpec]
}>()

const toolSelectOptions = computed(() =>
  props.toolOptions.filter((t) => (t as ToolInfo & { skillKind?: string | null }).skillKind == null),
)

const fieldKeys = ref<string[]>([])

watch(
  () => props.modelValue.fields?.length ?? 0,
  (len) => {
    while (fieldKeys.value.length < len) {
      fieldKeys.value.push(`f-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)
    }
    if (fieldKeys.value.length > len) fieldKeys.value = fieldKeys.value.slice(0, len)
  },
  { immediate: true },
)

const openFieldNames = ref<string[]>([])
const jsonOpen = ref<string[]>([])
const jsonOpenRead = ref<string[]>([])

const jsonText = ref('')
const jsonError = ref('')

watch(
  () => props.modelValue,
  (v) => {
    if (props.readonly) return
    try {
      jsonText.value = JSON.stringify(v, null, 2)
      jsonError.value = ''
    } catch {
      jsonText.value = ''
    }
  },
  { deep: true, immediate: true },
)

const jsonReadonlyText = computed(() => {
  try {
    return JSON.stringify(props.modelValue, null, 2)
  } catch {
    return ''
  }
})

function emitTop(p: Partial<InteractiveFormSpec>) {
  if (props.readonly) return
  emit('update:modelValue', { ...props.modelValue, ...p })
}

async function onTargetToolChange(name: string) {
  if (props.readonly) return
  const v = name || ''
  if (!v) {
    emitTop({ targetTool: '' })
    return
  }
  const tool = props.toolOptions.find((t) => t.name === v)
  const hasFields = (props.modelValue.fields || []).length > 0
  if (hasFields) {
    try {
      await ElMessageBox.confirm(
        '是否根据所选 Tool 的参数重置下方字段列表？现有字段将被覆盖。',
        '同步字段',
        {
          type: 'warning',
          confirmButtonText: '是，重置',
          cancelButtonText: '否，保留',
        },
      )
      const newFields = tool ? mapToolToFields(tool) : []
      emit('update:modelValue', { ...props.modelValue, targetTool: v, fields: newFields })
    } catch {
      emit('update:modelValue', { ...props.modelValue, targetTool: v })
    }
    return
  }
  if (tool) {
    emit('update:modelValue', { ...props.modelValue, targetTool: v, fields: mapToolToFields(tool) })
    return
  }
  emitTop({ targetTool: v })
}

function replaceField(index: number, f: FieldSpec) {
  if (props.readonly) return
  const fields = [...props.modelValue.fields]
  fields[index] = f
  emit('update:modelValue', { ...props.modelValue, fields })
}

function addField() {
  if (props.readonly) return
  const nf: FieldSpec = {
    key: `field_${props.modelValue.fields.length + 1}`,
    label: '新字段',
    type: 'text',
    required: false,
    source: emptyFieldSource('NONE'),
  }
  fieldKeys.value.push(`f-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)
  emit('update:modelValue', {
    ...props.modelValue,
    fields: [...props.modelValue.fields, nf],
  })
}

function removeField(index: number) {
  if (props.readonly) return
  const fields = props.modelValue.fields.filter((_, i) => i !== index)
  fieldKeys.value.splice(index, 1)
  emit('update:modelValue', { ...props.modelValue, fields })
}

function moveField(index: number, delta: number) {
  if (props.readonly) return
  const j = index + delta
  if (j < 0 || j >= props.modelValue.fields.length) return
  const fields = [...props.modelValue.fields]
  ;[fields[index], fields[j]] = [fields[j], fields[index]]
  const keys = [...fieldKeys.value]
  ;[keys[index], keys[j]] = [keys[j], keys[index]]
  fieldKeys.value = keys
  emit('update:modelValue', { ...props.modelValue, fields })
}

function onJsonBlur() {
  if (props.readonly) return
  const raw = jsonText.value.trim()
  if (!raw) {
    jsonError.value = ''
    return
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    const norm = normalizeInteractiveFormSpec(parsed)
    jsonError.value = ''
    emit('update:modelValue', norm)
  } catch (e) {
    jsonError.value = (e as Error).message || 'JSON 解析失败'
  }
}
</script>

<style scoped lang="scss">
.interactive-form-spec-editor {
  width: 100%;
}

.hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

.fields-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0 8px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.field-collapse {
  margin-bottom: 12px;
}

.field-collapse :deep(.el-collapse-item__header) {
  font-weight: 500;
}

.collapse-title {
  font-size: 13px;
}

.collapse-title .sub {
  color: #909399;
  font-weight: normal;
}

.field-actions {
  margin-bottom: 8px;
}

.json-collapse {
  margin-top: 16px;
}

.mono :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .hint,
  .collapse-title .sub {
    color: #94a3b8;
  }

  .section-title {
    color: #1e293b;
  }
}
</style>
