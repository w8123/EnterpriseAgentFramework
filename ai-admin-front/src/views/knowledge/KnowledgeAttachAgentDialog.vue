<template>
  <AppDialog
    :model-value="modelValue"
    title="接入 Agent"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="选择 Agent">
        <el-select
          v-model="agentId"
          filterable
          placeholder="选择要使用本知识库回答问题的 Agent"
          :loading="agentsLoading"
          style="width: 100%"
        >
          <el-option
            v-for="agent in agents"
            :key="agent.id"
            :label="agent.projectCode ? `${agent.name}（${agent.projectCode}）` : agent.name"
            :value="agent.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="attach-explain">
      <p>平台会做三件事：</p>
      <ol>
        <li>在该 Agent 所属项目里生成「知识问答：{{ kbName }}」Workflow：检索本库，按资料回答并注明出处，查不到时回复「资料里没有」；</li>
        <li>发布这个 Workflow；</li>
        <li>把它加入 Agent 的可调用 Workflow，并发布 Agent 的新配置版本。</li>
      </ol>
      <p>之后可以在 Workflow Studio 里像普通 Workflow 一样继续修改。已经接入过的 Agent 再点一次不会重复添加。</p>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!agentId" @click="handleAttach">接入并发布</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { listAgents } from '@/api/workflow'
import { attachKnowledgeToAgent } from '@/api/knowledge'
import type { Agent } from '@/types/workflow'
import type { KnowledgeQaAttachResult } from '@/types/knowledge'
import { platformSessionUser } from '@/auth/platformSession'

const props = defineProps<{
  modelValue: boolean
  kbCode: string
  kbName: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  attached: [result: KnowledgeQaAttachResult]
}>()

const agents = ref<Agent[]>([])
const agentsLoading = ref(false)
const agentId = ref('')
const submitting = ref(false)

watch(() => props.modelValue, (open) => {
  if (open && agents.value.length === 0) void fetchAgents()
})

async function fetchAgents() {
  agentsLoading.value = true
  try {
    const { data } = await listAgents()
    agents.value = (Array.isArray(data) ? data : []).filter((agent) => agent.enabled !== false)
    if (agents.value.length === 1) agentId.value = agents.value[0].id
  } finally {
    agentsLoading.value = false
  }
}

async function handleAttach() {
  if (!agentId.value) return
  submitting.value = true
  try {
    const { data } = await attachKnowledgeToAgent(agentId.value, {
      knowledgeBaseCode: props.kbCode,
      knowledgeBaseName: props.kbName,
      publishedBy: platformSessionUser.value?.displayName || platformSessionUser.value?.username || 'ReachAI 知识库',
    })
    ElMessage.success(data.alreadyAttached
      ? `${data.agentName} 已在使用本知识库`
      : `已接入 ${data.agentName}，Agent 配置已发布为 v${data.agentConfigVersion ?? ''}`)
    emit('attached', data)
    emit('update:modelValue', false)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped lang="scss">
.attach-explain {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);

  p {
    margin: 0 0 4px;
  }

  ol {
    margin: 0 0 8px;
    padding-left: 20px;
  }
}
</style>
