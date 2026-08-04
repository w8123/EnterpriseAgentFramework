<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Connection, Tools, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  debugPageActionCatalog,
  getPageActionDebugResult,
  listPageActionCatalog,
  type PageActionEventView,
} from '@/api/embedOps'
import type { ProjectPage, ProjectPageAction } from '@/types/pageWorkbench'
import {
  pageWorkbenchActionTitle,
  pageWorkbenchPageName,
} from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  page: ProjectPage
  initialActionId?: number | null
}>()

const emit = defineEmits<{
  actions: [page: ProjectPage]
  diagnostics: [page: ProjectPage]
  success: [page: ProjectPage]
}>()

const selectedActionId = ref<number | null>(null)
const argsJson = ref('{}')
const loading = ref(false)
const status = ref('')
const message = ref('')
const resultJson = ref('')
const requestId = ref('')
let executionSequence = 0

const selectedAction = computed(() => props.page.actions.find(
  (action) => action.id === selectedActionId.value,
) || null)

function resetExecution(action?: ProjectPageAction | null) {
  executionSequence += 1
  loading.value = false
  argsJson.value = JSON.stringify(action?.sampleArgs || {}, null, 2)
  status.value = ''
  message.value = ''
  resultJson.value = ''
  requestId.value = ''
}

watch(
  [
    () => props.page.id,
    () => props.initialActionId,
    () => props.page.actions.map((action) => action.id).join(','),
  ],
  () => {
    const requested = props.page.actions.find(
      (action) => action.id === props.initialActionId,
    )
    const action = requested || props.page.actions[0] || null
    selectedActionId.value = action?.id || null
    resetExecution(action)
  },
  { immediate: true },
)

watch(selectedActionId, (actionId, previousActionId) => {
  if (actionId === previousActionId) return
  resetExecution(props.page.actions.find((action) => action.id === actionId))
})

onBeforeUnmount(() => {
  executionSequence += 1
})

function statusLabel(value: string) {
  const labels: Record<string, string> = {
    REQUESTED: '等待业务页面执行',
    SUCCESS: '执行成功',
    FAILED: '执行失败',
    CANCELLED: '已取消',
    ACTION_NOT_FOUND: '业务页面未注册该操作',
    FORBIDDEN: '无权执行',
    TIMEOUT: '执行超时',
    NO_ACTIVE_SESSION: '没有在线页面会话',
  }
  return labels[value] || value || '尚未执行'
}

function statusType(value: string): 'success' | 'warning' | 'info' | 'danger' {
  if (value === 'SUCCESS') return 'success'
  if (value === 'REQUESTED') return 'warning'
  if (!value) return 'info'
  return 'danger'
}

function prettyResult(value: string) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function parseArgs() {
  const parsed = JSON.parse(argsJson.value || '{}') as unknown
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('输入参数必须是 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

async function waitForResult(id: string, sequence: number) {
  let latest: PageActionEventView | null = null
  for (let attempt = 0; attempt < 40; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 500))
    if (sequence !== executionSequence) return null
    const response = await getPageActionDebugResult(id)
    latest = response.data
    if (latest.status !== 'REQUESTED') return latest
  }
  return latest
}

async function runDebug() {
  const action = selectedAction.value
  if (!action) return

  const sequence = ++executionSequence
  loading.value = true
  status.value = ''
  message.value = ''
  resultJson.value = ''
  requestId.value = ''
  try {
    const args = parseArgs()
    const catalogResponse = await listPageActionCatalog({
      projectCode: props.page.projectCode,
      pageKey: props.page.pageKey,
      status: 'ACTIVE',
      limit: 500,
    })
    if (sequence !== executionSequence) return
    const catalogAction = (catalogResponse.data || []).find(
      (item) => item.actionKey === action.actionKey,
    )
    if (!catalogAction) {
      throw new Error(`页面操作目录中未找到已启用的 ${action.actionKey}`)
    }

    const response = await debugPageActionCatalog(catalogAction.id, { args })
    if (sequence !== executionSequence) return
    const request = response.data
    status.value = request.status
    message.value = request.status === 'NO_ACTIVE_SESSION'
      ? '没有找到当前页面的在线 SDK 会话。请保持业务页面打开并至少发送一次对话消息后重试。'
      : '调试请求已发送，正在等待业务页面执行。'
    requestId.value = request.requestId || ''
    if (!request.requestId) {
      ElMessage.warning(message.value)
      return
    }

    const result = await waitForResult(request.requestId, sequence)
    if (sequence !== executionSequence) return
    if (!result || result.status === 'REQUESTED') {
      status.value = 'TIMEOUT'
      message.value = '业务页面在 20 秒内没有回传结果。请确认页面保持打开、Page Bridge 已注册该操作后重试。'
      return
    }

    status.value = result.status
    message.value = result.status === 'SUCCESS'
      ? '业务页面已执行并回传真实结果。'
      : result.errorMessage || '业务页面已回传失败结果，请检查页面控制台和操作实现。'
    resultJson.value = result.resultJson || ''
    if (result.status === 'SUCCESS') {
      ElMessage.success('页面操作调试成功')
      emit('success', props.page)
    }
  } catch (error) {
    if (sequence !== executionSequence) return
    status.value = 'FAILED'
    message.value = (error as Error).message || '页面操作调试失败'
    ElMessage.error(message.value)
  } finally {
    if (sequence === executionSequence) loading.value = false
  }
}
</script>

<template>
  <section
    class="page-debug-inline"
    role="region"
    aria-labelledby="page-debug-inline-title"
  >
    <header class="resource-pane-heading">
      <div>
        <small>真实页面执行</small>
        <h3 id="page-debug-inline-title">调试验证</h3>
        <p>请求将发送到当前在线页面，并由 Page Bridge 执行后回传真实结果。</p>
      </div>
    </header>

    <template v-if="page.actions.length">
      <el-alert
        class="page-debug-inline__alert"
        title="请保持对应业务页面打开"
        description="在线页面需要携带有效 SDK 会话，并注册当前页面操作。"
        type="info"
        :closable="false"
        show-icon
      />

      <div class="page-debug-inline__workspace">
        <aside class="page-debug-inline__catalog">
          <label for="page-debug-action-select">选择页面操作</label>
          <el-select
            id="page-debug-action-select"
            v-model="selectedActionId"
            placeholder="选择要验证的操作"
          >
            <el-option
              v-for="action in page.actions"
              :key="action.id"
              :label="pageWorkbenchActionTitle(action)"
              :value="action.id"
            />
          </el-select>

          <dl v-if="selectedAction" class="page-action-debug__identity">
            <div>
              <dt>页面</dt>
              <dd>{{ pageWorkbenchPageName(page) }}</dd>
            </div>
            <div>
              <dt>actionKey</dt>
              <dd>{{ selectedAction.actionKey }}</dd>
            </div>
          </dl>

          <div class="page-debug-inline__connection-note">
            <el-icon><Connection /></el-icon>
            <span>只展示真实请求状态，不使用前端计时器模拟执行成功。</span>
          </div>
        </aside>

        <article class="page-debug-inline__runner">
          <header>
            <div><small>请求参数</small><h4>输入参数（JSON 对象）</h4></div>
            <el-button
              type="primary"
              :icon="VideoPlay"
              :loading="loading"
              :disabled="!selectedAction"
              @click="runDebug"
            >
              {{ status ? '重新执行' : '执行调试' }}
            </el-button>
          </header>
          <el-input
            v-model="argsJson"
            type="textarea"
            :rows="8"
            spellcheck="false"
          />

          <section v-if="status" class="page-action-debug__result">
            <header>
              <strong>执行结果</strong>
              <el-tag :type="statusType(status)" effect="light">
                {{ statusLabel(status) }}
              </el-tag>
            </header>
            <p>{{ message }}</p>
            <small v-if="requestId">请求 ID：{{ requestId }}</small>
            <pre v-if="resultJson">{{ prettyResult(resultJson) }}</pre>
          </section>

          <footer v-if="status === 'SUCCESS'">
            <el-button size="small" @click="emit('diagnostics', page)">
              查看接入诊断
            </el-button>
          </footer>
        </article>
      </div>
    </template>

    <div v-else class="page-debug-inline__empty">
      <el-icon><Tools /></el-icon>
      <strong>还没有可验证的页面操作</strong>
      <p>先登记页面操作契约，保存后再通过真实页面会话执行调试。</p>
      <el-button type="primary" @click="emit('actions', page)">
        新增页面操作
      </el-button>
    </div>
  </section>
</template>
