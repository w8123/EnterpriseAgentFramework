<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  Aim,
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  Clock,
  Close,
  CopyDocument,
  EditPen,
  InfoFilled,
  Lock,
  MagicStick,
  Monitor,
  Plus,
  Promotion,
  TopRight,
  VideoPlay,
  Warning,
} from '@element-plus/icons-vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import pageGoalBeforeAiIllustration from '@/assets/illustrations/page-goal-before-ai.webp'
import type { AiCodingTask, AiCodingTaskDetail } from '@/types/aiCodingTask'
import type {
  AnalysisFindingStatus,
  PageAccessJourney,
  PageAnalysisFinding,
  PageDetailCapabilityWorkspace,
  PageImplementationGoalDraft,
  PageIntegrationReadiness,
  ProjectPage,
  ProjectPageAction,
  PublishedPageWorkflow,
} from '@/types/pageWorkbench'
import {
  pageWorkbenchActionTitle,
  pageWorkbenchHumanText,
  pageWorkbenchModuleName,
  pageWorkbenchPageName,
  pageWorkbenchRiskLabel,
} from '@/utils/pageWorkbenchPresentation'
import { readPageImplementationGoalSelection } from '@/utils/aiCodingGoalSelection'
import PageWorkbenchActionPanel from '@/views/registry/components/page-workbench/PageWorkbenchActionPanel.vue'
import PageWorkbenchResourcePanel from '@/views/registry/components/page-workbench/PageWorkbenchResourcePanel.vue'
import PageWorkbenchDiagnosticsPanel from '@/views/registry/components/page-workbench/PageWorkbenchDiagnosticsPanel.vue'
import PageWorkbenchDebugPanel from '@/views/registry/components/page-workbench/PageWorkbenchDebugPanel.vue'

const props = defineProps<{
  page: ProjectPage
  journey?: PageAccessJourney | null
  findings?: PageAnalysisFinding[]
  task?: AiCodingTask | null
  taskDetail?: AiCodingTaskDetail | null
  taskDetailLoading?: boolean
  tasks?: AiCodingTask[]
  workflow?: PublishedPageWorkflow | null
  readiness?: PageIntegrationReadiness | null
  readinessLoading?: boolean
  capabilityWorkspace?: PageDetailCapabilityWorkspace
  debugActionId?: number | null
  businessPageUrl?: string
  runtimeAvailable?: boolean
  runtimeMessage?: string
  busy?: boolean
}>()

const emit = defineEmits<{
  back: []
  analyze: [page: ProjectPage]
  editPage: [page: ProjectPage]
  pageInfo: [page: ProjectPage]
  debugAction: [page: ProjectPage, action: ProjectPageAction]
  'update:capabilityWorkspace': [workspace: PageDetailCapabilityWorkspace]
  checkReadiness: [page: ProjectPage]
  debugSuccess: [page: ProjectPage]
  findingStatus: [finding: PageAnalysisFinding, status: AnalysisFindingStatus]
  findingDetail: [finding: PageAnalysisFinding]
  primary: [page: ProjectPage, journey: PageAccessJourney, draft: PageImplementationGoalDraft]
  retarget: [page: ProjectPage, journey: PageAccessJourney, draft: PageImplementationGoalDraft]
  history: [page: ProjectPage]
  refresh: []
  online: [workflow: PublishedPageWorkflow]
  taskRefresh: [taskId: string]
  taskAnswer: [taskId: string, questionId: string, answer: string]
  taskReissue: [taskId: string]
  taskVerify: [taskId: string]
  taskAcceptance: [taskId: string, passed: boolean, message: string]
  taskCancel: [taskId: string]
}>()

const manualGoal = ref('')
const manualGoalAdded = ref(false)
const retargetMode = ref(false)
const retargetManualGoal = ref('')
const retargetManualGoalAdded = ref(false)
const retargetFindingIds = ref<number[]>([])
const foundationActionSection = ref<HTMLElement | null>(null)
const flowTrackRef = ref<HTMLElement | null>(null)
const activeCapabilityWorkspace = computed<PageDetailCapabilityWorkspace>({
  get: () => props.capabilityWorkspace || 'goals',
  set: (workspace) => emit('update:capabilityWorkspace', workspace),
})

type FlowNavState = 'complete' | 'current' | 'future' | 'attention' | 'unavailable'
type FlowNavNodeKey =
  | 'foundation'
  | 'goals'
  | 'implementation'
  | 'diagnostics'
  | 'debug'

interface FlowNavNode {
  key: FlowNavNodeKey
  label: string
  description: string
  workspace: PageDetailCapabilityWorkspace
  flowState: FlowNavState
  selected: boolean
  statusText: string
  prerequisiteLabel: string
  icon: typeof CopyDocument
}

async function scrollFoundationToActions() {
  await nextTick()
  foundationActionSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function scrollSelectedFlowNodeIntoView() {
  await nextTick()
  const track = flowTrackRef.value
  if (!track) return
  const nav = track.parentElement
  const selected = track.querySelector<HTMLElement>('button.is-selected')
  if (!nav || !selected) return
  const navRect = nav.getBoundingClientRect()
  const selectedRect = selected.getBoundingClientRect()
  if (selectedRect.left < navRect.left || selectedRect.right > navRect.right) {
    nav.scrollBy({
      left: selectedRect.left - navRect.left - 12,
      behavior: 'smooth',
    })
  }
}

watch(
  activeCapabilityWorkspace,
  (workspace) => {
    if (workspace === 'actions') void scrollFoundationToActions()
    void scrollSelectedFlowNodeIntoView()
  },
  { flush: 'post', immediate: true },
)

watch(() => props.page.id, () => {
  manualGoal.value = ''
  manualGoalAdded.value = false
  retargetMode.value = false
  retargetManualGoal.value = ''
  retargetManualGoalAdded.value = false
  retargetFindingIds.value = []
})

watch(manualGoal, (value) => {
  if (!value.trim()) manualGoalAdded.value = false
})

watch(retargetManualGoal, (value) => {
  if (!value.trim()) retargetManualGoalAdded.value = false
})

watch(() => props.task?.taskId, (taskId, previousTaskId) => {
  if (previousTaskId && taskId !== previousTaskId) retargetMode.value = false
})

watch(() => props.journey?.stage, (stage) => {
  if (stage !== 'AI_IMPLEMENTATION') retargetMode.value = false
})

const selectedFindings = computed(() => (props.findings || []).filter(
  (item) => item.status === 'KEPT',
))

const currentGoalSelection = computed(() => readPageImplementationGoalSelection(
  props.task,
  props.findings || [],
))

const goalsLocked = computed(() => (
  Boolean(props.journey && props.journey.stage !== 'GOAL_SELECTION' && !retargetMode.value)
))

const goalsEditable = computed(() => !goalsLocked.value)

const canAdjustImplementationTargets = computed(() => Boolean(
  goalsLocked.value
    && props.task?.taskKind === 'CODE_IMPLEMENTATION'
))

const goalHeading = computed(() => {
  if (retargetMode.value) return '调整接入目标'
  if (goalsLocked.value) return '本次接入目标'
  return '选择本次接入目标'
})

const snapshotFindingIds = computed(() => currentGoalSelection.value.findingIds)

const activeManualGoal = computed({
  get: () => {
    if (retargetMode.value) return retargetManualGoal.value
    if (goalsLocked.value) return currentGoalSelection.value.manualGoal
    return manualGoal.value
  },
  set: (value: string) => {
    if (retargetMode.value) retargetManualGoal.value = value
    else if (!goalsLocked.value) manualGoal.value = value
  },
})

const activeManualGoalAdded = computed({
  get: () => {
    if (retargetMode.value) return retargetManualGoalAdded.value
    if (goalsLocked.value) return Boolean(currentGoalSelection.value.manualGoal)
    return manualGoalAdded.value
  },
  set: (value: boolean) => {
    if (retargetMode.value) retargetManualGoalAdded.value = value
    else if (!goalsLocked.value) manualGoalAdded.value = value
  },
})

const activeSelectedFindings = computed(() => {
  if (retargetMode.value) {
    const ids = new Set(retargetFindingIds.value)
    return (props.findings || []).filter((item) => ids.has(item.id))
  }
  if (goalsLocked.value) {
    const ids = new Set(snapshotFindingIds.value)
    return (props.findings || []).filter((item) => ids.has(item.id))
  }
  return selectedFindings.value
})

const lockedOrphanGoalItems = computed(() => {
  if (!goalsLocked.value) return []
  const matchedIds = new Set(activeSelectedFindings.value.map((item) => item.id))
  return currentGoalSelection.value.items.filter((item) => {
    if (item.source === 'MANUAL') return false
    if (item.findingId && matchedIds.has(item.findingId)) return false
    return Boolean(item.content.trim())
  })
})

const candidateFindings = computed(() => {
  const retainedIds = new Set(
    retargetMode.value
      ? retargetFindingIds.value
      : goalsLocked.value
        ? snapshotFindingIds.value
        : [],
  )
  return (props.findings || []).filter(
    (item) => item.status !== 'IGNORED' || retainedIds.has(item.id),
  )
})

const selectedTargetCount = computed(() => {
  if (goalsLocked.value) return currentGoalSelection.value.items.length
  return activeSelectedFindings.value.length
    + (activeManualGoalAdded.value && activeManualGoal.value.trim() ? 1 : 0)
})

const canStartImplementation = computed(() => selectedTargetCount.value > 0)

const foundationWorkspaceActive = computed(() => (
  activeCapabilityWorkspace.value === 'resources'
  || activeCapabilityWorkspace.value === 'actions'
))

const milestones = [
  { step: 1, label: '选择目标', description: '确认 AI 要完成什么' },
  { step: 2, label: 'AI 实施', description: '实施代码并完成自检' },
  { step: 3, label: '确认上线', description: '核对权限与发布版本' },
  { step: 4, label: '真实验收', description: '在业务页面验证结果' },
]

function milestoneClass(step: number) {
  if (!props.journey) return ''
  if (props.journey.completedSteps >= step) return 'is-complete'
  if (props.journey.currentStep === step) return 'is-current'
  return ''
}

const journeyUnavailable = computed(() => props.journey?.status === 'UNAVAILABLE')

const flowNavNodes = computed<FlowNavNode[]>(() => {
  const journey = props.journey
  const unavailable = journey?.status === 'UNAVAILABLE'
  const completedSteps = unavailable ? 0 : (journey?.completedSteps ?? 0)
  const stage = journey?.stage
  const journeyComplete = journey?.status === 'COMPLETE'
  const readiness = props.readiness

  const foundationDone = props.page.resources.length > 0
    || (!unavailable && (
      completedSteps >= 1
      || Boolean(stage && stage !== 'GOAL_SELECTION')
    ))

  const goalsDone = !unavailable && completedSteps >= 1
  const implementationDone = !unavailable && completedSteps >= 3
  const diagnosticsDone = journeyComplete || readiness?.status === 'PASS'
  const debugDone = journeyComplete

  const diagnosticsFailed = readiness?.status === 'FAIL'
    || Boolean(readiness?.items.some((item) => item.status === 'FAIL'))
  const diagnosticsPending = readiness?.status === 'PENDING'
    || Boolean(readiness?.items.some((item) => item.status === 'PENDING' || item.status === 'WARN'))
  const debugNeedsAttention = !debugDone
    && (journey?.status === 'ERROR' || journey?.status === 'ACTION_REQUIRED')
    && stage === 'REAL_ACCEPTANCE'

  type NodeSeed = {
    key: FlowNavNodeKey
    label: string
    description: string
    workspace: PageDetailCapabilityWorkspace
    icon: typeof CopyDocument
    complete: boolean
    attention: boolean
    unavailable: boolean
    statusWhenCurrent: string
    statusWhenAttention: string
    statusWhenIncomplete: string
  }

  const seeds: NodeSeed[] = [
    {
      key: 'foundation',
      label: '页面基础',
      description: '看看页面能提供什么',
      workspace: 'resources',
      icon: CopyDocument,
      complete: foundationDone,
      attention: false,
      unavailable: false,
      statusWhenCurrent: '当前要做',
      statusWhenAttention: '需处理',
      statusWhenIncomplete: '',
    },
    {
      key: 'goals',
      label: '接入目标',
      description: '明确页面要智能化成什么样',
      workspace: 'goals',
      icon: Aim,
      complete: goalsDone,
      attention: false,
      unavailable: unavailable && stage === 'GOAL_SELECTION',
      statusWhenCurrent: journey?.status === 'ACTION_REQUIRED' ? '等待你处理' : '当前要做',
      statusWhenAttention: '需处理',
      statusWhenIncomplete: '',
    },
    {
      key: 'implementation',
      label: 'AI 实施',
      description: '交给 AI Coding 完成页面改造',
      workspace: 'implementation',
      icon: MagicStick,
      complete: implementationDone,
      attention: !implementationDone
        && (journey?.status === 'ERROR' || journey?.status === 'ACTION_REQUIRED')
        && (stage === 'AI_IMPLEMENTATION' || stage === 'PUBLISH_CONFIRMATION'),
      unavailable: unavailable
        && (stage === 'AI_IMPLEMENTATION' || stage === 'PUBLISH_CONFIRMATION'),
      statusWhenCurrent: journey?.status === 'ACTIVE' ? '进行中' : (
        journey?.status === 'ACTION_REQUIRED' ? '等待你处理' : '当前要做'
      ),
      statusWhenAttention: '需处理',
      statusWhenIncomplete: '',
    },
    {
      key: 'diagnostics',
      label: '接入诊断',
      description: '检查页面是否接入成功',
      workspace: 'diagnostics',
      icon: Monitor,
      complete: diagnosticsDone,
      attention: !diagnosticsDone && diagnosticsFailed,
      unavailable: false,
      statusWhenCurrent: readiness == null
        ? '待检查'
        : (diagnosticsPending ? '检查中' : '当前要做'),
      statusWhenAttention: '需处理',
      statusWhenIncomplete: readiness == null
        ? '待检查'
        : (diagnosticsPending ? '待处理' : ''),
    },
    {
      key: 'debug',
      label: '调试验证',
      description: '验证改造结果能不能用',
      workspace: 'debug',
      icon: VideoPlay,
      complete: debugDone,
      attention: debugNeedsAttention,
      unavailable: unavailable && stage === 'REAL_ACCEPTANCE',
      statusWhenCurrent: journey?.status === 'ACTION_REQUIRED' ? '等待你处理' : '当前要做',
      statusWhenAttention: '需处理',
      statusWhenIncomplete: stage === 'REAL_ACCEPTANCE' ? '待验证' : '',
    },
  ]

  const recommendedKey = unavailable
    ? (
      seeds.find((item) => item.unavailable)?.key
      ?? seeds.find((item) => !item.complete)?.key
      ?? seeds[seeds.length - 1]?.key
    )
    : (
      seeds.find((item) => !item.complete)?.key
      ?? seeds[seeds.length - 1]?.key
    )

  const recommendedLabel = seeds.find((item) => item.key === recommendedKey)?.label || ''

  return seeds.map((seed) => {
    const selected = seed.key === 'foundation'
      ? foundationWorkspaceActive.value
      : activeCapabilityWorkspace.value === seed.workspace

    let flowState: FlowNavState
    if (seed.unavailable) flowState = 'unavailable'
    else if (seed.complete) flowState = 'complete'
    else if (seed.attention) flowState = 'attention'
    else if (seed.key === recommendedKey) flowState = 'current'
    else flowState = 'future'

    let statusText = ''
    if (flowState === 'complete') statusText = '已完成'
    else if (flowState === 'unavailable') statusText = '状态不可用'
    else if (flowState === 'attention') statusText = seed.statusWhenAttention
    else if (flowState === 'current') statusText = seed.statusWhenCurrent
    else statusText = seed.statusWhenIncomplete

    return {
      key: seed.key,
      label: seed.label,
      description: seed.description,
      workspace: seed.workspace,
      flowState,
      selected,
      statusText,
      prerequisiteLabel: flowState === 'future' ? recommendedLabel : '',
      icon: seed.icon,
    }
  })
})

const recommendedFlowNode = computed(() => (
  flowNavNodes.value.find((node) => node.flowState === 'current')
  || flowNavNodes.value.find((node) => node.flowState === 'attention')
  || flowNavNodes.value.find((node) => node.flowState === 'unavailable')
  || null
))

const selectedFlowNode = computed(() => (
  flowNavNodes.value.find((node) => node.selected) || null
))

const implementationNeedsGoalSelection = computed(() => (
  activeCapabilityWorkspace.value === 'implementation'
  && props.journey?.stage === 'GOAL_SELECTION'
))

const flowPreviewHint = computed(() => {
  if (implementationNeedsGoalSelection.value) return null
  const selected = selectedFlowNode.value
  const recommended = recommendedFlowNode.value
  if (!selected || !recommended) return null
  if (selected.key === recommended.key) return null
  if (selected.flowState === 'complete') return null
  if (selected.flowState !== 'future' && selected.flowState !== 'attention') return null
  const selectedIndex = flowNavNodes.value.findIndex((node) => node.key === selected.key)
  const recommendedIndex = flowNavNodes.value.findIndex((node) => node.key === recommended.key)
  if (selectedIndex <= recommendedIndex) return null
  return {
    message: `你正在查看后续环节「${selected.label}」。当前建议先完成「${recommended.label}」，完成后流程会自动推进。`,
    returnNode: recommended,
  }
})

function flowNodeTitle(node: FlowNavNode) {
  if (node.flowState === 'future' && node.prerequisiteLabel) {
    return `可提前查看，建议先完成 ${node.prerequisiteLabel}`
  }
  return node.statusText || node.description
}

function flowConnectorClass(node: FlowNavNode, next: FlowNavNode | undefined) {
  if (!next) return ''
  return {
    'is-complete': node.flowState === 'complete',
    'is-active': node.flowState === 'complete' && next.flowState === 'current',
    'is-muted': next.flowState === 'future' || node.flowState === 'future',
    'is-attention': next.flowState === 'attention' || node.flowState === 'attention',
  }
}

function activateFlowNode(node: FlowNavNode) {
  if (node.key === 'foundation') {
    openFoundationWorkspace()
    return
  }
  if (node.key === 'diagnostics') {
    openDiagnostics()
    return
  }
  if (node.key === 'debug') {
    openDebugWorkspace()
    return
  }
  activeCapabilityWorkspace.value = node.workspace
}

function toggleFinding(finding: PageAnalysisFinding) {
  if (goalsLocked.value) return
  if (retargetMode.value) {
    retargetFindingIds.value = retargetFindingIds.value.includes(finding.id)
      ? retargetFindingIds.value.filter((id) => id !== finding.id)
      : [...retargetFindingIds.value, finding.id]
    return
  }
  emit('findingStatus', finding, finding.status === 'KEPT' ? 'UNREAD' : 'KEPT')
}

function isFindingSelected(finding: PageAnalysisFinding) {
  if (retargetMode.value) return retargetFindingIds.value.includes(finding.id)
  if (goalsLocked.value) return snapshotFindingIds.value.includes(finding.id)
  return finding.status === 'KEPT'
}

function removeFinding(finding: PageAnalysisFinding) {
  if (goalsLocked.value) return
  if (retargetMode.value) {
    retargetFindingIds.value = retargetFindingIds.value.filter((id) => id !== finding.id)
    return
  }
  emit('findingStatus', finding, 'UNREAD')
}

type FindingRiskTagType = 'success' | 'warning' | 'danger' | 'info'

function findingRiskTagType(riskLevel?: string): FindingRiskTagType {
  if (riskLevel === 'READ' || riskLevel === 'LOW') return 'success'
  if (riskLevel === 'WRITE' || riskLevel === 'MEDIUM') return 'warning'
  if (riskLevel === 'HIGH' || riskLevel === 'IRREVERSIBLE') return 'danger'
  return 'info'
}

function findingRiskToneClass(riskLevel?: string) {
  return `is-risk-${findingRiskTagType(riskLevel)}`
}

function addManualGoal() {
  if (goalsLocked.value || !activeManualGoal.value.trim()) return
  activeManualGoalAdded.value = true
}

function runPrimary() {
  if (!props.journey) return
  emit('primary', props.page, props.journey, {
    manualGoal: activeManualGoalAdded.value ? activeManualGoal.value.trim() : '',
    findingIds: activeSelectedFindings.value.map((finding) => finding.id),
  })
}

function beginRetarget() {
  const selection = currentGoalSelection.value
  retargetManualGoal.value = selection.manualGoal
  retargetManualGoalAdded.value = Boolean(selection.manualGoal)
  retargetFindingIds.value = [...selection.findingIds]
  retargetMode.value = true
}

function cancelRetarget() {
  retargetMode.value = false
  retargetManualGoal.value = ''
  retargetManualGoalAdded.value = false
  retargetFindingIds.value = []
}

function runRetarget() {
  if (!props.journey) return
  emit('retarget', props.page, props.journey, {
    manualGoal: activeManualGoalAdded.value ? activeManualGoal.value.trim() : '',
    findingIds: activeSelectedFindings.value.map((finding) => finding.id),
  })
}

function openImplementationWorkspace() {
  activeCapabilityWorkspace.value = 'implementation'
}

function runGoalsPrimaryAction() {
  if (goalsLocked.value) {
    openImplementationWorkspace()
    return
  }
  if (retargetMode.value) {
    runRetarget()
    return
  }
  runPrimary()
}

function openDebugWorkspace() {
  activeCapabilityWorkspace.value = 'debug'
}

function openFoundationWorkspace() {
  activeCapabilityWorkspace.value = 'resources'
}

function openActionWorkspace() {
  activeCapabilityWorkspace.value = 'actions'
}

function openActionDebug(page: ProjectPage, action: ProjectPageAction) {
  emit('debugAction', page, action)
}

function openDiagnostics() {
  activeCapabilityWorkspace.value = 'diagnostics'
  emit('checkReadiness', props.page)
}
</script>

<template>
  <section class="page-access-detail-shell">
    <header class="page-detail-workbench-header">
      <button
        type="button"
        class="page-detail-back"
        aria-label="返回页面列表"
        title="返回页面列表"
        @click="emit('back')"
      >
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <i class="page-detail-title-mark" aria-hidden="true" />
      <div class="page-detail-title">
        <small
          >页面接入 ·
          {{ pageWorkbenchModuleName(page.moduleName, page.moduleKey) }}</small
        >
        <h1>{{ pageWorkbenchPageName(page) }}</h1>
      </div>

      <section
        v-if="journey"
        class="page-detail-header-progress"
        aria-label="页面接入进度"
      >
        <div>
          <strong>{{ milestones[journey.currentStep - 1]?.label }}</strong>
          <b>{{ journey.currentStep }} / 4</b>
        </div>
        <ol aria-hidden="true">
          <li
            v-for="item in milestones"
            :key="item.step"
            :class="milestoneClass(item.step)"
          />
        </ol>
        <el-tag
          :type="journey.status === 'COMPLETE' ? 'success' : 'info'"
          effect="light"
        >
          {{ journey.statusLabel }}
        </el-tag>
      </section>

      <div class="page-detail-header-actions">
        <el-button
          :icon="Clock"
          circle
          aria-label="接入历程"
          title="接入历程"
          @click="emit('history', page)"
        />
        <el-button
          :icon="InfoFilled"
          circle
          aria-label="页面信息"
          title="页面信息"
          @click="emit('pageInfo', page)"
        />
        <el-button
          v-if="businessPageUrl"
          tag="a"
          :href="businessPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          :icon="TopRight"
          circle
          aria-label="查看业务页面"
          title="查看业务页面"
        />
      </div>
    </header>

    <section v-if="journey" class="page-detail-stage-card">
      <nav
        class="page-detail-capability-summary"
        aria-label="页面接入流程"
      >
        <div ref="flowTrackRef" class="page-detail-flow-track">
          <template
            v-for="(node, index) in flowNavNodes"
            :key="node.key"
          >
            <button
              type="button"
              :class="[
                `is-${node.flowState}`,
                {
                  'is-selected': node.selected,
                  'is-guidance-target': implementationNeedsGoalSelection && node.key === 'goals',
                },
              ]"
              :data-flow-node="node.key"
              :aria-pressed="node.selected"
              :aria-current="recommendedFlowNode?.key === node.key ? 'step' : undefined"
              :aria-describedby="implementationNeedsGoalSelection && node.key === 'goals'
                ? 'implementation-goal-guide-title'
                : undefined"
              :title="flowNodeTitle(node)"
              @click="activateFlowNode(node)"
            >
              <span class="page-detail-flow-icon" aria-hidden="true">
                <el-icon v-if="node.flowState === 'complete'"><CircleCheck /></el-icon>
                <el-icon v-else><component :is="node.icon" /></el-icon>
              </span>
              <span class="page-detail-flow-copy">
                <span class="page-detail-flow-title-row">
                  <strong>{{ node.label }}</strong>
                  <span
                    v-if="node.statusText"
                    class="page-detail-flow-status"
                  >{{ node.statusText }}</span>
                </span>
                <small>{{ node.description }}</small>
              </span>
            </button>
            <i
              v-if="index < flowNavNodes.length - 1"
              class="page-detail-flow-connector"
              :class="flowConnectorClass(node, flowNavNodes[index + 1])"
              aria-hidden="true"
            />
          </template>
        </div>
      </nav>

      <aside
        v-if="flowPreviewHint"
        class="page-detail-flow-preview"
        role="status"
      >
        <p>{{ flowPreviewHint.message }}</p>
        <button
          type="button"
          class="page-detail-flow-preview__return"
          @click="activateFlowNode(flowPreviewHint.returnNode)"
        >
          返回{{ flowPreviewHint.returnNode.label }}
        </button>
      </aside>

      <section
        v-if="foundationWorkspaceActive"
        class="page-foundation-workspace"
      >
        <PageWorkbenchResourcePanel
          :page="page"
        />

        <div ref="foundationActionSection" class="page-foundation-action-anchor">
          <PageWorkbenchActionPanel
            :page="page"
            @edit="emit('editPage', page)"
            @debug="openActionDebug"
          />
        </div>
      </section>

      <PageWorkbenchDiagnosticsPanel
        v-else-if="activeCapabilityWorkspace === 'diagnostics'"
        :page="page"
        :readiness="readiness"
        :readiness-loading="readinessLoading"
        @check="emit('checkReadiness', page)"
      />

      <PageWorkbenchDebugPanel
        v-else-if="activeCapabilityWorkspace === 'debug'"
        :page="page"
        :initial-action-id="debugActionId"
        @actions="openActionWorkspace"
        @diagnostics="openDiagnostics"
        @success="emit('debugSuccess', page)"
      />

      <template v-else>
        <div
          v-if="journey.status === 'UNAVAILABLE'"
          class="page-detail-unavailable"
        >
        <el-icon><Warning /></el-icon>
        <div>
          <h2>{{ journey.title }}</h2>
          <p>{{ journey.message }}</p>
          <small>{{ runtimeMessage || '等待相关服务恢复后刷新状态。' }}</small>
        </div>
        <el-button :loading="busy" @click="emit('refresh')">刷新状态</el-button>
      </div>

        <template v-else-if="activeCapabilityWorkspace === 'goals'">
        <header
          class="page-detail-stage-heading"
          :class="{ 'is-retarget': retargetMode, 'is-locked': goalsLocked }"
        >
          <div>
            <h2>{{ goalHeading }}</h2>
            <p v-if="retargetMode">已回填当前活动目标；确认前不会影响正在进行的 AI 实施。</p>
          </div>
          <div class="page-detail-goal-heading-actions">
            <el-tooltip
              v-if="goalsLocked && canAdjustImplementationTargets"
              content="调整接入目标"
              placement="top"
              :show-after="200"
            >
              <el-button
                :icon="EditPen"
                plain
                circle
                type="success"
                aria-label="调整接入目标"
                :disabled="busy"
                @click="beginRetarget"
              />
            </el-tooltip>
            <el-button v-if="retargetMode" :disabled="busy" @click="cancelRetarget">
              取消调整
            </el-button>
          </div>
        </header>
        <section
          class="target-selection-workspace"
          :class="{ 'is-locked': goalsLocked }"
        >
          <div class="target-selection-layout">
            <div
              class="target-source-column"
              :class="{ 'is-locked': goalsLocked }"
            >
              <div
                v-if="goalsLocked"
                class="target-source-locked"
                role="status"
                aria-label="当前活动目标已锁定"
              >
                <span class="goal-lock-state" aria-hidden="true">
                  <el-icon><Lock /></el-icon>
                </span>
                <strong>当前活动目标已锁定</strong>
                <small>如需修改，请点击右上角绿色编辑图标</small>
              </div>
              <template v-else>
              <section class="manual-target-section">
                <header>
                  <span
                    ><el-icon><EditPen /></el-icon
                  ></span>
                  <h3>人工描述</h3>
                </header>
                <article>
                  <el-input
                    v-model="activeManualGoal"
                    type="textarea"
                    :rows="2"
                    placeholder="例如：查询当前用户可见的数据权限，并把结果显示在页面中"
                  />
                  <footer>
                    <el-button
                      :type="activeManualGoalAdded ? 'success' : 'default'"
                      :disabled="!activeManualGoal.trim() || activeManualGoalAdded"
                      @click="addManualGoal"
                    >
                      {{ activeManualGoalAdded ? '已添加' : '添加目标' }}
                    </el-button>
                  </footer>
                </article>
              </section>

              <section class="recommended-target-section">
                <header>
                  <span
                    ><el-icon><MagicStick /></el-icon
                  ></span>
                  <h3>AI 推荐</h3>
                  <el-button
                    link
                    type="primary"
                    :icon="MagicStick"
                    @click="emit('analyze', page)"
                  >
                    补充建议
                  </el-button>
                </header>
                <div
                  v-if="candidateFindings.length"
                  class="recommended-target-list"
                >
                  <article
                    v-for="finding in candidateFindings"
                    :key="finding.id"
                    :class="[
                      findingRiskToneClass(finding.operationRisk),
                      { 'is-selected': isFindingSelected(finding) },
                    ]"
                  >
                    <header>
                      <el-tag
                        :type="findingRiskTagType(finding.operationRisk)"
                        effect="light"
                        round
                        size="small"
                        >{{ pageWorkbenchRiskLabel(finding.operationRisk) }}</el-tag
                      >
                      <small class="recommendation-coverage-tag"
                        >{{ pageWorkbenchHumanText(finding.category, '页面改造建议') }}</small
                      >
                      <button
                        v-if="goalsEditable && !retargetMode"
                        type="button"
                        aria-label="关闭推荐"
                        title="关闭推荐"
                        @click="emit('findingStatus', finding, 'IGNORED')"
                      >
                        <el-icon><Close /></el-icon>
                      </button>
                    </header>
                    <h4>
                      {{ pageWorkbenchHumanText(finding.title, '页面改造建议') }}
                    </h4>
                    <p>
                      {{ pageWorkbenchHumanText(finding.useCase, finding.confirmedFact) }}
                    </p>
                    <footer>
                      <el-button link @click="emit('findingDetail', finding)"
                        >查看依据</el-button
                      >
                      <el-button
                        :type="isFindingSelected(finding) ? 'success' : 'primary'"
                        :icon="isFindingSelected(finding) ? CircleCheck : Plus"
                        plain
                        @click="toggleFinding(finding)"
                      >
                        {{ isFindingSelected(finding) ? '移出目标' : '添加目标' }}
                      </el-button>
                    </footer>
                  </article>
                </div>
                <el-empty
                  v-else
                  :image-size="56"
                  description="还没有有依据的 AI 页面建议"
                >
                  <el-button
                    v-if="goalsEditable"
                    link
                    type="primary"
                    @click="emit('analyze', page)"
                    >让 AI Coding 只读分析</el-button
                  >
                </el-empty>
              </section>
              </template>
            </div>

            <aside class="onboarding-target-panel">
              <header>
                <h3>已选目标</h3>
                <b>{{ selectedTargetCount }}</b>
              </header>
              <div v-if="selectedTargetCount" class="onboarding-target-list">
                <article v-if="activeManualGoalAdded && activeManualGoal.trim()">
                  <span
                    ><strong>人工描述</strong
                    ><small>{{ activeManualGoal.trim() }}</small></span
                  ><button
                    v-if="goalsEditable"
                    type="button"
                    aria-label="移出人工目标"
                    title="移出人工目标"
                    @click="activeManualGoalAdded = false"
                  >
                    <el-icon><Close /></el-icon>
                  </button>
                </article>
                <article
                  v-for="finding in activeSelectedFindings"
                  :key="finding.id"
                  :class="findingRiskToneClass(finding.operationRisk)"
                >
                  <span
                    ><el-tag
                      :type="findingRiskTagType(finding.operationRisk)"
                      effect="light"
                      round
                      size="small"
                      >{{ pageWorkbenchRiskLabel(finding.operationRisk) }}</el-tag
                    ><strong
                      >{{ pageWorkbenchHumanText(finding.title, '页面目标') }}</strong
                    ><small
                      >{{ pageWorkbenchHumanText(finding.useCase, finding.confirmedFact) }}</small
                    ></span
                  ><button
                    v-if="goalsEditable"
                    type="button"
                    aria-label="移出推荐目标"
                    title="移出推荐目标"
                    @click="removeFinding(finding)"
                  >
                    <el-icon><Close /></el-icon>
                  </button>
                </article>
                <article
                  v-for="(item, index) in lockedOrphanGoalItems"
                  :key="`orphan-${item.source}-${item.findingId || index}`"
                >
                  <span
                    ><strong>{{
                      item.source === 'AI_FINDING' ? 'AI 建议' : '任务目标'
                    }}</strong
                    ><small>{{ item.content }}</small></span
                  >
                </article>
              </div>
              <div v-else class="onboarding-target-empty">
                <el-icon><Aim /></el-icon><strong>尚未选择目标</strong
                ><small>{{ goalsLocked ? '当前活动未返回可展示目标' : '从左侧添加' }}</small>
              </div>
              <footer>
                <el-button
                  type="primary"
                  :loading="!goalsLocked && busy"
                  :disabled="!goalsLocked && !canStartImplementation"
                  @click="runGoalsPrimaryAction"
                  >{{
                    goalsLocked
                      ? '查看 AI 实施进度'
                      : (retargetMode ? '确认新目标并重新实施' : '确认并开始接入')
                  }}<el-icon><ArrowRight /></el-icon></el-button
                ><small v-if="!goalsLocked">{{
                  retargetMode
                    ? '确认后会取消当前活动并创建新的 AI 实施任务。'
                    : '默认交给 Codex；其他工具仍可从高级任务入口选择。'
                }}</small>
              </footer>
            </aside>
          </div>
        </section>
        </template>

        <template v-else-if="activeCapabilityWorkspace === 'implementation' && journey.stage === 'AI_IMPLEMENTATION'">
        <header
          v-if="journey.nextAction.code === 'START_WORKFLOW_ENGINEERING'"
          class="page-detail-stage-heading"
        >
          <div>
            <h2>{{ journey.title }}</h2>
            <p>{{ journey.message }}</p>
          </div>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!journey.nextAction.enabled"
            @click="runPrimary"
          >
            {{ journey.nextAction.label }}<el-icon><ArrowRight /></el-icon>
          </el-button>
        </header>
        <section class="journey-stage-workspace implementation-task-workspace">
          <AiCodingTaskDetailPanel
            v-if="task"
            variant="inline"
            :detail="taskDetail || null"
            :loading="taskDetailLoading"
            :busy="busy"
            @refresh="emit('taskRefresh', $event)"
            @answer="(taskId, questionId, answer) => emit('taskAnswer', taskId, questionId, answer)"
            @reissue="emit('taskReissue', $event)"
            @verify="emit('taskVerify', $event)"
            @acceptance="(taskId, passed, message) => emit('taskAcceptance', taskId, passed, message)"
            @cancel="emit('taskCancel', $event)"
          />
          <el-empty v-else description="当前没有可展示的 AI 实施任务" />
        </section>
        </template>

        <template v-else-if="implementationNeedsGoalSelection">
        <section
          class="implementation-goal-onboarding"
          aria-labelledby="implementation-goal-guide-title"
        >
          <div class="implementation-goal-onboarding__pointer" aria-hidden="true">
            <div class="implementation-goal-onboarding__pointer-target">
              <svg viewBox="0 0 72 86" focusable="false">
                <path
                  class="implementation-goal-onboarding__pointer-line"
                  d="M36 80 C28 62 44 46 36 18"
                />
                <path
                  class="implementation-goal-onboarding__pointer-head"
                  d="M24 30 L36 17 L48 30"
                />
              </svg>
              <span><i />点击上方「接入目标」</span>
            </div>
          </div>

          <div class="implementation-goal-onboarding__visual">
            <img :src="pageGoalBeforeAiIllustration" alt="" />
            <div class="implementation-goal-onboarding__copy">
              <h2 id="implementation-goal-guide-title">先选好目标，再交给 AI 实施</h2>
              <p>选择希望这个页面完成的改造，AI Coding 会据此开始工作</p>
            </div>
          </div>
        </section>
        </template>

        <template v-else-if="journey.stage === 'PUBLISH_CONFIRMATION'">
        <header class="page-detail-stage-heading">
          <h2>确认页面操作并上线</h2>
        </header>
        <section class="journey-stage-workspace is-publish">
          <div class="journey-stage-introduction">
            <span
              ><el-icon><Promotion /></el-icon
            ></span>
            <div>
              <small>第三步 · 确认上线</small>
              <h2>{{ journey.title }}</h2>
              <p>{{ journey.message }}</p>
            </div>
          </div>
          <div class="publish-operation-list">
            <article v-for="action in page.actions" :key="action.id">
              <span
                ><strong>{{ pageWorkbenchActionTitle(action) }}</strong
                ><small>{{ action.actionKey }}</small></span
              ><span
                ><el-tag
                  effect="plain"
                  >{{ pageWorkbenchRiskLabel(action.riskLevel) }}</el-tag
                ><small
                  >{{ action.confirmRequired ? '执行前必须确认' : '无需额外确认' }}</small
                ></span
              >
            </article>
          </div>
          <footer>
            <el-button @click="openActionWorkspace"
              >核对页面操作</el-button
            ><el-button
              type="primary"
              :loading="busy"
              :disabled="!journey.nextAction.enabled"
              @click="runPrimary"
              >{{ journey.nextAction.label }}<el-icon><ArrowRight /></el-icon
            ></el-button>
          </footer>
        </section>
        </template>

        <template v-else-if="journey.stage === 'REAL_ACCEPTANCE'">
        <header class="page-detail-stage-heading">
          <h2>真实业务页面验收</h2>
        </header>
        <section class="journey-stage-workspace is-acceptance">
          <div class="journey-stage-introduction">
            <span
              ><el-icon><VideoPlay /></el-icon
            ></span>
            <div>
              <small>第四步 · 真实验收</small>
              <h2>{{ journey.title }}</h2>
              <p>{{ journey.message }}</p>
            </div>
          </div>
          <article class="acceptance-version-card">
            <span
              ><strong>{{ workflow?.workflowName || '当前页面助手' }}</strong
              ><small
                >{{ workflow?.workflowVersion || journey.workflowVersion || '版本暂不可用' }}</small
              ></span
            ><span
              ><strong>精确版本锁定</strong
              ><small
                >{{ workflow ? `Workflow ${workflow.workflowId} · Version ID ${workflow.workflowVersionId}` : '等待运行服务返回发布版本' }}</small
              ></span
            >
          </article>
          <footer>
            <el-button @click="emit('history', page)">查看已有材料</el-button
            ><el-button
              type="primary"
              :loading="busy"
              :disabled="!journey.nextAction.enabled"
              @click="runPrimary"
              >{{ journey.nextAction.label }}<el-icon><ArrowRight /></el-icon
            ></el-button>
          </footer>
        </section>
        </template>

        <template v-else>
        <header class="page-detail-stage-heading">
          <h2>页面能力已上线</h2>
        </header>
        <section class="journey-stage-workspace is-complete">
          <div class="journey-stage-introduction">
            <span
              ><el-icon><CircleCheck /></el-icon
            ></span>
            <div>
              <small>页面能力已上线</small>
              <h2>{{ journey.title }}</h2>
              <p>{{ journey.message }}</p>
            </div>
          </div>
          <article class="completed-evidence-card">
            <el-icon><CircleCheck /></el-icon
            ><span
              ><strong>真实业务验收已经通过</strong
              ><small
                >{{ workflow ? `${workflow.workflowName} ${workflow.workflowVersion} · 页面操作与浏览器证据已归档` : '已完成精确版本验收' }}</small
              ></span
            >
          </article>
          <dl class="completed-capability-metrics">
            <div>
              <dt>在线页面操作</dt>
              <dd>{{ page.actions.length }} 个</dd>
            </div>
            <div>
              <dt>最近验收成功</dt>
              <dd>已通过</dd>
            </div>
            <div>
              <dt>RunOps Trace</dt>
              <dd>{{ workflow?.latestTraceId ? '可回放' : '尚无线上调用' }}</dd>
            </div>
          </dl>
          <footer>
            <el-button
              v-if="workflow"
              type="primary"
              @click="emit('online', workflow)"
              >查看在线能力<el-icon><ArrowRight /></el-icon
            ></el-button>
          </footer>
        </section>
        </template>
      </template>
    </section>

    <section v-else class="page-detail-unavailable is-no-journey">
      <el-icon><Warning /></el-icon>
      <div>
        <h2>页面接入状态暂不可用</h2>
        <p>页面资料仍可查看，但系统不会根据缺失数据推断接入进度。</p>
      </div>
    </section>
  </section>
</template>
