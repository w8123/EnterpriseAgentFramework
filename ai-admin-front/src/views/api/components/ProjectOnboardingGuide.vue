<template>
  <section v-if="steps.length" class="onboarding" :class="{ 'is-complete': progress.complete }" aria-labelledby="onboarding-title">
    <header class="onboarding__header">
      <button type="button" class="onboarding__toggle" :aria-expanded="open" @click="toggle">
        <el-icon class="onboarding__chevron" :class="{ 'is-open': open }"><ArrowRight /></el-icon>
        <h2 id="onboarding-title">接入进度</h2>
        <span class="onboarding__count">{{ progress.complete ? '已完成' : `${progress.done} / ${progress.total}` }}</span>
      </button>
      <p v-if="!open && next" class="onboarding__next">
        下一步：{{ next.title }}
        <el-button v-if="next.action" link type="primary" :loading="busy === next.key" @click="act(next)">{{ next.action.label }}</el-button>
      </p>
      <p v-else-if="!open && progress.complete" class="onboarding__next">员工和助手已经可以使用这个系统。</p>
      <el-button v-if="open" link :loading="loading" @click="load">刷新</el-button>
    </header>
    <ol v-if="open" class="onboarding__steps">
      <li v-for="(step, index) in steps" :key="step.key" class="onboarding__step" :class="[`is-${step.state}`, { 'is-next': next?.key === step.key }]">
        <span class="onboarding__mark" aria-hidden="true">
          <el-icon v-if="step.state === 'done'"><Check /></el-icon>
          <template v-else>{{ index + 1 }}</template>
        </span>
        <div class="onboarding__body">
          <strong>{{ step.title }}<small v-if="step.state === 'optional'">可选</small><small v-else-if="step.state === 'done'">已完成</small></strong>
          <p>{{ step.detail }}</p>
        </div>
        <el-button v-if="step.action && (canWrite || step.state === 'done')" :type="next?.key === step.key ? 'primary' : 'default'"
          size="small" :loading="busy === step.key" @click="act(step)">{{ step.action.label }}</el-button>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Check } from '@element-plus/icons-vue'
import { getHttpApiProjectDefaultConnection, listHttpApis } from '@/api/httpApi'
import { getMcpEmployeeAccess, listMcpPublications } from '@/api/mcp'
import { listBusinessSsoProviders } from '@/api/platformAuth'
import { applyPlatformEvalAccount, getProjectEvalOverview } from '@/api/projectEval'
import { platformSessionUser } from '@/auth/platformSession'
import { hasPlatformPermission, PLATFORM_PERMISSION_ADMIN } from '@/auth/platformAccess'
import { nextOnboardingStep, onboardingProgress, onboardingSteps, type OnboardingFacts, type OnboardingStep } from '../projectOnboarding'

const props = defineProps<{
  projectId: number
  projectCode: string
  canWrite: boolean
  /** 外面每次改动（加接口、存鉴权、发布）后加一，这里就重新读一遍 */
  refreshKey?: number
}>()
const emit = defineEmits<{ add: []; accept: []; auth: []; publish: [] }>()

const router = useRouter()
const facts = ref<OnboardingFacts | null>(null)
const loading = ref(false)
const busy = ref('')
const open = ref(true)
let userToggled = false
let sequence = 0

const isPlatformAdmin = computed(() => hasPlatformPermission(platformSessionUser.value?.permissions, PLATFORM_PERMISSION_ADMIN))
const steps = computed<OnboardingStep[]>(() => (facts.value ? onboardingSteps(facts.value) : []))
const progress = computed(() => onboardingProgress(steps.value))
const next = computed(() => nextOnboardingStep(steps.value))

function toggle() {
  userToggled = true
  open.value = !open.value
}

/** 每一项各读各的：读不到的当作还没做（登录入口读不到当作看不到），不让一处失败挡住整条进度。 */
async function load() {
  const mine = ++sequence
  loading.value = true
  // 自己数全项目的接口，不受页面上筛选条件的影响；鉴权配置按已接纳的任一接口定位。
  const [all, accepted] = await Promise.all([apiPage(), apiPage('ACCEPTED')])
  const [auth, tools, login, evaluation] = await Promise.all([
    authStatus(accepted.firstId), employeeToolCount(), loginEntry(), evalState(),
  ])
  if (mine !== sequence) return
  facts.value = {
    apiTotal: all.total, acceptedTotal: accepted.total, authStatus: auth, employeeToolCount: tools,
    loginEntry: login, evalAccount: evaluation.account, lastPassRate: evaluation.passRate,
  }
  loading.value = false
  // 接入完成后默认收起，不占地方；用户自己展开或收起过就听用户的。
  if (!userToggled) open.value = !onboardingProgress(onboardingSteps(facts.value)).complete
}

async function apiPage(sourceStatus?: string) {
  try {
    const { data } = await listHttpApis({ projectId: props.projectId, sourceStatus, current: 1, size: 1 })
    return { total: Number(data.total) || 0, firstId: data.records?.[0]?.id ?? null }
  } catch { return { total: 0, firstId: null as number | null } }
}

async function authStatus(apiId: number | null) {
  if (!apiId) return null
  try {
    const { data } = await getHttpApiProjectDefaultConnection(apiId)
    return data.status || null
  } catch { return null }
}

async function employeeToolCount() {
  try {
    const { data: page } = await listMcpPublications({ limit: 100 })
    let count: number | null = null
    for (const publication of page.items || []) {
      if (publication.state !== 'PUBLISHED') continue
      try {
        const { data: access } = await getMcpEmployeeAccess(publication.id)
        if (access.projectCode !== props.projectCode || !access.enabled) continue
        count = (count ?? 0) + Math.max(0, (access.toolCount ?? 0) - (access.hiddenToolCount ?? 0))
      } catch { /* 没有权限看的发布不算 */ }
    }
    return count
  } catch { return null }
}

async function loginEntry() {
  if (!isPlatformAdmin.value) return null
  try {
    const { data } = await listBusinessSsoProviders()
    return (data.providers || []).some(provider => provider.active && provider.projectCode === props.projectCode)
  } catch { return null }
}

async function evalState() {
  try {
    const { data } = await getProjectEvalOverview(props.projectCode)
    const summary = (data.projects || []).find(project => project.setting.projectCode === props.projectCode)
    const finished = (summary?.runs || []).find(run => typeof run.passRate === 'number')
    return { account: Boolean(summary?.setting.evalUserId), passRate: finished ? Number(finished.passRate) : null }
  } catch { return { account: false, passRate: null } }
}

async function act(step: OnboardingStep) {
  const kind = step.action?.kind
  if (!kind || busy.value) return
  if (kind === 'add') emit('add')
  else if (kind === 'accept') emit('accept')
  else if (kind === 'auth') emit('auth')
  else if (kind === 'publish') emit('publish')
  else if (kind === 'login') void router.push({ path: '/settings/auth-providers' })
  else if (kind === 'eval-open') void router.push({ path: '/usage', query: { tab: 'eval', system: props.projectCode } })
  else if (kind === 'eval-account') {
    busy.value = step.key
    try {
      await applyPlatformEvalAccount(props.projectCode)
      ElMessage.success('已配上平台评测账号，它只能调用只读能力')
      await load()
    } catch (error) {
      const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      ElMessage.error(message ? `没能配上平台评测账号：${message}` : '没能配上平台评测账号，请稍后再试')
    } finally { busy.value = '' }
  }
}

watch(() => [props.projectId, props.projectCode, props.refreshKey], () => { void load() }, { immediate: true })
watch(() => props.projectId, () => { userToggled = false })
</script>

<style scoped>
.onboarding { display: grid; gap: 10px; padding: 12px 14px; border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.2)); border-radius: 12px; background: var(--surface-raised, rgba(255, 255, 255, 0.5)); }
.onboarding__header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.onboarding__toggle { display: inline-flex; align-items: center; gap: 8px; padding: 0; border: 0; background: transparent; color: var(--text-primary); font: inherit; cursor: pointer; }
.onboarding__toggle h2 { margin: 0; font-size: 14px; font-weight: 650; }
.onboarding__chevron { transition: transform 0.15s ease; }
.onboarding__chevron.is-open { transform: rotate(90deg); }
.onboarding__count { padding: 1px 8px; border-radius: 999px; background: var(--surface-hover, rgba(127, 127, 127, 0.12)); color: var(--text-secondary); font-size: 12px; }
.onboarding.is-complete .onboarding__count { color: var(--el-color-success); }
.onboarding__next { display: inline-flex; align-items: center; gap: 6px; margin: 0; color: var(--text-secondary); font-size: 13px; }
.onboarding__header > .el-button { margin-left: auto; }
.onboarding__steps { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.onboarding__step { display: flex; align-items: center; gap: 12px; padding: 8px 10px; border-radius: 10px; }
.onboarding__step.is-next { background: var(--surface-hover, rgba(127, 127, 127, 0.08)); }
.onboarding__mark { display: inline-flex; flex: none; align-items: center; justify-content: center; width: 24px; height: 24px; border: 1px solid var(--border-strong, rgba(127, 127, 127, 0.35)); border-radius: 50%; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.onboarding__step.is-done .onboarding__mark { border-color: var(--el-color-success); background: var(--el-color-success); color: #fff; }
.onboarding__step.is-blocked { opacity: 0.6; }
.onboarding__body { flex: 1; min-width: 0; }
.onboarding__body strong { display: flex; align-items: center; gap: 8px; color: var(--text-primary); font-size: 13px; font-weight: 650; }
.onboarding__body small { color: var(--text-muted); font-size: 12px; font-weight: 400; }
.onboarding__step.is-done .onboarding__body small { color: var(--el-color-success); }
.onboarding__body p { margin: 2px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
@media (max-width: 640px) { .onboarding__step { flex-wrap: wrap; } }
</style>
