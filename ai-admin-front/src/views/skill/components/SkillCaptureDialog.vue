<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassInput, LiquidGlassNotice } from '@/components/common/liquid-glass'
import { createAgentSkillCapture, previewAgentSkillCapture } from '@/api/skill'
import { createWorkflow } from '@/api/workflow'
import type { AgentSkillCaptureDraft, AgentSkillCaptureSource } from '@/types/skill'

const props = defineProps<{
  modelValue: boolean
  source: AgentSkillCaptureSource
  callLogIds?: number[]
  traceId?: string
  /** 能否顺带生成 Workflow 草稿（需要该系统的 workflow:write）。 */
  canCreateWorkflow?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [skillId: number]
}>()

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const creatingWorkflow = ref(false)
const error = ref('')
const draft = ref<AgentSkillCaptureDraft | null>(null)
const displayName = ref('')
const skillMd = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const stepSummary = computed(() => (draft.value?.steps ?? [])
  .map((step) => `${step.order}. ${step.description || step.toolName}${step.requiresApproval ? '（需审批）' : ''}`)
  .join('  '))

function errorMessage(value: unknown, fallback: string) {
  const response = (value as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || (value instanceof Error && value.message ? value.message : fallback)
}

async function loadDraft() {
  loading.value = true
  error.value = ''
  draft.value = null
  try {
    const { data } = await previewAgentSkillCapture({
      source: props.source,
      callLogIds: props.source === 'MCP_CALLS' ? props.callLogIds : undefined,
      traceId: props.source === 'RUN' ? props.traceId : undefined,
    })
    draft.value = data
    displayName.value = data.displayName
    skillMd.value = data.skillMd
  } catch (cause) {
    error.value = errorMessage(cause, '生成草稿失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (open) void loadDraft()
}, { immediate: true })

async function save() {
  if (!draft.value || saving.value) return
  saving.value = true
  try {
    const { data } = await createAgentSkillCapture({
      projectCode: draft.value.projectCode,
      displayName: displayName.value.trim() || undefined,
      skillMd: skillMd.value,
      sourceRef: draft.value.sourceRef,
    })
    ElMessage.success(`已保存为 ${data.skill.name}@${data.version.version}，审核发布后员工就能下载`)
    emit('saved', data.skill.id)
    visible.value = false
    void router.push({ path: '/skills', query: { skillId: String(data.skill.id), versionId: String(data.version.id) } })
  } catch (cause) {
    ElMessage.error(errorMessage(cause, '保存 Skill 失败'))
  } finally {
    saving.value = false
  }
}

async function createWorkflowDraft() {
  if (!draft.value?.projectId || creatingWorkflow.value) return
  creatingWorkflow.value = true
  try {
    const suffix = Date.now().toString(36)
    const { data } = await createWorkflow({
      name: (displayName.value.trim() || draft.value.displayName).slice(0, 60),
      keySlug: `${draft.value.name}`.slice(0, 100) + `-${suffix}`,
      projectId: draft.value.projectId,
      projectCode: draft.value.projectCode,
      workflowKind: 'GENERAL',
      executionEngine: 'GRAPH_SPEC',
      definitionAuthority: 'USER',
      creationChannel: 'STUDIO',
      description: `由运行记录生成（${draft.value.sourceRef}）`.slice(0, 200),
      status: 'DRAFT',
      graphSpecJson: '{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}',
      canvasJson: '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}',
    })
    if (!data?.id) return
    visible.value = false
    void router.push({
      path: `/workflows/${data.id}/studio`,
      query: { aiInstruction: draft.value.workflowInstruction },
    })
  } catch (cause) {
    ElMessage.error(errorMessage(cause, '创建 Workflow 草稿失败'))
  } finally {
    creatingWorkflow.value = false
  }
}
</script>

<template>
  <AppDialog
    v-model="visible"
    title="沉淀成 Skill"
    description="按这次真实操作生成一份 Skill（SKILL.md）。只写工具名和参数名，不写当时填的客户数据。保存后进入审核，发布后员工可在业务系统的 AI 工具面板里下载，放进 Claude Code / Codex 使用。"
    width="min(920px, 94vw)"
  >
    <div v-loading="loading" class="skill-capture">
      <LiquidGlassNotice v-if="error" type="error" :title="error" source="backdrop" />
      <template v-if="draft">
        <LiquidGlassNotice
          v-for="warning in draft.warnings"
          :key="warning"
          type="warning"
          :title="warning"
          source="backdrop"
        />
        <dl class="skill-capture__facts">
          <div><dt>业务系统</dt><dd>{{ draft.systemName }}</dd></div>
          <div><dt>Skill 名称</dt><dd><code>{{ draft.name }}</code> · 版本 {{ draft.version }}</dd></div>
          <div><dt>步骤</dt><dd>{{ stepSummary }}</dd></div>
        </dl>
        <label class="skill-capture__label" for="skill-capture-name">显示名称</label>
        <LiquidGlassInput id="skill-capture-name" v-model="displayName" maxlength="128" aria-label="显示名称" />
        <label class="skill-capture__label" for="skill-capture-md">SKILL.md（可直接修改；开头的 name 决定 Skill 名称）</label>
        <el-input
          id="skill-capture-md"
          v-model="skillMd"
          type="textarea"
          class="skill-capture__editor"
          :autosize="{ minRows: 14, maxRows: 26 }"
          spellcheck="false"
          aria-label="SKILL.md"
        />
      </template>
    </div>
    <template #footer>
      <LiquidGlassButton @click="visible = false">取消</LiquidGlassButton>
      <LiquidGlassButton
        v-if="canCreateWorkflow && draft?.projectId"
        :loading="creatingWorkflow"
        :disabled="loading"
        @click="createWorkflowDraft"
      >
        生成 Workflow 草稿
      </LiquidGlassButton>
      <LiquidGlassButton type="primary" :loading="saving" :disabled="!draft || loading" @click="save">
        保存并提交审核
      </LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.skill-capture {
  display: grid;
  gap: 10px;
  min-height: 120px;
}

.skill-capture__facts {
  display: grid;
  gap: 6px;
  margin: 0;

  div {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 8px;
  }

  dt {
    color: var(--text-secondary);
  }

  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
}

.skill-capture__label {
  color: var(--text-secondary);
  font-size: 13px;
}

.skill-capture__editor :deep(textarea) {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
  line-height: 1.55;
}
</style>
