<template>
  <div class="node-specific-panel">
    <ConfigSection title="确认内容" description="流程在这里暂停，批准后走「批准」线，拒绝走「拒绝」线">
      <el-form-item label="标题">
        <el-input
          :model-value="config.title"
          placeholder="例如：确认给客户退款"
          @update:model-value="write({ title: String($event || '') })"
        />
      </el-form-item>
      <el-form-item label="要确认的内容">
        <el-input
          :model-value="config.prompt"
          type="textarea"
          :rows="5"
          placeholder="可以引用变量，例如：退款 {{ params.amount }} 元给 {{ params.customer }}"
          @update:model-value="write({ prompt: String($event || '') })"
        />
      </el-form-item>
    </ConfigSection>

    <ConfigSection title="谁来确认" description="指定审批人后，审批人在 ReachAI「待办与审核」里处理，发起人的对话里显示等待状态">
      <el-form-item label="确认人">
        <el-radio-group :model-value="mode" data-testid="approval-mode" @update:model-value="setMode($event as ApproverMode)">
          <el-radio value="RUNNER">运行的人自己确认</el-radio>
          <el-radio value="ASSIGNED">指定审批人</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="mode === 'ASSIGNED'">
        <el-form-item label="人员">
          <el-select
            :model-value="selectedUsernames"
            multiple
            filterable
            clearable
            :loading="optionsLoading"
            placeholder="选择 ReachAI 账号，可多选"
            data-testid="approval-users"
            @update:model-value="setUsers($event as string[])"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.username"
              :label="user.displayName === user.username ? user.username : `${user.displayName}（${user.username}）`"
              :value="user.username"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group :model-value="config.approverRoles || []" data-testid="approval-roles" @update:model-value="write({ approverRoles: ($event as string[]) })">
            <el-checkbox v-for="role in roleOptions" :key="role.code" :value="role.code">{{ role.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label=" ">
          <el-checkbox :model-value="Boolean(config.allowRunnerApprove)" @update:model-value="write({ allowRunnerApprove: Boolean($event) })">
            允许发起人自己审批
          </el-checkbox>
        </el-form-item>
        <p v-if="assigneesMissing" class="hint warn" role="alert">请至少选一个人员或角色，否则不能发布。</p>
        <p v-if="optionsError" class="hint warn">{{ optionsError }}</p>
      </template>
      <el-form-item label="最长等待">
        <el-input-number
          :model-value="Math.round((config.timeoutSeconds || defaultTimeout) / 60)"
          :min="1"
          :max="mode === 'ASSIGNED' ? 43200 : 10080"
          @update:model-value="write({ timeoutSeconds: Math.max(1, Number($event || 60)) * 60 })"
        />
        <span class="hint-inline">分钟，超时后这次运行结束</span>
      </el-form-item>
      <div class="hint">
        <template v-if="mode === 'ASSIGNED'">
          任意一位审批人批准或拒绝即可。审批人只做决定：之后的步骤仍按发起人的身份和调用权限执行，
          发起人的对话打开着时会自动继续。审批意见可以在后面的节点里用 <code v-pre>{{ approval_result.comment }}</code> 引用。
          调试时由你代替审批人确认。
        </template>
        <template v-else>在调试、Agent 对话和嵌入式助手里会弹出确认卡片，运行的人自己点批准或拒绝。</template>
        定时自动化和员工 AI 工具（MCP）调用时不能暂停，会直接失败；这类场景里的写操作已经由「待办与审核」把关。
      </div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, onMounted, ref, watch } from 'vue'
import type { ApprovalApproverOption, CanvasNodeData, HumanApprovalNodeConfig } from '@/types/studio'
import { getApproverOptions, type ApproverOptions } from '@/api/workflowApprovals'

type ApproverMode = 'RUNNER' | 'ASSIGNED'

const props = defineProps<{ data: CanvasNodeData }>()

const DEFAULT_ROLES: ApproverOptions['roles'] = [
  { code: 'MANAGER', label: '管理人员' },
  { code: 'DEVELOPER', label: '开发人员' },
  { code: 'PLATFORM_ADMIN', label: '平台管理员' },
]

const config = computed<HumanApprovalNodeConfig>(() => props.data.approvalConfig || {
  title: '人工确认',
  prompt: '{{ lastOutput }}',
  approvers: [],
  timeoutSeconds: 3600,
})
const mode = computed<ApproverMode>(() => config.value.approverMode === 'ASSIGNED' ? 'ASSIGNED' : 'RUNNER')
const defaultTimeout = computed(() => mode.value === 'ASSIGNED' ? 86400 : 3600)
const selectedUsernames = computed(() => (config.value.approverUsers || []).map(user => user.username))
const assigneesMissing = computed(() => mode.value === 'ASSIGNED'
  && !(config.value.approverUsers || []).length && !(config.value.approverRoles || []).length)

const options = ref<ApproverOptions | null>(null)
const optionsLoading = ref(false)
const optionsError = ref('')
const roleOptions = computed(() => options.value?.roles?.length ? options.value.roles : DEFAULT_ROLES)
const userOptions = computed<Array<{ username: string; displayName: string }>>(() => {
  const loaded = options.value?.users ?? []
  // 已选但当前看不到的账号（例如被停用）也保留显示，避免保存时被静默去掉。
  const extra = (config.value.approverUsers || [])
    .filter(user => !loaded.some(option => option.username === user.username))
    .map(user => ({ username: user.username, displayName: user.displayName || user.username }))
  return [...loaded, ...extra]
})

function write(patch: Partial<HumanApprovalNodeConfig>) {
  props.data.approvalConfig = { ...config.value, ...patch }
}

function setMode(next: ApproverMode) {
  const patch: Partial<HumanApprovalNodeConfig> = { approverMode: next }
  // 指定审批人通常要等更久：仍是默认 1 小时的，改成 24 小时。
  if (next === 'ASSIGNED' && (!config.value.timeoutSeconds || config.value.timeoutSeconds === 3600)) {
    patch.timeoutSeconds = 86400
  }
  if (next === 'RUNNER' && config.value.timeoutSeconds && config.value.timeoutSeconds > 604800) {
    patch.timeoutSeconds = 604800
  }
  write(patch)
  if (next === 'ASSIGNED') void loadOptions()
}

function setUsers(usernames: string[]) {
  const users: ApprovalApproverOption[] = usernames.map((username) => {
    const option = userOptions.value.find(user => user.username === username)
    return { username, displayName: option?.displayName || username }
  })
  write({ approverUsers: users })
}

async function loadOptions() {
  if (options.value || optionsLoading.value) return
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const { data } = await getApproverOptions()
    options.value = data
  } catch {
    optionsError.value = '人员列表加载失败；可以先按角色指定。'
  } finally {
    optionsLoading.value = false
  }
}

onMounted(() => { if (mode.value === 'ASSIGNED') void loadOptions() })
watch(() => props.data, () => { if (mode.value === 'ASSIGNED') void loadOptions() })
</script>

<style scoped>
.hint.warn {
  color: var(--color-warning, #e6a23c);
}
</style>
