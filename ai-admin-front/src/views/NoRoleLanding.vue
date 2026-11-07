<template>
  <WorkbenchPage class="my-home-page">
    <div class="my-home-content">
      <PageHeader title="我的工作台" :description="`${displayName}，从熟悉的系统开始，让 AI 帮你把事情办好。`" presentation="plain" action-presentation="inline" :artwork="false" compact>
        <template #actions>
          <LiquidGlassButton source="wallpaper" :disabled="busy || loading" @click="connectionHelp = true"><Link aria-hidden="true" />连接系统</LiquidGlassButton>
        </template>
      </PageHeader>
      <section class="my-systems" aria-labelledby="my-systems-title" :aria-busy="loading">
        <div class="my-section-heading">
          <h2 id="my-systems-title">我的业务系统 <span v-if="systems.length">{{ systems.length }}</span></h2>
          <LiquidGlassButton v-if="systems.length > 6" link :disabled="busy" @click="allSystemsOpen = true">全部系统 <ArrowRight aria-hidden="true" /></LiquidGlassButton>
        </div>
        <div v-if="loading" class="my-home-status" role="status"><el-icon class="is-loading"><Loading /></el-icon>正在加载系统和业务连接…</div>
        <div v-else-if="loadError" class="my-home-notice" role="alert">{{ loadError }}<LiquidGlassButton link :disabled="busy" @click="load">重试</LiquidGlassButton></div>
        <div v-if="!loading && visibleSystems.length" class="my-system-grid">
          <WorkbenchSystemCard v-for="system in visibleSystems" :key="system.code" :system="system" :selected="selectedCode === system.code" :pinned="pinned.includes(system.code)" :disabled="busy" @select="chooseSystem" @pin="togglePinned" />
        </div>
        <p v-else-if="!loading && !loadError" class="my-home-muted">还没有连接业务系统。从业务系统的 AI 面板进入个人页，即可添加到这里。</p>
        <p v-if="connectionNotice" class="my-home-notice" role="status">{{ connectionNotice }}<LiquidGlassButton v-if="selected?.connection?.expired" link @click="connectionHelp = true">重新连接</LiquidGlassButton><LiquidGlassButton v-else link :disabled="busy || loading" @click="load">重新检查</LiquidGlassButton></p>
      </section>
      <LiquidGlassCard v-show="false" source="wallpaper" class="my-assistant-surface" aria-label="AI 业务助手">
        <div class="my-assistant-context">
          <span class="my-assistant-label"><ChatDotRound aria-hidden="true" /> AI 助手</span>
          <div v-if="systems.length" class="my-assistant-scope">
            <span>业务系统</span>
            <LiquidGlassSelect :model-value="selectedCode" :disabled="busy || loading" aria-label="选择对话的业务系统" @update:model-value="chooseSystem(String($event))">
              <el-option v-for="system in systems" :key="system.code" :label="system.name" :value="system.code" />
            </LiquidGlassSelect>
          </div>
        </div>
        <p v-if="busy" class="my-assistant-busy" role="status">正在处理当前系统的任务，结束后可以切换系统。</p>
        <template v-for="connection in connections" :key="`${connection.profile.projectCode}:${connection.profile.account}`">
          <WorkbenchAssistant v-if="visited.includes(connection.profile.projectCode)" v-show="selectedCode === connection.profile.projectCode" :system-name="connection.profile.systemName" :get-token="tokenProvider(connection.token)" :suggestions="suggestions" :expired="connection.expired" @busy="busy = $event" @completed="loadActivity" @expired="expireConnection(connection.token)" />
        </template>
        <div v-if="!selected?.connection" class="my-assistant-unconnected">
          <ChatDotRound class="my-assistant-unconnected__icon" aria-hidden="true" />
          <h2>今天要办什么？</h2>
          <p>{{ selected ? `连接${selected.name}后，就能在这里直接对话办事。` : '连接你的业务系统，把查询和办事交给 AI。' }}</p>
          <LiquidGlassButton type="primary" :disabled="loading" @click="connectionHelp = true">{{ selected ? '连接这个系统' : '连接业务系统' }} <ArrowRight aria-hidden="true" /></LiquidGlassButton>
          <small>沿用你在业务系统里的身份和权限</small>
        </div>
        <p v-if="capabilitiesError" class="my-home-muted my-assistant-footnote">{{ capabilitiesError }}</p>
      </LiquidGlassCard>
      <LiquidGlassCard v-show="false" source="wallpaper" class="my-recent" aria-labelledby="my-recent-title" :aria-busy="activityLoading">
        <div class="my-section-heading">
          <h2 id="my-recent-title">最近办的事</h2>
          <LiquidGlassButton v-if="calls.length || approvals.length" link @click="activityOpen = true">查看全部 <ArrowRight aria-hidden="true" /></LiquidGlassButton>
        </div>
        <div v-if="activityLoading" class="my-home-status" role="status">正在读取办事记录…</div>
        <p v-else-if="!selected?.connection || selected.connection.expired" class="my-home-muted">连接系统后，可以查看当前业务身份的 AI 工具调用和审批记录。</p>
        <template v-else>
          <p v-if="activityError" class="my-home-notice" role="alert">{{ activityError }}<LiquidGlassButton link @click="loadActivity">重试</LiquidGlassButton></p>
          <button v-if="pendingApprovals.length" type="button" class="my-pending" @click="activityOpen = true"><Clock aria-hidden="true" /><span>{{ pendingApprovals.length }} 项操作等待审批</span><ArrowRight aria-hidden="true" /></button>
          <ul v-if="calls.length" class="my-activity-list">
            <li v-for="call in calls.slice(0, 3)" :key="call.id">
              <span class="my-activity-icon" :class="{ 'is-failed': !call.success }"><CircleCheck v-if="call.success" aria-hidden="true" /><Warning v-else aria-hidden="true" /></span>
              <div class="my-activity-main"><strong>{{ callLabel(call) }}</strong><small>{{ call.businessSystem || selected.name }} · {{ formatTime(call.createdAt) }}</small></div>
              <span class="my-activity-state" :class="{ 'is-failed': !call.success }">{{ call.success ? '已完成' : '未成功' }}</span>
            </li>
          </ul>
          <p v-else-if="!activityError && !pendingApprovals.length" class="my-home-muted">还没有办事记录。先从上方说说你想办的事。</p>
        </template>
      </LiquidGlassCard>
      <nav v-show="false" class="my-home-shortcuts" aria-label="个人设置">
        <RouterLink v-if="hasPlatformRole" to="/me/ai-connections"><Monitor aria-hidden="true" />AI 工具接入<ArrowRight aria-hidden="true" /></RouterLink>
        <RouterLink to="/me/memories"><Collection aria-hidden="true" />我的记忆<ArrowRight aria-hidden="true" /></RouterLink>
      </nav>
    </div>
    <AppDialog v-model="connectionHelp" title="连接业务系统" width="min(520px, calc(100vw - 32px))">
      <p class="my-dialog-intro">在原业务系统确认你的身份后，就能回到这里对话办事。</p>
      <ol class="my-connection-steps">
        <li>打开{{ selected?.name || '你要使用的业务系统' }}，登录你的账号。</li>
        <li>在 AI 助手面板中，选择「在我的 AI 工具中使用」，再点「在浏览器里管理」。</li>
        <li>在个人页点击「在工作台中使用」。</li>
      </ol>
      <p class="my-home-muted">连接保留在当前浏览器标签页，业务登录过期后需要重新连接。这里的对话可以查询和办理已开放的业务；填写当前页面表单等操作，请在原业务系统的 AI 面板中完成。</p>
      <template #footer>
        <LiquidGlassButton @click="connectionHelp = false">知道了</LiquidGlassButton>
        <a v-if="selected?.url" class="my-open-link" :href="selected.url" target="_blank" rel="noopener noreferrer">打开{{ selected.name }} <TopRight aria-hidden="true" /></a>
      </template>
    </AppDialog>
    <AppDialog v-model="allSystemsOpen" title="全部业务系统" width="min(760px, calc(100vw - 32px))" @closed="systemSearch = ''">
      <LiquidGlassInput v-model="systemSearch" placeholder="搜索系统名称" aria-label="搜索业务系统" clearable />
      <div class="my-system-grid my-system-grid--all">
        <WorkbenchSystemCard v-for="system in filteredSystems" :key="system.code" :system="system" :selected="selectedCode === system.code" :pinned="pinned.includes(system.code)" :disabled="busy" @select="chooseSystem" @pin="togglePinned" />
      </div>
      <p v-if="!filteredSystems.length" class="my-home-muted">没有找到匹配的系统，试试其他名称。</p>
    </AppDialog>
    <AppDrawer v-model="activityOpen" title="最近办的事" size="min(620px, 100vw)">
      <p class="my-home-muted">{{ selected?.name }} · 当前业务身份的最近 50 条 AI 工具调用，以及已提交的审批。</p>
      <h3 v-if="approvals.length">操作审批</h3>
      <ul class="my-activity-list">
        <li v-for="approval in approvals" :key="approval.approvalId"><div class="my-activity-main"><strong>{{ toolLabels.get(approval.tool) || approval.tool }}</strong><small>{{ approval.businessSystem }}<template v-if="approval.comment"> · {{ approval.comment }}</template></small></div><span>{{ approvalLabel(approval.approvalStatus) }}</span></li>
      </ul>
      <h3 v-if="calls.length">AI 工具调用</h3>
      <ul class="my-activity-list">
        <li v-for="call in calls" :key="call.id"><div class="my-activity-main"><strong>{{ callLabel(call) }}</strong><small>{{ call.businessSystem }} · {{ formatTime(call.createdAt) }}</small><small v-if="call.errorCategory">{{ call.errorCategory }}</small></div><span>{{ call.success ? '已完成' : '未成功' }}</span></li>
      </ul>
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, ChatDotRound, CircleCheck, Clock, Collection, Link, Loading, Monitor, TopRight, Warning } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassInput, LiquidGlassSelect } from '@/components/common/liquid-glass'
import { platformSessionUser } from '@/auth/platformSession'
import type { EmployeeCall } from '@/api/employeePortal'
import WorkbenchSystemCard from './me/components/WorkbenchSystemCard.vue'
import WorkbenchAssistant from './me/components/WorkbenchAssistant.vue'
import { useMyWorkbench } from './me/useMyWorkbench'

const { systems, selected, selectedCode, visibleSystems, pinned, loading, loadError, connectionNotice, busy,
  suggestions, calls, approvals, pendingApprovals, toolLabels, activityLoading, activityError, capabilitiesError,
  connections, load, loadActivity, selectSystem, togglePinned, expireConnection } = useMyWorkbench()
const displayName = computed(() => platformSessionUser.value?.displayName || platformSessionUser.value?.username || '你好')
const hasPlatformRole = computed(() => (platformSessionUser.value?.permissions ?? []).length > 0)
const connectionHelp = ref(false)
const allSystemsOpen = ref(false)
const activityOpen = ref(false)
const systemSearch = ref('')
const visited = ref<string[]>([])
const filteredSystems = computed(() => systems.value.filter(system => system.name.toLocaleLowerCase('zh-CN').includes(systemSearch.value.trim().toLocaleLowerCase('zh-CN'))))
watch(selectedCode, code => { if (code && !visited.value.includes(code)) visited.value.push(code) }, { immediate: true })
watch(() => platformSessionUser.value?.userId, () => { visited.value = []; activityOpen.value = false })
function chooseSystem(code: string) {
  if (busy.value || loading.value) return
  selectSystem(code)
  allSystemsOpen.value = false
  if (!selected.value?.connection || selected.value.connection.expired) connectionHelp.value = true
}
function callLabel(call: EmployeeCall) { return (call.toolName && toolLabels.value.get(call.toolName)) || call.toolName || '业务操作' }
function tokenProvider(token: string) { return () => Promise.resolve(token) }
function formatTime(value?: string | null) {
  if (!value) return '时间未记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未记录'
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}
function approvalLabel(status: string) {
  return ({ PENDING: '等待审批', EXECUTING: '执行中', SUCCEEDED: '已完成', FAILED: '执行失败', REJECTED: '已驳回', EXPIRED: '已过期' } as Record<string, string>)[status] || status
}
</script>

<style scoped lang="scss">
.my-home-page { min-height: 100%; }
.my-home-content { width: min(1080px, 100%); align-self: center; display: flex; flex-direction: column; gap: 24px; padding-block: 6px 20px; }
.my-home-content > :deep(.app-page-header) { padding-inline: 0; }
.my-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; min-height: 28px; }
.my-section-heading h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 14px; font-weight: 650; color: var(--text-primary); }
.my-section-heading h2 span { color: var(--text-muted); font-size: 12px; font-weight: 450; }
.my-system-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.my-system-grid--all { margin-top: 16px; grid-template-columns: repeat(4, minmax(0, 1fr)); max-height: 52vh; overflow: auto; padding: 2px; }
.my-home-status { display: flex; align-items: center; gap: 8px; min-height: 80px; color: var(--text-muted); font-size: 13px; }
.my-home-notice { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 10px 0 0; font-size: 12px; line-height: 1.6; color: var(--status-warning); }
.my-home-muted { margin: 8px 0; font-size: 13px; line-height: 1.8; color: var(--text-secondary); }
.my-assistant-surface { padding: 0; overflow: hidden; }
.my-assistant-context { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 14px 20px; border-bottom: 1px solid var(--border-divider); }
.my-assistant-label, .my-assistant-scope { display: flex; align-items: center; gap: 8px; color: var(--text-secondary); font-size: 12px; }
.my-assistant-label { font-weight: 600; }
.my-assistant-label svg { width: 16px; height: 16px; color: var(--brand-on-surface); }
.my-assistant-scope :deep(.liquid-glass-control) { width: 180px; }
.my-assistant-busy { margin: 0; padding: 9px 20px; color: var(--status-info); font-size: 12px; background: var(--surface-solid-control); }
.my-assistant-unconnected { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 16px; padding: 44px 24px; }
.my-assistant-unconnected__icon { width: 38px; height: 38px; color: var(--brand-on-surface); }
.my-assistant-unconnected h2 { margin: 0; font-size: clamp(24px, 2.6vw, 34px); font-weight: 650; letter-spacing: -0.025em; }
.my-assistant-unconnected p { margin: 0 0 5px; color: var(--text-secondary); font-size: 14px; line-height: 1.7; }
.my-assistant-unconnected small { color: var(--text-muted); font-size: 12px; }
.my-assistant-footnote { padding: 0 24px 14px; font-size: 12px; }
.my-recent { padding: 20px 24px; }
.my-recent .my-home-status { min-height: 70px; }
.my-activity-list { padding: 0; margin: 0; list-style: none; }
.my-activity-list li { display: flex; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--border-divider); font-size: 12px; color: var(--text-secondary); }
.my-activity-list li:last-child { border-bottom: 0; }
.my-activity-icon { display: flex; color: var(--status-success); }
.my-activity-icon svg { width: 20px; height: 20px; }
.my-activity-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; overflow-wrap: anywhere; }
.my-activity-main strong { font-size: 13px; font-weight: 550; color: var(--text-primary); }
.my-activity-main small { color: var(--text-muted); line-height: 1.6; }
.my-activity-state { flex: 0 0 auto; color: var(--text-muted); }
.is-failed { color: var(--status-danger); }
.my-pending { display: flex; align-items: center; gap: 9px; width: 100%; padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-solid-control); color: var(--status-warning); cursor: pointer; font: inherit; font-size: 12px; }
.my-pending span { flex: 1; text-align: left; }
.my-pending:hover { background: var(--surface-hover); }
.my-pending:active { background: var(--surface-pressed); }
.my-pending:focus-visible, .my-open-link:focus-visible, .my-home-shortcuts a:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.my-home-shortcuts { display: flex; justify-content: center; flex-wrap: wrap; gap: 24px; }
.my-home-shortcuts a { display: inline-flex; align-items: center; gap: 7px; padding-block: 4px; color: var(--text-secondary); font-size: 12px; text-decoration: none; cursor: pointer; }
.my-home-shortcuts a:hover { color: var(--brand-on-surface); }
.my-home-shortcuts a:active { color: var(--text-primary); }
.my-home-shortcuts svg, .my-open-link svg, .my-pending svg { width: 14px; height: 14px; flex: 0 0 auto; }
.my-dialog-intro { margin: 0; line-height: 1.8; color: var(--text-primary); }
.my-connection-steps { margin: 20px 0; padding-left: 20px; color: var(--text-secondary); line-height: 1.8; }
.my-connection-steps li { padding-left: 4px; margin-bottom: 10px; }
.my-open-link { display: inline-flex; align-items: center; gap: 6px; margin-left: 12px; color: var(--brand-on-surface); text-decoration: none; font-size: 14px; cursor: pointer; }
.my-open-link:hover { text-decoration: underline; }
.my-open-link:active { color: var(--text-primary); }
@media (max-width: 900px) { .my-system-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 540px) {
  .my-home-content { gap: 20px; }
  .my-system-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .my-assistant-context { padding: 12px; }
  .my-assistant-scope { width: 100%; }
  .my-assistant-scope :deep(.liquid-glass-control) { flex: 1; width: auto; }
  .my-recent { padding: 16px; }
}
</style>
