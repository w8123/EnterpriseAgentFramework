<template>
  <div class="node-specific-panel">
    <ConfigSection title="代码" description="在沙箱里运行 JavaScript，或用表达式直接拼出输出字段">
      <el-form-item label="方式">
        <el-radio-group v-model="language">
          <el-radio value="javascript">JavaScript</el-radio>
          <el-radio value="expression">表达式</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="language === 'javascript'">
        <el-form-item label="输入变量">
          <el-input
            v-model="inputsText"
            type="textarea"
            :rows="3"
            placeholder="每行一个：名称 = 变量路径或模板&#10;text = lastOutput&#10;items = var.orders"
            @change="commitInputs"
          />
          <div class="hint">代码里用 inputs.名称 读取。</div>
        </el-form-item>
        <el-form-item label="代码">
          <el-input
            v-model="codeText"
            type="textarea"
            class="code-editor"
            :rows="12"
            spellcheck="false"
            @change="commitCode"
          />
          <div class="hint">
            需要定义 <code>function main(inputs) {...}</code> 并返回一个对象。不能访问网络、文件和系统，默认最多运行 3 秒。
          </div>
        </el-form-item>
        <el-form-item label="输出字段">
          <el-input
            v-model="outputsText"
            type="textarea"
            :rows="3"
            placeholder="每行一个：字段名 = 类型&#10;result = string&#10;total = number"
            @change="commitOutputs"
          />
          <div class="hint">返回的对象必须包含这些字段；后续节点用 nodeOutput.节点ID.字段名 引用。</div>
        </el-form-item>
        <el-form-item label="运行时限">
          <el-input-number v-model="timeoutSeconds" :min="0.1" :max="10" :step="0.5" :precision="1" />
          <span class="hint-inline">秒</span>
        </el-form-item>
      </template>
      <el-form-item v-else label="输出字段">
        <el-input
          v-model="outputsText"
          type="textarea"
          :rows="6"
          placeholder="每行一个：字段名 = 变量路径或模板&#10;result = lastOutput&#10;customerId = params.customerId"
          @change="commitOutputs"
        />
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, ref, watch } from 'vue'
import type { CanvasNodeData, CodeNodeConfig } from '@/types/studio'
import { formatMap, parseMap } from './panelUtils'
import { DEFAULT_CODE_TEMPLATE } from '@/utils/studio/defaults'

const props = defineProps<{
  data: CanvasNodeData
}>()

const FALLBACK: CodeNodeConfig = { language: 'expression', code: '', outputs: { result: 'lastOutput' } }

/** Read-only view; opening the panel never writes into the node. */
const config = computed<CodeNodeConfig>(() => props.data.codeConfig || FALLBACK)

function write(patch: Partial<CodeNodeConfig>) {
  props.data.codeConfig = { ...config.value, ...patch }
}

const language = computed({
  get: () => config.value.language === 'javascript' ? 'javascript' : 'expression',
  set: (value: CodeNodeConfig['language']) => {
    if (value === 'javascript') {
      const code = config.value.code && config.value.code.includes('function main') ? config.value.code : DEFAULT_CODE_TEMPLATE
      write({
        language: 'javascript',
        code,
        inputs: config.value.inputs && Object.keys(config.value.inputs).length ? config.value.inputs : { text: 'lastOutput' },
        outputs: { result: 'string', length: 'number' },
      })
    } else {
      write({ language: 'expression', inputs: undefined, timeoutMs: undefined, outputs: { result: 'lastOutput' } })
    }
  },
})

const inputsText = ref('')
const codeText = ref('')
const outputsText = ref('')
watch(
  () => [props.data, config.value] as const,
  () => {
    inputsText.value = formatMap(config.value.inputs)
    codeText.value = config.value.code || ''
    outputsText.value = formatMap(config.value.outputs)
  },
  { immediate: true },
)

function commitInputs() {
  const next = parseMap(inputsText.value)
  if (JSON.stringify(next) !== JSON.stringify(config.value.inputs || {})) write({ inputs: next })
}
function commitCode() {
  if (codeText.value !== (config.value.code || '')) write({ code: codeText.value })
}
function commitOutputs() {
  const next = parseMap(outputsText.value)
  if (JSON.stringify(next) !== JSON.stringify(config.value.outputs || {})) write({ outputs: next })
}

const timeoutSeconds = computed({
  get: () => (config.value.timeoutMs || 3000) / 1000,
  set: (value: number | undefined) => {
    const ms = Math.round(Math.max(0.1, Math.min(10, value || 3)) * 1000)
    if (ms !== (config.value.timeoutMs || 3000)) write({ timeoutMs: ms === 3000 ? undefined : ms })
  },
})
</script>

<style scoped>
.code-editor :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  tab-size: 2;
}
</style>
