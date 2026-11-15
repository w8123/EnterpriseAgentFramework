<template>
  <section v-if="candidates.length" class="learned-flow-panel">
    <header class="learned-flow-panel__header">
      <strong>可以沉淀为直达流程的请求</strong>
      <p>
        下面这些请求，助手近 30 天多次靠推理完成。沉淀为直达流程后，再遇到同类请求会直接执行，更快也更省 token。
        生成的草稿不会对员工生效，确认启用后才会用到。
      </p>
    </header>
    <ul class="learned-flow-panel__list">
      <li v-for="item in candidates" :key="item.sourceRef" class="learned-flow-panel__item">
        <div class="learned-flow-panel__copy">
          <strong>{{ item.title }}</strong>
          <small>推理完成 {{ item.runCount }} 次，平均 {{ seconds(item.avgLatencyMs) }} 秒</small>
        </div>
        <div class="learned-flow-panel__actions">
          <span v-if="item.attached" class="asset-pill is-success">已启用</span>
          <template v-else-if="item.workflowId">
            <el-button link type="primary" @click="openStudio(item.workflowId)">查看草稿</el-button>
            <el-button type="primary" :loading="busy === item.sourceRef" @click="enable(item)">确认启用</el-button>
          </template>
          <el-button v-else :loading="busy === item.sourceRef" @click="draft(item)">生成流程草稿</el-button>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  draftLearnedFlow,
  enableLearnedFlow,
  listLearnedFlows,
  type LearnedFlowCandidate,
} from '@/api/agentLearnedFlow'

const props = defineProps<{ agentId: string }>()
const emit = defineEmits<{ (event: 'enabled'): void }>()

const router = useRouter()
const candidates = ref<LearnedFlowCandidate[]>([])
const busy = ref('')

function seconds(ms: number) {
  return Math.max(1, Math.round((ms || 0) / 1000))
}

async function load() {
  try {
    const { data } = await listLearnedFlows(props.agentId)
    candidates.value = Array.isArray(data) ? data : []
  } catch {
    // 这只是建议区，读不到就不显示，不打断配置页。
    candidates.value = []
  }
}

async function draft(item: LearnedFlowCandidate) {
  busy.value = item.sourceRef
  try {
    await draftLearnedFlow(props.agentId, item.sourceRef)
    ElMessage.success('已生成流程草稿，确认启用后才会对员工生效')
    await load()
  } finally {
    busy.value = ''
  }
}

async function enable(item: LearnedFlowCandidate) {
  try {
    await ElMessageBox.confirm(
      `启用后会发布流程「${item.title}」并发布助手的新版本，员工再问同类问题时直接走这个流程。`,
      '确认启用直达流程',
      { confirmButtonText: '确认启用', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = item.sourceRef
  try {
    await enableLearnedFlow(props.agentId, item.sourceRef, 'admin-ui')
    ElMessage.success('已启用')
    await load()
    emit('enabled')
  } finally {
    busy.value = ''
  }
}

function openStudio(workflowId?: string | null) {
  if (!workflowId) return
  const target = router.resolve(`/workflows/${workflowId}/studio`)
  window.open(target.href, '_blank', 'noopener')
}

onMounted(load)
</script>

<style scoped lang="scss">
.learned-flow-panel {
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;

  &__header p {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-secondary);
  }

  &__list {
    margin: 10px 0 0;
    padding: 0;
    list-style: none;
  }

  &__item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0;
    border-top: 1px solid var(--border-divider);
  }

  &__copy {
    display: flex;
    flex-direction: column;
    min-width: 0;

    small {
      margin-top: 2px;
      color: var(--text-secondary);
    }
  }

  &__actions {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    gap: 8px;
  }
}
</style>
