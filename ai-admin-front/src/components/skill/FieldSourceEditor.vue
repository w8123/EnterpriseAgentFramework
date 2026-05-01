<template>
  <div class="field-source-editor">
    <el-form-item label="数据来源">
      <el-radio-group
        :model-value="modelValue.kind"
        :disabled="readonly"
        @update:model-value="onKindChange"
      >
        <el-radio-button value="NONE">NONE</el-radio-button>
        <el-radio-button value="STATIC">STATIC</el-radio-button>
        <el-radio-button value="DICT">DICT</el-radio-button>
        <el-radio-button value="TOOL_CALL">TOOL_CALL</el-radio-button>
      </el-radio-group>
    </el-form-item>

    <template v-if="modelValue.kind === 'STATIC'">
      <div class="static-toolbar">
        <span class="hint">静态选项（value / label）</span>
        <el-button v-if="!readonly" type="primary" link size="small" @click="addStaticRow">新增行</el-button>
      </div>
      <el-table :data="staticOptions" border size="small" class="static-table">
        <el-table-column label="value" min-width="120">
          <template #default="{ row, $index }">
            <el-input v-model="row.value" :disabled="readonly" @change="emitStatic" />
          </template>
        </el-table-column>
        <el-table-column label="label" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.label" :disabled="readonly" @change="emitStatic" />
          </template>
        </el-table-column>
        <el-table-column v-if="!readonly" label="" width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeStaticRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else-if="modelValue.kind === 'DICT'">
      <el-form-item label="dictCode">
        <el-autocomplete
          :model-value="modelValue.dictCode || ''"
          :disabled="readonly"
          :fetch-suggestions="queryDictHints"
          clearable
          placeholder="如 SHIFT_TYPE"
          style="width: 100%"
          @update:model-value="patchDictCode"
        />
      </el-form-item>
    </template>

    <template v-else-if="modelValue.kind === 'TOOL_CALL'">
      <el-form-item label="toolName">
        <el-select
          :model-value="modelValue.toolName || ''"
          :disabled="readonly"
          filterable
          clearable
          placeholder="选择 Tool"
          style="width: 100%"
          @update:model-value="(v: string) => emitPatch({ toolName: v || '' })"
        >
          <el-option
            v-for="t in toolSelectOptions"
            :key="t.name"
            :label="`${t.name} — ${(t.description || '').slice(0, 48)}`"
            :value="t.name"
          />
        </el-select>
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="valueField">
            <el-input
              :model-value="modelValue.valueField || 'id'"
              :disabled="readonly"
              placeholder="id"
              @update:model-value="(v: string) => emitPatch({ valueField: v })"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="labelField">
            <el-input
              :model-value="modelValue.labelField || 'name'"
              :disabled="readonly"
              placeholder="name"
              @update:model-value="(v: string) => emitPatch({ labelField: v })"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="toolArgs (JSON)">
        <el-input
          v-model="toolArgsText"
          type="textarea"
          :rows="4"
          :disabled="readonly"
          placeholder="{}"
          class="mono"
          @blur="onToolArgsBlur"
        />
        <el-alert v-if="toolArgsError" type="error" :closable="false" show-icon class="mt8">
          {{ toolArgsError }}
        </el-alert>
      </el-form-item>
    </template>

    <template v-else>
      <p class="muted">无外部数据源（自由输入 / 日期等）。</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FieldOptionSpec, FieldSourceKind, FieldSourceSpec } from '@/types/skill'
import { emptyFieldSource } from '@/types/skill'
import type { ToolInfo } from '@/types/tool'

const props = withDefaults(
  defineProps<{
    modelValue: FieldSourceSpec
    toolOptions: ToolInfo[]
    readonly?: boolean
  }>(),
  { readonly: false },
)

const emit = defineEmits<{
  'update:modelValue': [v: FieldSourceSpec]
}>()

/** 仅 Tool：若未来 ToolInfo 带 skillKind，则排除 Skill 形态条目 */
const toolSelectOptions = computed(() =>
  props.toolOptions.filter((t) => (t as ToolInfo & { skillKind?: string | null }).skillKind == null),
)

const DICT_HINTS = ['SHIFT_TYPE', 'TEAM_STATUS', 'USER_ROLE']

function queryDictHints(queryString: string, cb: (arg: { value: string }[]) => void) {
  const q = (queryString || '').trim().toUpperCase()
  const list = DICT_HINTS.filter((h) => h.includes(q) || !q).map((value) => ({ value }))
  cb(list.length ? list : DICT_HINTS.map((value) => ({ value })))
}

function onKindChange(kind: FieldSourceKind | string) {
  emit('update:modelValue', emptyFieldSource(kind as FieldSourceKind))
}

function emitPatch(p: Partial<FieldSourceSpec>) {
  emit('update:modelValue', { ...props.modelValue, ...p } as FieldSourceSpec)
}

const staticOptions = ref<FieldOptionSpec[]>([])

watch(
  () => props.modelValue,
  (v) => {
    if (v.kind === 'STATIC') {
      staticOptions.value = [...(v.options || [])]
      if (!staticOptions.value.length && !props.readonly) {
        staticOptions.value = [{ value: '', label: '' }]
      }
    }
  },
  { immediate: true, deep: true },
)

function emitStatic() {
  if (props.modelValue.kind !== 'STATIC') return
  emit('update:modelValue', {
    kind: 'STATIC',
    options: staticOptions.value.map((o) => ({ value: o.value, label: o.label })),
  })
}

function addStaticRow() {
  staticOptions.value.push({ value: '', label: '' })
  emitStatic()
}

function removeStaticRow(i: number) {
  staticOptions.value.splice(i, 1)
  if (!staticOptions.value.length) staticOptions.value = [{ value: '', label: '' }]
  emitStatic()
}

function patchDictCode(v: string) {
  emit('update:modelValue', { kind: 'DICT', dictCode: v })
}

const toolArgsText = ref('{}')
const toolArgsError = ref('')

watch(
  () => props.modelValue,
  (v) => {
    if (v.kind === 'TOOL_CALL') {
      try {
        toolArgsText.value = JSON.stringify(v.toolArgs ?? {}, null, 2)
        toolArgsError.value = ''
      } catch {
        toolArgsText.value = '{}'
      }
    }
  },
  { immediate: true, deep: true },
)

function onToolArgsBlur() {
  if (props.modelValue.kind !== 'TOOL_CALL') return
  const raw = toolArgsText.value.trim() || '{}'
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      toolArgsError.value = 'toolArgs 必须是 JSON 对象'
      return
    }
    toolArgsError.value = ''
    emitPatch({ toolArgs: parsed })
  } catch {
    toolArgsError.value = 'toolArgs 不是合法 JSON'
  }
}
</script>

<style scoped lang="scss">
.field-source-editor {
  width: 100%;
}

.static-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.static-table {
  width: 100%;
}

.muted {
  font-size: 13px;
  color: #909399;
  margin: 0;
}

.mono :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}

.mt8 {
  margin-top: 8px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .hint,
  .muted {
    color: #94a3b8;
  }
}
</style>
