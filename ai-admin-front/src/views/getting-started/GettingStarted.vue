<template>
  <WorkbenchPage class="getting-started">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="开始使用"
      description="把模型、业务系统和员工的 AI 工具连起来。"
    >
      <template #actions>
        <LiquidGlassButton :loading="loading" @click="reload">
          <el-icon v-if="!loading" aria-hidden="true"><Refresh /></el-icon>
          <span>刷新进度</span>
        </LiquidGlassButton>
      </template>
    </PageHeader>

    <div class="gs-layout">
      <div class="gs-main">
        <LiquidGlassCard as="section" source="wallpaper" class="gs-setup" aria-labelledby="gs-setup-title" :aria-busy="loading">
          <header class="gs-route-head">
            <span>你的接入路线</span>
            <p class="gs-progress" role="status" aria-live="polite" aria-atomic="true">
              <template v-if="loaded"><strong>{{ progress.doneCount }}</strong><span>/ {{ progress.total }} 已完成</span></template>
              <template v-else>正在检查…</template>
            </p>
          </header>

          <ol class="gs-steps" aria-label="设置步骤">
            <li
              v-for="step in progress.steps"
              :key="step.key"
              class="gs-step"
              :class="loaded ? `is-${step.state}` : 'is-loading'"
              :aria-current="loaded && step.state === 'next' ? 'step' : undefined"
              :data-testid="`gs-step-${step.key}`"
            >
              <component
                :is="loaded ? RouterLink : 'div'"
                :to="loaded ? step.actionPath : undefined"
                class="gs-step__link"
                :aria-label="`${step.order}，${step.title}，${loaded ? STATE_LABELS[step.state] : '检查中'}${step.summary ? `，${step.summary}` : ''}`"
                :title="step.description"
              >
                <span class="gs-step__number" aria-hidden="true">
                  <el-icon v-if="loaded && step.state === 'done'"><Check /></el-icon>
                  <template v-else>{{ step.order }}</template>
                </span>
                <span class="gs-step__copy">
                  <strong>{{ STEP_NAMES[step.key] }}</strong>
                  <small :class="{ 'gs-step__summary': loaded && step.state === 'done', 'gs-step__unavailable': loaded && step.state === 'unknown' }">
                    {{ loaded ? STATE_LABELS[step.state] : '检查中' }}
                  </small>
                </span>
              </component>
            </li>
          </ol>

          <section class="gs-focus" :class="{ 'is-complete': loaded && progress.allDone }">
            <div class="gs-focus__copy">
              <p class="gs-focus__eyebrow">
                <i aria-hidden="true" />
                {{ !loaded ? '接入准备' : progress.allDone ? '准备就绪' : progress.nextStep ? `下一步 · 第 ${progress.nextStep.order} 步` : '需要确认' }}
              </p>
              <h2 id="gs-setup-title">{{ focusTitle }}</h2>
              <p class="gs-focus__description">{{ focusDescription }}</p>
              <ul v-if="loaded && progress.allDone" class="gs-completed" aria-label="已完成的设置">
                <li v-for="step in progress.steps" :key="step.key"><el-icon aria-hidden="true"><Check /></el-icon>{{ step.summary }}</li>
              </ul>
              <div class="gs-focus__actions">
                <LiquidGlassButton
                  v-if="loaded && progress.nextStep"
                  type="primary"
                  size="large"
                  data-testid="gs-continue"
                  @click="go(progress.nextStep.actionPath)"
                >{{ progress.nextStep.actionLabel }}<el-icon aria-hidden="true"><ArrowRight /></el-icon></LiquidGlassButton>
                <LiquidGlassButton v-else-if="loaded && progress.allDone" type="primary" size="large" @click="go('/dashboard')">查看运营概览<el-icon aria-hidden="true"><ArrowRight /></el-icon></LiquidGlassButton>
                <LiquidGlassButton v-else :loading="loading" type="primary" size="large" @click="reload">{{ loaded ? '重新检查进度' : '正在检查' }}</LiquidGlassButton>
                <span v-if="loaded && progress.nextStep" class="gs-focus__hint">{{ STEP_HINTS[progress.nextStep.key] }}</span>
              </div>
            </div>
            <GettingStartedVisual class="gs-focus__visual" :stage="visualStage" />
          </section>
        </LiquidGlassCard>

        <div class="gs-principles" aria-label="调用保障">
          <div><el-icon aria-hidden="true"><User /></el-icon><span>员工本人的身份</span></div>
          <div><el-icon aria-hidden="true"><Lock /></el-icon><span>写操作先审批</span></div>
          <div><el-icon aria-hidden="true"><DocumentChecked /></el-icon><span>每次调用有记录</span></div>
        </div>
      </div>

      <SystemCheckPanel class="gs-check" />
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ArrowRight, Check, DocumentChecked, Lock, Refresh, User } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassCard } from '@/components/common/liquid-glass'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import GettingStartedVisual from './GettingStartedVisual.vue'
import SystemCheckPanel from './SystemCheckPanel.vue'
import {
  buildGettingStartedProgress,
  loadGettingStartedFacts,
  type GettingStartedFacts,
  type GettingStartedStepKey,
  type GettingStartedStepState,
} from './gettingStarted'

const STATE_LABELS: Record<GettingStartedStepState, string> = {
  done: '已完成',
  next: '现在开始',
  todo: '待完成',
  unknown: '暂未确认',
}
const STEP_NAMES: Record<GettingStartedStepKey, string> = {
  model: '连接模型',
  system: '接入系统',
  publish: '发布工具集',
  employee: '员工连接',
}
const STEP_HINTS: Record<GettingStartedStepKey, string> = {
  model: '准备一个模型厂商的 API Key',
  system: '支持 Java SDK 与 OpenAPI 接入',
  publish: '选择允许 AI 调用的业务能力',
  employee: '员工从业务系统创建个人连接',
}

const router = useRouter()
const loading = ref(false)
const loaded = ref(false)
const facts = ref<GettingStartedFacts>({ models: [], projects: [], publications: [], activeEmployeeConnections: 0 })
const progress = computed(() => buildGettingStartedProgress(facts.value))
const focusTitle = computed(() => {
  if (!loaded.value) return '正在确认接入进度'
  if (progress.value.allDone) return '一切就绪，开始使用吧'
  return progress.value.nextStep?.title ?? '还有一些信息待确认'
})
const focusDescription = computed(() => {
  if (!loaded.value) return '正在读取模型、业务系统、工具集和员工连接的状态。'
  if (progress.value.allDone) return '四项设置已完成，可以前往概览查看使用情况。'
  return progress.value.nextStep?.description ?? '部分接入信息暂时无法获取。请刷新重试，也可以从上方进入对应页面检查。'
})
const visualStage = computed<GettingStartedStepKey>(() => progress.value.allDone ? 'employee' : progress.value.nextStep?.key ?? 'model')

async function reload() {
  if (loading.value) return
  loading.value = true
  try {
    facts.value = await loadGettingStartedFacts()
    loaded.value = true
  } finally {
    loading.value = false
  }
}

function go(path: string) {
  void router.push(path)
}

onMounted(reload)
</script>

<style scoped lang="scss">
.gs-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  align-items: start;
  gap: 20px;
}

.gs-main { display: grid; min-width: 0; gap: 20px; }
.gs-setup { padding: 0; }

.gs-route-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 24px 28px 12px;
  color: var(--text-muted);
  font-size: 12px;
}

.gs-progress {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  min-height: 24px;
  margin: 0;
  line-height: 20px;

  strong { color: var(--brand-on-surface); font-size: 16px; font-weight: 600; font-variant-numeric: tabular-nums; }
}

.gs-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  padding: 0 24px 24px;
  list-style: none;
}

.gs-step {
  position: relative;
  min-width: 0;

  &:not(:last-child)::after {
    position: absolute;
    top: 29px;
    right: 6px;
    width: 14px;
    height: 1px;
    background: var(--border-readable);
    content: '';
  }
}

.gs-step__link {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 58px;
  box-sizing: border-box;
  padding: 8px 20px 8px 4px;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-decoration: none;

  &:is(a) {
    cursor: pointer;
    transition: background var(--motion-duration-fast) ease;
    &:hover { background: var(--surface-hover); }
    &:active { background: var(--surface-pressed); }
    &:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
  }
}

.gs-step__number {
  display: grid;
  flex: none;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border-readable);
  border-radius: 12px;
  color: var(--text-muted);
  font-size: 14px;
  font-variant-numeric: tabular-nums;

  .is-next & {
    border-color: var(--brand-primary);
    background: var(--brand-selected-bg);
    color: var(--brand-on-surface);
    font-weight: 600;
  }

  .is-done & { border-color: var(--status-success-soft); background: var(--status-success-soft); color: var(--status-success); }
}

.gs-step__copy {
  display: grid;
  gap: 4px;
  min-width: 0;

  strong { color: var(--text-primary); font-size: 13px; font-weight: 600; line-height: 1.5; }
  small { color: var(--text-muted); font-size: 11px; line-height: 1.5; }
  .is-next & small { color: var(--brand-on-surface); }
  .is-done & small { color: var(--status-success); }
}

.gs-focus {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  align-items: center;
  gap: 16px;
  min-height: 350px;
  padding: 28px;
  border-top: 1px solid var(--border-divider);
}

.gs-focus__copy { min-width: 0; }

.gs-focus__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 16px;
  color: var(--brand-on-surface);
  font-size: 12px;
  font-weight: 500;

  i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
}

.gs-focus h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(22px, 2vw, 28px);
  font-weight: 600;
  line-height: 1.5;
  letter-spacing: -0.025em;
  text-wrap: balance;
}

.gs-focus__description {
  max-width: 440px;
  min-height: 54px;
  margin: 14px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.9;
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.gs-focus__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-top: 26px;

  .el-icon { margin-left: 8px; }
}

.gs-focus__hint { color: var(--text-muted); font-size: 12px; line-height: 1.7; }

.gs-completed {
  display: grid;
  gap: 8px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.7;

  li { display: flex; align-items: baseline; gap: 7px; }
  .el-icon { flex: none; color: var(--status-success); }
}

.gs-principles {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16px 28px;
  padding: 2px 16px;
  color: var(--text-on-wallpaper-secondary);
  font-size: 12px;
  text-shadow: var(--text-on-wallpaper-shadow);

  > div { display: flex; align-items: center; gap: 7px; }
  .el-icon { font-size: 15px; }
}

@media (max-width: 1350px) {
  .gs-focus { grid-template-columns: minmax(0, 1fr) 280px; gap: 8px; }
  .gs-focus__visual { transform: scale(0.82); transform-origin: center; }
  .gs-steps { grid-template-columns: repeat(2, minmax(0, 1fr)); row-gap: 8px; }
  .gs-step:nth-child(2)::after { display: none; }
}

@media (max-width: 1180px) {
  .gs-layout { grid-template-columns: minmax(0, 1fr); }
  .gs-focus { grid-template-columns: minmax(0, 1fr) 340px; }
  .gs-focus__visual { transform: none; }
  .gs-steps { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .gs-step:nth-child(2)::after { display: block; }
}

@media (max-width: 760px) {
  .gs-route-head { padding: 20px 20px 12px; }
  .gs-steps { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0 16px 16px; }
  .gs-step:nth-child(2)::after { display: none; }
  .gs-focus { grid-template-columns: minmax(0, 1fr); min-height: 0; padding: 24px 20px; gap: 24px; }
  .gs-focus__visual { grid-row: 1; }
  .gs-focus__description { min-height: 0; }
}

@media (max-width: 480px) {
  .gs-focus__visual { display: none; }
  .gs-step__link { gap: 7px; padding-right: 8px; }
  .gs-step__number { width: 26px; height: 26px; border-radius: 9px; font-size: 12px; }
  .gs-step:not(:last-child)::after { display: none; }
  .gs-step__copy strong { font-size: 12px; }
  .gs-focus h2 { font-size: 22px; }
  .gs-principles { justify-content: start; gap: 12px; padding-inline: 4px; }
}

@media (prefers-reduced-motion: reduce) {
  .gs-step__link { transition: none; }
}
</style>
