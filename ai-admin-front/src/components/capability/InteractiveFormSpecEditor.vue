<template>
  <div class="interactive-form-spec-editor">
    <el-form label-width="108px" :disabled="readonly">
      <section class="spec-card target-card">
        <div class="spec-card-head">
          <div>
            <span class="card-kicker">Step 1</span>
            <h4>选择最终执行的 Tool</h4>
            <p>表单收集完信息后，会把结果提交给这个 Tool 执行业务。</p>
          </div>
          <el-tag v-if="selectedTool" size="small" effect="plain">{{ selectedTool.parameters?.length || 0 }} 个入参</el-tag>
        </div>
        <el-form-item label="执行 Tool" required>
          <el-select
            :model-value="modelValue.targetTool"
            :disabled="readonly"
            filterable
            clearable
            placeholder="选择表单提交后调用的 Tool"
            style="width: 100%"
            @update:model-value="onTargetToolChange"
          >
            <el-option
              v-for="t in toolSelectOptions"
              :key="t.name"
              :label="`${t.name} — ${(t.description || '').slice(0, 56)}`"
              :value="t.name"
            />
          </el-select>
        </el-form-item>
        <div v-if="selectedTool" class="tool-preview">
          <b>{{ selectedTool.name }}</b>
          <span>{{ selectedTool.description || '暂无描述' }}</span>
        </div>
        <div v-else class="tool-preview muted">
          选择 Tool 后会自动生成表单字段，已有字段可选择保留或重置。
        </div>
      </section>

      <section class="spec-card">
        <div class="spec-card-head">
          <div>
            <span class="card-kicker">Step 2</span>
            <h4>设计用户要填写的字段</h4>
            <p>字段会用于槽位抽取、表单渲染，并在确认后组装成 Tool 入参。</p>
          </div>
          <div class="field-stats">
            <span>{{ modelValue.fields?.length || 0 }} 个字段</span>
            <span>{{ requiredFieldCount }} 个必填</span>
          </div>
        </div>

        <div v-if="!readonly" class="fields-toolbar">
          <span class="section-title">字段结构</span>
          <div class="fields-actions">
            <el-button :disabled="!selectedTool" size="small" @click="syncFieldsFromSelectedTool">
              同步 Tool 入参
            </el-button>
            <el-button type="primary" size="small" @click="addField">新增字段</el-button>
          </div>
        </div>
        <div v-else class="section-title">字段结构</div>

        <el-collapse v-model="openFieldNames" class="field-collapse">
          <el-collapse-item
            v-for="(field, index) in modelValue.fields"
            :key="fieldKeys[index]"
            :name="String(index)"
          >
            <template #title>
              <div class="collapse-header-row">
                <span class="collapse-title">
                  <template v-if="isBodyJsonField(field)">Body</template>
                  <template v-else>
                    {{ field.label || field.key || '(未命名)' }}
                    <span class="sub">{{ field.key || '-' }}</span>
                  </template>
                </span>
                <span class="field-badges">
                  <el-tag v-if="field.required" type="danger" size="small" effect="plain">必填</el-tag>
                  <el-tag size="small" effect="plain">{{ field.children ? '对象' : field.type }}</el-tag>
                </span>
                <div v-if="!readonly" class="collapse-header-actions" @click.stop>
                  <template v-if="isBodyJsonField(field)">
                    <el-button link type="primary" size="small" @click.stop="addBodyJsonChild(index)">
                      新增子字段
                    </el-button>
                  </template>
                  <template v-else>
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
                  </template>
                </div>
              </div>
            </template>
            <FieldSpecEditor
              :model-value="field"
              :tool-options="toolOptions"
              :readonly="readonly"
              :parent-handles-body-toolbar="isBodyJsonField(field)"
              @update:model-value="(f) => replaceField(index, f)"
            />
          </el-collapse-item>
        </el-collapse>
        <el-empty v-if="!modelValue.fields?.length" description="暂无字段" />
      </section>

      <section class="spec-card">
        <div class="spec-card-head">
          <div>
            <span class="card-kicker">Step 3</span>
            <h4>确认页与结果反馈</h4>
            <p>控制每轮追问数量、确认卡标题和执行成功后的提示文案。</p>
          </div>
        </div>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="每轮追问">
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
          <el-col :span="16">
            <el-form-item label="确认标题">
              <el-input
                :model-value="modelValue.confirmTitle || ''"
                :disabled="readonly"
                placeholder="确认弹窗标题"
                @update:model-value="(v: string) => emitTop({ confirmTitle: v })"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="成功文案">
          <el-input
            :model-value="modelValue.successTemplate || ''"
            :disabled="readonly"
            placeholder="成功文案，可用 {{fieldKey}} 占位"
            @update:model-value="(v: string) => emitTop({ successTemplate: v })"
          />
        </el-form-item>
      </section>
    </el-form>

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
import type { FieldSpec, InteractiveFormSpec } from '@/types/capability'
import { emptyFieldSource, mapToolToFields, normalizeInteractiveFormSpec } from '@/types/capability'
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
const selectedTool = computed(() =>
  toolSelectOptions.value.find((tool) => tool.name === props.modelValue.targetTool),
)
const requiredFieldCount = computed(() => countRequiredFields(props.modelValue.fields || []))

const fieldKeys = ref<string[]>([])
const openFieldNames = ref<string[]>([])
const jsonOpen = ref<string[]>([])
const jsonOpenRead = ref<string[]>([])

watch(
  () => props.modelValue.fields?.length ?? 0,
  (len) => {
    while (fieldKeys.value.length < len) {
      fieldKeys.value.push(`f-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)
    }
    if (fieldKeys.value.length > len) fieldKeys.value = fieldKeys.value.slice(0, len)
    openFieldNames.value = Array.from({ length: len }, (_, index) => String(index))
  },
  { immediate: true },
)

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

function countRequiredFields(fields: FieldSpec[]): number {
  return fields.reduce((sum, field) => {
    const childCount = field.children ? countRequiredFields(field.children) : 0
    return sum + (field.required ? 1 : 0) + childCount
  }, 0)
}

/** 扫描器生成的 JSON 请求体根节点，表单侧固定为「Body」展示且不参与排序/删除 */
function isBodyJsonField(field: FieldSpec): boolean {
  return (field.key || '').trim() === 'body_json'
}

/** 顶层 Body（body_json）分组：在折叠标题上「新增子字段」，与 FieldSpecEditor 内逻辑一致 */
function addBodyJsonChild(index: number) {
  if (props.readonly) return
  const f = props.modelValue.fields[index]
  if (!f || (f.key || '').trim() !== 'body_json') return
  const cur = [...(f.children ?? [])]
  cur.push({
    key: `sub_${cur.length + 1}`,
    label: '子字段',
    type: 'text',
    required: false,
    source: emptyFieldSource('NONE'),
  })
  replaceField(index, { ...f, children: cur })
}

async function syncFieldsFromSelectedTool() {
  if (props.readonly || !selectedTool.value) return
  const nextFields = mapToolToFields(selectedTool.value)
  const currentHasFields = (props.modelValue.fields || []).length > 0
  if (currentHasFields) {
    try {
      await ElMessageBox.confirm(
        '将根据当前 Tool 的入参重新生成字段结构，现有字段配置会被覆盖。',
        '同步 Tool 入参',
        {
          type: 'warning',
          confirmButtonText: '同步',
          cancelButtonText: '取消',
        },
      )
    } catch {
      return
    }
  }
  emit('update:modelValue', { ...props.modelValue, fields: nextFields })
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

.spec-card {
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.045);

  & + & {
    margin-top: 14px;
  }
}

.target-card {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.09), rgba(20, 184, 166, 0.06));
}

.spec-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 14px;

  h4 {
    margin: 2px 0 4px;
    color: var(--text-primary);
    font-size: 16px;
    line-height: 1.35;
  }

  p {
    margin: 0;
    color: #64748b;
    font-size: 13px;
    line-height: 1.55;
  }
}

.card-kicker {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
}

.tool-preview {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  margin-left: 108px;
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.36);
  color: #94a3b8;

  b {
    color: var(--text-primary);
  }

  &.muted {
    color: #cbd5e1;
  }
}

.field-stats {
  display: inline-flex;
  gap: 8px;
  flex-wrap: wrap;

  span {
    padding: 5px 9px;
    border-radius: 999px;
    background: rgba(99, 102, 241, 0.1);
    color: var(--el-color-primary);
    font-size: 12px;
    font-weight: 700;
  }
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

.fields-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.field-collapse {
  margin-bottom: 12px;
  border-top: 0;
  border-bottom: 0;
}

.field-collapse :deep(.el-collapse-item__header) {
  font-weight: 500;
  display: flex;
  align-items: center;
  min-height: 48px;
  padding: 0 10px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: var(--bg-secondary);
}

.field-collapse :deep(.el-collapse-item__title) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.collapse-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding-right: 4px;
  box-sizing: border-box;
}

.collapse-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collapse-title .sub {
  color: #909399;
  font-weight: normal;
}

.collapse-header-actions {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.field-badges {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.field-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
  background: transparent;
}

.field-collapse :deep(.el-collapse-item__content) {
  padding: 14px 8px 4px;
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

  .spec-card,
  .field-collapse :deep(.el-collapse-item__header) {
    border-color: #e5e7eb;
  }

  .spec-card {
    background: #f8fafc;
  }

  .target-card {
    background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(20, 184, 166, 0.06));
  }

  .tool-preview {
    background: #fff;
  }
}
</style>
