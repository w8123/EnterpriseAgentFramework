<template>
  <el-drawer
    v-model="open"
    title="Workflow 评测"
    size="760px"
    class="workflow-eval-drawer"
    destroy-on-close
  >
    <div class="eval-body">
      <section class="eval-panel">
        <div class="eval-section-head">
          <div>
            <strong>发布前用例</strong>
            <span>使用当前 Workflow GraphSpec 草稿在沙箱模式下重复运行。</span>
          </div>
          <div class="eval-import-actions">
            <el-button size="small" @click="$emit('add-case')">新增用例</el-button>
            <el-button size="small" @click="$emit('reset-cases')">重置示例</el-button>
          </div>
        </div>
        <el-table :data="cases" size="small" max-height="300">
          <el-table-column label="启用" width="64">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" />
            </template>
          </el-table-column>
          <el-table-column label="用例" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.caseNo" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="消息" min-width="190">
            <template #default="{ row }">
              <el-input v-model="row.message" type="textarea" :rows="2" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="输入 JSON" min-width="190">
            <template #default="{ row }">
              <el-input v-model="row.inputParamsJson" type="textarea" :rows="2" size="small" spellcheck="false" />
            </template>
          </el-table-column>
          <el-table-column label="期望包含" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.expectedText" size="small" placeholder="可留空" />
            </template>
          </el-table-column>
          <el-table-column label="" width="54" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="danger" @click="$emit('remove-case', row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="eval-panel">
        <div class="eval-section-head">
          <div>
            <strong>运行设置</strong>
            <span>默认附加 evalMode 和 sandboxSideEffects，避免真实不可逆副作用。</span>
          </div>
        </div>
        <div class="eval-toolbar">
          <el-input-number v-model="repeatCount" :min="1" :max="20" controls-position="right" />
          <el-button :loading="validating" @click="$emit('validate-runtime')">校验 GraphSpec</el-button>
          <el-button type="primary" :loading="running" :disabled="!enabledCaseCount" @click="$emit('run')">
            开始评测
          </el-button>
        </div>
      </section>

      <section v-if="results.length" class="eval-summary-grid">
        <div class="eval-metric">
          <span>断言通过</span>
          <strong>{{ formatRate(summary.assertionRate) }}</strong>
        </div>
        <div class="eval-metric">
          <span>运行成功</span>
          <strong>{{ formatRate(summary.runtimeSuccessRate) }}</strong>
        </div>
        <div class="eval-metric">
          <span>P95 响应</span>
          <strong>{{ summary.p95LatencyMs }} ms</strong>
        </div>
        <div class="eval-metric">
          <span>偏差数</span>
          <strong>{{ summary.biasCount }}</strong>
        </div>
      </section>

      <section v-if="results.length" class="eval-panel">
        <div class="eval-section-head">
          <div>
            <strong>评测结果</strong>
            <span>{{ results.length }} 次执行 · {{ runName }}</span>
          </div>
        </div>
        <el-table :data="results" size="small" max-height="320">
          <el-table-column prop="roundNo" label="轮次" width="72" />
          <el-table-column prop="caseNo" label="用例" min-width="120" />
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="row.assertionPassed ? 'success' : row.runtimeSuccess ? 'warning' : 'danger'" size="small">
                {{ row.assertionPassed ? '通过' : row.runtimeSuccess ? '偏差' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="elapsedMs" label="耗时" width="92" />
          <el-table-column prop="answer" label="输出" min-width="220" show-overflow-tooltip />
          <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
        </el-table>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import type {
  WorkflowEvalCase,
  WorkflowEvalResult,
} from '@/views/workflow/composables/useWorkflowStudioEval'

interface WorkflowEvalSummary {
  assertionRate: number
  runtimeSuccessRate: number
  p95LatencyMs: number
  biasCount: number
}

defineProps<{
  cases: WorkflowEvalCase[]
  results: WorkflowEvalResult[]
  enabledCaseCount: number
  summary: WorkflowEvalSummary
  runName: string
  validating: boolean
  running: boolean
  formatRate: (value: number) => string
}>()

defineEmits<{
  (event: 'add-case'): void
  (event: 'reset-cases'): void
  (event: 'remove-case', id: string): void
  (event: 'validate-runtime'): void
  (event: 'run'): void
}>()

const open = defineModel<boolean>('open', { required: true })
const repeatCount = defineModel<number>('repeatCount', { required: true })
</script>

<style scoped lang="scss">
.eval-body {
  display: grid;
  gap: 14px;
}

.eval-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.eval-section-head,
.eval-toolbar,
.eval-import-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.eval-section-head {
  justify-content: space-between;
}

.eval-section-head strong,
.eval-section-head span {
  display: block;
}

.eval-section-head span {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.eval-toolbar {
  flex-wrap: wrap;
}

.eval-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.eval-metric {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.eval-metric span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.eval-metric strong {
  color: var(--el-text-color-primary);
  font-size: 18px;
}
</style>
