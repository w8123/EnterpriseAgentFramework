<template>
  <main class="login-page">
    <header class="topbar">
      <div class="brand">
        <img class="brand-logo" src="/reachai-logo-tile.png" alt="" width="40" height="40" />
        <div class="brand-copy">
          <span class="brand-title">ReachAI</span>
          <span class="brand-subtitle">企业 AI 能力中台</span>
        </div>
      </div>
    </header>

    <div class="stage">
      <section class="hero" aria-labelledby="login-brand-heading">
        <div class="hero-copy">
          <p class="hero-eyebrow">面向已有企业业务系统</p>
          <h1 id="login-brand-heading">AI 不只回答，更能替你把<span class="hero-accent">工作做完</span></h1>
          <p class="hero-lede">
            让 Agent 在 Workflow、知识库与模型支撑下，调用 OA、EHR、ERP、CRM 等系统能力，完成查询、审批与业务办理。
          </p>
        </div>

        <div
          class="scene-canvas"
          role="group"
          aria-label="ReachAI 企业任务场景演示"
          @mouseenter="pauseScenarioTimer"
          @mouseleave="startScenarioTimer"
        >
          <div class="scene-grid" aria-hidden="true"></div>
          <div class="scene-orbit scene-orbit--one" aria-hidden="true"></div>
          <div class="scene-orbit scene-orbit--two" aria-hidden="true"></div>

          <Transition name="speaker-swap" mode="out-in">
            <div
              :key="currentScenario.id + '-speaker'"
              class="scene-speaker"
              :class="'scene-speaker--' + currentScenario.speakerSide"
            >
              <div class="person-window" aria-hidden="true">
                <img
                  class="person-sprite"
                  :class="'person-sprite--' + currentScenario.speakerSide"
                  src="/login-scene-people-v2.webp"
                  alt=""
                  width="1536"
                  height="1024"
                  decoding="async"
                />
              </div>

              <div class="speech-card" :class="'speech-card--' + currentScenario.speakerSide">
                <span class="speaker-role">
                  <i aria-hidden="true"></i>
                  {{ currentScenario.speaker }}
                </span>
                <strong>“{{ currentScenario.prompt }}”</strong>
              </div>
            </div>
          </Transition>

          <div class="computer-system">
            <Transition name="outcome-swap" mode="out-in">
              <article :key="currentScenario.id + '-outcome'" class="outcome-card reachai-conversation">
                <div class="outcome-card__surface">
                  <header class="outcome-heading">
                    <span class="outcome-agent">
                      <i aria-hidden="true">✦</i>
                      <strong>ReachAI 智能体</strong>
                    </span>
                    <span class="outcome-state"><i aria-hidden="true"></i>已完成</span>
                  </header>
                  <div class="outcome-body">
                    <strong>{{ currentScenario.outcome }}</strong>
                    <span>{{ currentScenario.title }} · {{ currentScenario.systems.join(' / ') }}</span>
                  </div>
                </div>
              </article>
            </Transition>

            <div class="monitor-shell">
              <span class="monitor-camera" aria-hidden="true"></span>
              <div class="monitor-screen">
                <Transition name="screen-swap" mode="out-in">
                  <img
                    :key="currentScenario.id + '-screen'"
                    class="screen-illustration"
                    :src="currentScenario.image"
                    :alt="currentScenario.imageAlt"
                    width="1536"
                    height="1024"
                    decoding="async"
                    fetchpriority="high"
                  />
                </Transition>

                <div class="execution-pill">
                  <span class="execution-pulse" aria-hidden="true"></span>
                  <span>
                    <small>ReachAI 正在执行</small>
                    <strong>{{ currentScenario.steps }}</strong>
                  </span>
                </div>
              </div>
            </div>
            <div class="monitor-neck" aria-hidden="true"></div>
            <div class="monitor-foot" aria-hidden="true"></div>
          </div>

          <div class="foundation-system">
            <div class="foundation-beam" aria-hidden="true"></div>
            <ul class="foundation-rail" aria-label="ReachAI AI 底座">
              <li
                v-for="module in foundationModules"
                :key="module.key"
                class="foundation-chip"
                :class="{ 'foundation-chip--active': isFoundationActive(module.key) }"
              >
                <span class="foundation-icon" aria-hidden="true">
                  <el-icon><component :is="module.icon" /></el-icon>
                </span>
                <span class="foundation-copy">
                  <strong>{{ module.name }}</strong>
                  <small>{{ module.caption }}</small>
                </span>
              </li>
            </ul>
          </div>

          <div class="scene-navigation" aria-label="切换业务场景">
            <button type="button" aria-label="上一个业务场景" @click="showPreviousScenario">‹</button>
            <span class="scene-dots">
              <button
                v-for="(scenario, index) in scenarios"
                :key="scenario.id"
                type="button"
                :aria-label="'切换到' + scenario.title + '场景'"
                :aria-current="index === activeScenarioIndex ? 'true' : undefined"
                :class="{ 'scene-dot--active': index === activeScenarioIndex }"
                @click="selectScenario(index)"
              ></button>
            </span>
            <button type="button" aria-label="下一个业务场景" @click="showNextScenario">›</button>
          </div>
        </div>
      </section>

      <section class="login-zone" aria-labelledby="login-form-heading">
        <span class="login-zone-kicker">PLATFORM CONSOLE</span>
        <h2 id="login-form-heading" class="login-title">登录工作台</h2>
        <p class="login-subtitle">使用平台账号进入管理控制台</p>

        <el-form class="login-form" label-position="top" aria-label="ReachAI 平台登录" @submit.prevent="handleLogin">
          <el-form-item label="用户名">
            <el-input
              v-model="form.username"
              name="username"
              autocomplete="username"
              placeholder="请输入用户名"
              size="large"
            />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              size="large"
            />
          </el-form-item>
          <el-button class="login-button" type="primary" native-type="submit" size="large" :loading="loading">
            登录
          </el-button>
        </el-form>

        <details class="dev-hint">
          <summary>本地开发登录说明</summary>
          <p>
            本地开发默认账号 <code>admin</code> / <code>admin123</code>，已为你预填。生产部署必须关闭 LOCAL 登录或替换默认凭据。
          </p>
        </details>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Collection, Connection, Cpu, Link, MagicStick, TrendCharts } from '@element-plus/icons-vue'
import axios from 'axios'
import { applyPlatformLogin, loginPlatform } from '@/api/platformAuth'
import { sanitizePlatformRedirect } from '@/auth/platformSession'
import '@/conversation/styles/conversation-tokens.css'

type FoundationKey = 'agent' | 'workflow' | 'knowledge' | 'model' | 'capability' | 'runops'
type SpeakerSide = 'left' | 'right'

type Scenario = {
  id: string
  title: string
  speaker: string
  speakerSide: SpeakerSide
  prompt: string
  systems: string[]
  outcome: string
  steps: string
  image: string
  imageAlt: string
  activeFoundation: FoundationKey[]
}

const foundationModules: Array<{
  key: FoundationKey
  name: string
  caption: string
  icon: Component
}> = [
  { key: 'agent', name: 'Agent', caption: '理解任务', icon: MagicStick },
  { key: 'workflow', name: 'Workflow', caption: '编排步骤', icon: Connection },
  { key: 'knowledge', name: 'Knowledge', caption: '知识增强', icon: Collection },
  { key: 'model', name: 'Model', caption: '模型驱动', icon: Cpu },
  { key: 'capability', name: 'Capability', caption: '连接系统', icon: Link },
  { key: 'runops', name: 'RunOps', caption: '运行治理', icon: TrendCharts },
]

const scenarios: Scenario[] = [
  {
    id: 'order-query',
    title: '订单查询',
    speaker: '运营同事',
    speakerSide: 'left',
    prompt: '帮我查询本月订单履约情况',
    systems: ['ERP', 'CRM'],
    outcome: '订单状态已汇总，异常项已标记',
    steps: '理解问题 → 查询订单 → 汇总结果',
    image: '/login-scene-order-v2.webp',
    imageAlt: '订单、物流进度与履约分析组成的订单查询插图',
    activeFoundation: ['agent', 'workflow', 'model', 'capability', 'runops'],
  },
  {
    id: 'oa-approval',
    title: '采购审批',
    speaker: '采购同事',
    speakerSide: 'right',
    prompt: '帮我完成这笔采购申请的审批',
    systems: ['OA', 'ERP'],
    outcome: '审批已完成，结果已写回业务系统',
    steps: '读取规则 → 发起审批 → 写回结果',
    image: '/login-scene-approval-v2.webp',
    imageAlt: '审批单、流程节点、规则手册与完成标记组成的采购审批插图',
    activeFoundation: ['agent', 'workflow', 'knowledge', 'capability', 'runops'],
  },
  {
    id: 'ehr-onboarding',
    title: '员工入职',
    speaker: 'HR 同事',
    speakerSide: 'left',
    prompt: '帮新员工完成入职办理',
    systems: ['EHR', 'OA', 'IT'],
    outcome: '档案、账号与入职审批已协同完成',
    steps: '创建档案 → 开通账号 → 协同审批',
    image: '/login-scene-onboarding-v2.webp',
    imageAlt: '员工档案、入职清单、组织节点与工牌组成的员工入职插图',
    activeFoundation: ['agent', 'workflow', 'knowledge', 'capability', 'runops'],
  },
  {
    id: 'crm-follow-up',
    title: '客户跟进',
    speaker: '客户经理',
    speakerSide: 'right',
    prompt: '整理这次客户沟通并创建跟进任务',
    systems: ['CRM', '知识库'],
    outcome: '沟通纪要已生成，下次跟进已创建',
    steps: '汇总沟通 → 生成纪要 → 创建任务',
    image: '/login-scene-followup-v2.webp',
    imageAlt: '客户档案、沟通消息、跟进日历与趋势组成的客户跟进插图',
    activeFoundation: ['agent', 'workflow', 'knowledge', 'model', 'capability', 'runops'],
  },
]

const activeScenarioIndex = ref(0)
const currentScenario = computed(() => scenarios[activeScenarioIndex.value])

let scenarioTimer: number | undefined
let reducedMotionQuery: MediaQueryList | undefined

function isFoundationActive(key: FoundationKey) {
  return currentScenario.value.activeFoundation.includes(key)
}

function pauseScenarioTimer() {
  if (scenarioTimer !== undefined) {
    window.clearInterval(scenarioTimer)
    scenarioTimer = undefined
  }
}

function startScenarioTimer() {
  pauseScenarioTimer()
  if (reducedMotionQuery?.matches) {
    return
  }
  scenarioTimer = window.setInterval(() => {
    activeScenarioIndex.value = (activeScenarioIndex.value + 1) % scenarios.length
  }, 5600)
}

function selectScenario(index: number) {
  activeScenarioIndex.value = index
  startScenarioTimer()
}

function showPreviousScenario() {
  selectScenario((activeScenarioIndex.value - 1 + scenarios.length) % scenarios.length)
}

function showNextScenario() {
  selectScenario((activeScenarioIndex.value + 1) % scenarios.length)
}

function handleMotionPreferenceChange() {
  startScenarioTimer()
}

onMounted(() => {
  reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  reducedMotionQuery.addEventListener('change', handleMotionPreferenceChange)
  scenarios.forEach((scenario) => {
    const image = new Image()
    image.src = scenario.image
  })
  startScenarioTimer()
})

onBeforeUnmount(() => {
  pauseScenarioTimer()
  reducedMotionQuery?.removeEventListener('change', handleMotionPreferenceChange)
})

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: 'admin123',
})

async function handleLogin() {
  if (loading.value) {
    return
  }
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const { data } = await loginPlatform({ username: form.username, password: form.password })
    applyPlatformLogin(data)
    const redirect = sanitizePlatformRedirect(route.query.redirect)
    await router.replace(redirect)
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 503) {
      ElMessage.error('平台登录尚未配置或暂不可用，请联系管理员。')
      return
    }
    ElMessage.error('用户名或密码错误，请重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  --scene-navy: #15346f;
  --scene-blue: #4d72eb;
  --scene-violet: #6964ed;
  --scene-mint: #3fb8ab;
  --scene-amber: #f4aa22;
  --scene-card: rgb(255 255 255 / 0.92);
  --scene-card-border: rgb(104 127 202 / 0.18);
  --login-card-bg: linear-gradient(160deg, rgb(255 255 255 / 0.74), rgb(244 247 255 / 0.48));
  --login-card-border: rgb(255 255 255 / 0.72);
  --login-card-highlight: rgb(255 255 255 / 0.9);
  --login-card-inner-line: rgb(255 255 255 / 0.3);
  --login-card-shadow: 0 28px 64px rgb(45 66 128 / 0.18), 0 6px 18px rgb(45 66 128 / 0.08);
  --login-field-bg: rgb(255 255 255 / 0.88);
  --login-field-border: rgb(93 113 160 / 0.34);

  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  color: var(--text-primary);
  background:
    radial-gradient(920px 620px at 6% 4%, rgb(var(--brand-hover-rgb) / 0.14), transparent 70%),
    radial-gradient(720px 540px at 86% 6%, rgb(105 100 237 / 0.09), transparent 72%),
    radial-gradient(780px 620px at 96% 96%, rgb(63 184 171 / 0.13), transparent 72%),
    var(--surface-page-canvas);
  overflow-x: hidden;
}

.login-page::before,
.login-page::after {
  position: absolute;
  inset: 0;
  z-index: 0;
  content: '';
  pointer-events: none;
}

.login-page::before {
  background-image:
    linear-gradient(rgb(98 116 182 / 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgb(98 116 182 / 0.055) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 95% 85% at 32% 18%, #000 25%, transparent 78%);
}

.login-page::after {
  background:
    linear-gradient(112deg, transparent 40%, rgb(255 255 255 / 0.36) 50%, transparent 60%),
    url("data:image/svg+xml,%3Csvg xmlns='http:%2F%2Fwww.w3.org%2F2000%2Fsvg' width='140' height='140'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='140' height='140' filter='url(%23n)' opacity='0.05'/%3E%3C/svg%3E");
}

.topbar {
  position: relative;
  z-index: 20;
  flex: none;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 clamp(24px, 3vw, 48px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-logo {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  box-shadow: var(--shadow-panel);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-title {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0.01em;
}

.brand-subtitle {
  color: var(--text-tertiary);
  font-size: 12px;
}

.stage {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 400px);
  align-items: center;
  gap: clamp(40px, 4.5vw, 80px);
  padding: 0 clamp(28px, 3.6vw, 56px) 32px;
}

.hero {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.hero-copy {
  position: relative;
  z-index: 3;
  max-width: 780px;
  margin-bottom: 26px;
}

.hero-eyebrow {
  margin: 0 0 12px;
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

.hero h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(30px, 2.6vw, 42px);
  font-weight: 700;
  line-height: 1.26;
  letter-spacing: -0.02em;
}

.hero-accent {
  color: transparent;
  background: linear-gradient(100deg, var(--brand-primary) 5%, var(--scene-violet) 60%, #4f9dde 105%);
  -webkit-background-clip: text;
  background-clip: text;
}

.hero-lede {
  max-width: 37em;
  margin: 14px 0 0;
  color: var(--text-secondary);
  font-size: 15px;
  line-height: 1.78;
}

.scene-canvas {
  position: relative;
  isolation: isolate;
  width: 100%;
  height: clamp(470px, calc(100vh - 292px), 660px);
  min-height: 0;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.62);
  border-radius: 30px;
  background:
    radial-gradient(circle at 50% 32%, rgb(255 255 255 / 0.5), transparent 52%),
    linear-gradient(150deg, rgb(255 255 255 / 0.62), rgb(237 242 255 / 0.38) 55%, rgb(246 248 255 / 0.52));
  box-shadow:
    0 26px 60px rgb(51 73 132 / 0.13),
    inset 0 1px 0 rgb(255 255 255 / 0.75);
  backdrop-filter: blur(16px);
}

.scene-grid {
  position: absolute;
  inset: 0;
  z-index: -3;
  opacity: 0.32;
  background-image:
    linear-gradient(rgb(98 116 182 / 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgb(98 116 182 / 0.08) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(to bottom, transparent, #000 18%, #000 78%, transparent);
}

.scene-orbit {
  position: absolute;
  z-index: -2;
  border: 1px dashed rgb(83 112 224 / 0.2);
  border-radius: 50%;
  pointer-events: none;
}

.scene-orbit--one {
  width: 72%;
  aspect-ratio: 2 / 1;
  left: 14%;
  top: 24%;
  transform: rotate(-4deg);
}

.scene-orbit--two {
  width: 44%;
  aspect-ratio: 1;
  left: 28%;
  top: 18%;
  transform: rotate(8deg);
}

.scene-speaker {
  position: absolute;
  inset: 0;
  z-index: 6;
  pointer-events: none;
}

.person-window {
  position: absolute;
  bottom: 17%;
  width: clamp(160px, 23%, 225px);
  aspect-ratio: 3 / 4;
  overflow: hidden;
}

.scene-speaker--left .person-window {
  left: 0.5%;
}

.scene-speaker--right .person-window {
  right: 0.5%;
}

.person-sprite {
  position: absolute;
  top: 0;
  width: 200%;
  height: 100%;
  max-width: none;
  object-fit: fill;
}

.person-sprite--left {
  left: 0;
}

.person-sprite--right {
  right: 0;
}

.speech-card {
  position: absolute;
  top: 41%;
  width: clamp(178px, 23%, 224px);
  box-sizing: border-box;
  padding: 13px 15px 14px;
  border: 1px solid rgb(88 112 203 / 0.19);
  border-radius: 17px;
  color: var(--scene-navy);
  background: var(--scene-card);
  box-shadow: 0 14px 34px rgb(52 75 145 / 0.16);
  backdrop-filter: blur(14px);
}

.speech-card--left {
  left: 15.5%;
}

.speech-card--right {
  right: 15.5%;
}

.speech-card::after {
  position: absolute;
  top: 52%;
  width: 12px;
  height: 12px;
  content: '';
  background: inherit;
  transform: translateY(-50%) rotate(45deg);
}

.speech-card--left::after {
  left: -7px;
  border-bottom: 1px solid rgb(88 112 203 / 0.19);
  border-left: 1px solid rgb(88 112 203 / 0.19);
}

.speech-card--right::after {
  right: -7px;
  border-top: 1px solid rgb(88 112 203 / 0.19);
  border-right: 1px solid rgb(88 112 203 / 0.19);
}

.speaker-role {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 6px;
  color: var(--scene-blue);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.speaker-role i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--scene-mint);
  box-shadow: 0 0 0 4px rgb(63 184 171 / 0.13);
}

.speech-card strong {
  display: block;
  font-size: 14px;
  line-height: 1.48;
}

.computer-system {
  position: absolute;
  z-index: 4;
  top: 22%;
  left: 50%;
  width: min(57%, 580px);
  transform: translateX(-50%);
}

.outcome-card {
  position: absolute;
  z-index: 8;
  right: 5%;
  bottom: calc(100% + 13px);
  left: 5%;
  min-height: 98px;
  box-sizing: border-box;
  padding: 2px;
  overflow: hidden;
  border: 0;
  border-radius: calc(var(--reachai-chat-card-radius, 24px) + 2px);
  color: var(--reachai-chat-text);
  background: var(--reachai-chat-card-border);
  box-shadow: var(--reachai-chat-glass-shadow);
}

.outcome-card__surface {
  min-height: 94px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-sizing: border-box;
  padding: 13px 16px 14px;
  border-radius: var(--reachai-chat-card-radius, 24px);
  background:
    var(--reachai-chat-glass-card-reading-layer),
    radial-gradient(circle at 0% 0%, var(--reachai-chat-card-cool-glow), transparent 32%),
    radial-gradient(circle at 100% 0%, var(--reachai-chat-card-rose-glow), transparent 30%),
    radial-gradient(circle at 100% 100%, var(--reachai-chat-card-warm-glow), transparent 34%),
    radial-gradient(circle at 0% 100%, var(--reachai-chat-card-anchor-glow), transparent 30%),
    var(--reachai-chat-card-base);
  box-shadow:
    var(--reachai-chat-glass-highlight),
    var(--reachai-chat-glass-inset-edge);
  backdrop-filter: var(--reachai-chat-glass-blur);
}

.outcome-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.outcome-agent {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--reachai-chat-text);
}

.outcome-agent i {
  color: var(--reachai-chat-primary);
  font-size: 12px;
  font-style: normal;
  line-height: 1;
}

.outcome-agent strong {
  overflow: hidden;
  font-size: 12px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outcome-state {
  flex: none;
  display: flex;
  align-items: center;
  gap: 5px;
  min-height: 22px;
  box-sizing: border-box;
  padding: 2px 8px;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  border-radius: 999px;
  color: var(--reachai-chat-success);
  background: var(--reachai-chat-glass-control);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

.outcome-state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--reachai-chat-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--reachai-chat-success) 14%, transparent);
}

.outcome-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.outcome-body strong {
  overflow: hidden;
  color: var(--reachai-chat-text);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outcome-body span {
  overflow: hidden;
  color: var(--reachai-chat-text-muted);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.monitor-shell {
  position: relative;
  z-index: 3;
  width: 100%;
  aspect-ratio: 16 / 10;
  box-sizing: border-box;
  padding: 11px;
  border-radius: 24px;
  background: linear-gradient(145deg, #244a91, #102f6c 58%, #1d4d9d);
  box-shadow:
    0 25px 52px rgb(27 53 112 / 0.28),
    inset 0 1px rgb(255 255 255 / 0.22);
}

.monitor-camera {
  position: absolute;
  z-index: 5;
  top: 4px;
  left: 50%;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #8eb5ff;
  transform: translateX(-50%);
  box-shadow: 0 0 0 2px rgb(5 23 59 / 0.42);
}

.monitor-screen {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 15px;
  background: #eef3ff;
}

.screen-illustration {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.execution-pill {
  position: absolute;
  z-index: 4;
  bottom: 10px;
  left: 50%;
  min-width: 58%;
  max-width: calc(100% - 24px);
  display: flex;
  align-items: center;
  gap: 9px;
  box-sizing: border-box;
  padding: 7px 11px;
  border: 1px solid rgb(105 126 200 / 0.18);
  border-radius: 12px;
  color: var(--scene-navy);
  background: rgb(255 255 255 / 0.9);
  box-shadow: 0 9px 26px rgb(51 73 135 / 0.14);
  transform: translateX(-50%);
  backdrop-filter: blur(10px);
}

.execution-pulse {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--scene-mint);
  box-shadow: 0 0 0 5px rgb(63 184 171 / 0.13);
}

.execution-pill > span:last-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.execution-pill small {
  color: var(--scene-blue);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.execution-pill strong {
  overflow: hidden;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.monitor-neck {
  position: relative;
  z-index: 2;
  width: 24%;
  height: 43px;
  margin: -1px auto 0;
  background: linear-gradient(90deg, #1f4487, #5b7fd2 48%, #1d4385);
  clip-path: polygon(18% 0, 82% 0, 100% 100%, 0 100%);
}

.monitor-foot {
  position: relative;
  z-index: 3;
  width: 48%;
  height: 12px;
  margin: -1px auto 0;
  border-radius: 12px 12px 6px 6px;
  background: linear-gradient(180deg, #5278cb, #1f4589);
  box-shadow: 0 7px 15px rgb(29 63 127 / 0.2);
}

.foundation-system {
  position: absolute;
  z-index: 7;
  right: 7%;
  bottom: 18px;
  left: 7%;
  box-sizing: border-box;
  padding: 16px 18px 12px;
  border: 1px solid rgb(88 114 205 / 0.18);
  border-radius: 25px;
  background: linear-gradient(180deg, rgb(238 243 255 / 0.96), rgb(203 217 255 / 0.94));
  box-shadow:
    0 18px 36px rgb(48 70 137 / 0.19),
    inset 0 1px rgb(255 255 255 / 0.82);
}

.foundation-beam {
  position: absolute;
  right: 8%;
  bottom: calc(100% - 7px);
  left: 8%;
  height: 100px;
  opacity: 0.34;
  background:
    linear-gradient(70deg, transparent 48%, rgb(84 112 225 / 0.4) 49%, transparent 50%) 0 0 / 20% 100%,
    linear-gradient(110deg, transparent 48%, rgb(84 112 225 / 0.4) 49%, transparent 50%) 0 0 / 20% 100%;
  mask-image: linear-gradient(to top, #000, transparent 92%);
  pointer-events: none;
}

.foundation-rail {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.foundation-chip {
  min-width: 0;
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  box-sizing: border-box;
  padding: 7px 6px;
  border: 1px solid rgb(102 123 195 / 0.16);
  border-radius: 13px;
  color: #5f6e91;
  background: rgb(255 255 255 / 0.78);
  opacity: 0.64;
  transition:
    opacity 0.3s ease,
    border-color 0.3s ease,
    box-shadow 0.3s ease,
    transform 0.3s ease;
}

.foundation-chip--active {
  border-color: rgb(84 105 239 / 0.34);
  color: var(--scene-navy);
  background: rgb(255 255 255 / 0.96);
  box-shadow: 0 8px 18px rgb(65 86 167 / 0.13);
  opacity: 1;
  transform: translateY(-2px);
}

.foundation-icon {
  flex: none;
  width: 27px;
  height: 27px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: var(--scene-blue);
  background: rgb(91 115 233 / 0.1);
  font-size: 15px;
}

.foundation-chip--active .foundation-icon {
  color: #fff;
  background: linear-gradient(145deg, var(--scene-blue), var(--scene-violet));
  box-shadow: 0 6px 14px rgb(87 99 225 / 0.22);
}

.foundation-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.18;
}

.foundation-copy strong {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foundation-copy small {
  overflow: hidden;
  margin-top: 2px;
  color: inherit;
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scene-navigation {
  position: absolute;
  z-index: 10;
  top: 20px;
  right: 18px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 7px;
  border: 1px solid rgb(97 119 194 / 0.17);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.76);
  box-shadow: 0 9px 22px rgb(47 68 133 / 0.12);
  backdrop-filter: blur(12px);
}

.scene-navigation > button {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--scene-navy);
  background: transparent;
  font: inherit;
  font-size: 19px;
  line-height: 1;
  cursor: pointer;
}

.scene-navigation > button:hover,
.scene-navigation > button:focus-visible {
  background: rgb(89 108 226 / 0.1);
  outline: none;
}

.scene-dots {
  display: flex;
  align-items: center;
  gap: 5px;
}

.scene-dots button {
  width: 6px;
  height: 6px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: rgb(87 103 155 / 0.3);
  cursor: pointer;
  transition: width 0.25s ease, background 0.25s ease;
}

.scene-dots .scene-dot--active {
  width: 18px;
  background: var(--scene-violet);
}

.login-zone {
  position: relative;
  z-index: 4;
  align-self: center;
  width: 100%;
  min-height: 520px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(36px, 4.5vh, 48px) clamp(28px, 2.2vw, 40px);
  border: 1px solid var(--login-card-border);
  border-radius: 26px;
  background: var(--login-card-bg);
  box-shadow:
    var(--login-card-shadow),
    inset 0 1px 0 var(--login-card-highlight),
    inset 0 -1px 0 var(--login-card-inner-line);
  backdrop-filter: blur(26px) saturate(1.5);
}

.login-zone-kicker {
  display: block;
  margin-bottom: 10px;
  color: var(--brand-primary);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.15em;
}

.login-title {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
}

.login-subtitle {
  margin: 8px 0 28px;
  color: var(--text-tertiary);
  font-size: 13px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 21px;
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  color: var(--text-secondary);
  font-weight: 700;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 42px;
  background: var(--login-field-bg);
  box-shadow: 0 0 0 1px var(--login-field-border) inset;
}

.login-form :deep(.el-input__wrapper:hover),
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--brand-primary) inset;
}

.login-button {
  width: 100%;
  min-height: 43px;
  margin-top: 8px;
  border: 0;
  background: linear-gradient(110deg, var(--brand-primary), #8256ed);
  box-shadow: 0 10px 24px rgb(var(--brand-hover-rgb) / 0.22);
  font-weight: 800;
}

.dev-hint {
  margin-top: 24px;
  color: var(--text-tertiary);
  font-size: 12px;
}

.dev-hint summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  list-style: none;
  transition: color 0.2s ease;
}

.dev-hint summary:hover {
  color: var(--brand-primary);
}

.dev-hint summary::-webkit-details-marker {
  display: none;
}

.dev-hint summary::after {
  content: '';
  width: 7px;
  height: 7px;
  margin-left: 2px;
  border-right: 1.5px solid currentColor;
  border-top: 1.5px solid currentColor;
  transform: rotate(45deg);
  transition: transform 0.22s ease;
}

.dev-hint[open] summary::after {
  transform: rotate(135deg) translateX(-1px) translateY(1px);
}

.dev-hint p {
  margin: 9px 0 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgb(var(--brand-hover-rgb) / 0.06);
  line-height: 1.55;
}

.dev-hint code {
  color: var(--brand-primary);
  font-family: var(--font-mono, monospace);
  font-weight: 700;
}

.speaker-swap-enter-active,
.speaker-swap-leave-active,
.outcome-swap-enter-active,
.outcome-swap-leave-active,
.screen-swap-enter-active,
.screen-swap-leave-active {
  transition: opacity 0.42s ease, transform 0.52s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.speaker-swap-enter-from,
.speaker-swap-leave-to,
.outcome-swap-enter-from,
.outcome-swap-leave-to,
.screen-swap-enter-from,
.screen-swap-leave-to {
  opacity: 0;
}

.speaker-swap-enter-from.scene-speaker--left,
.speaker-swap-leave-to.scene-speaker--left {
  transform: translateX(-34px);
}

.speaker-swap-enter-from.scene-speaker--right,
.speaker-swap-leave-to.scene-speaker--right {
  transform: translateX(34px);
}

.outcome-swap-enter-from,
.outcome-swap-leave-to {
  transform: translateY(-12px);
}

.screen-swap-enter-from,
.screen-swap-leave-to {
  transform: scale(1.025);
}

@media (max-width: 1320px) {
  .stage {
    grid-template-columns: minmax(0, 1fr) 360px;
    gap: 32px;
  }

  .foundation-copy small {
    display: none;
  }

  .foundation-chip {
    gap: 5px;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .execution-pulse {
    animation: execution-pulse 1.8s ease-out infinite;
  }

  .foundation-chip--active .foundation-icon {
    animation: foundation-glow 2.6s ease-in-out infinite;
  }

  @keyframes execution-pulse {
    0%,
    100% {
      box-shadow: 0 0 0 4px rgb(63 184 171 / 0.12);
    }
    50% {
      box-shadow: 0 0 0 9px rgb(63 184 171 / 0);
    }
  }

  @keyframes foundation-glow {
    0%,
    100% {
      transform: translateY(0);
    }
    50% {
      transform: translateY(-2px);
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-page *,
  .login-page *::before,
  .login-page *::after {
    scroll-behavior: auto !important;
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
  }
}

:global([data-theme='dark'] .login-page) {
  --scene-card: rgb(19 33 56 / 0.92);
  --scene-card-border: rgb(132 153 224 / 0.2);
  --scene-navy: #dce6ff;
  --login-card-bg: linear-gradient(160deg, rgb(24 40 68 / 0.78), rgb(14 27 49 / 0.6));
  --login-card-border: rgb(132 153 224 / 0.26);
  --login-card-highlight: rgb(160 185 255 / 0.16);
  --login-card-inner-line: rgb(160 185 255 / 0.08);
  --login-card-shadow: 0 28px 64px rgb(0 0 0 / 0.46), 0 6px 18px rgb(0 0 0 / 0.24);
  --login-field-bg: rgb(21 38 64 / 0.96);
  --login-field-border: rgb(132 153 194 / 0.32);

  background:
    radial-gradient(900px 600px at 6% 5%, rgb(var(--brand-hover-rgb) / 0.15), transparent 70%),
    radial-gradient(700px 520px at 86% 8%, rgb(105 100 237 / 0.1), transparent 72%),
    radial-gradient(760px 600px at 96% 95%, rgb(63 184 171 / 0.11), transparent 72%),
    var(--surface-page-canvas);
}

:global([data-theme='dark'] .login-page::before) {
  background-image:
    linear-gradient(rgb(132 153 224 / 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgb(132 153 224 / 0.06) 1px, transparent 1px);
}

:global([data-theme='dark'] .login-page::after) {
  background:
    linear-gradient(112deg, transparent 40%, rgb(132 153 224 / 0.07) 50%, transparent 60%),
    url("data:image/svg+xml,%3Csvg xmlns='http:%2F%2Fwww.w3.org%2F2000%2Fsvg' width='140' height='140'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='140' height='140' filter='url(%23n)' opacity='0.05'/%3E%3C/svg%3E");
}

:global([data-theme='dark'] .scene-canvas) {
  border-color: rgb(132 153 224 / 0.2);
  background:
    radial-gradient(circle at 50% 32%, rgb(64 84 132 / 0.24), transparent 54%),
    linear-gradient(150deg, rgb(18 30 52 / 0.74), rgb(11 22 40 / 0.6) 58%, rgb(17 28 49 / 0.7));
  box-shadow:
    0 26px 60px rgb(0 0 0 / 0.32),
    inset 0 1px 0 rgb(160 185 255 / 0.1);
}

:global([data-theme='dark'] .foundation-system) {
  background: linear-gradient(180deg, rgb(29 45 75 / 0.96), rgb(18 33 58 / 0.96));
}

:global([data-theme='dark'] .foundation-chip) {
  color: #8f9cb8;
  background: rgb(13 27 49 / 0.72);
}

:global([data-theme='dark'] .foundation-chip--active) {
  color: #dce6ff;
  background: rgb(24 41 69 / 0.96);
}

:global([data-theme='dark'] .execution-pill) {
  color: #dce6ff;
  background: rgb(14 29 52 / 0.9);
}

:global([data-theme='dark'] .scene-navigation) {
  background: rgb(16 31 54 / 0.82);
}

:global([data-theme='dark'] .scene-navigation > button) {
  color: #dce6ff;
}

:global([data-theme='dark'] .outcome-state) {
  color: #6ee7b7;
  background: rgb(6 118 71 / 0.18);
  border-color: rgb(110 231 183 / 0.22);
}

:global([data-theme='dark'] .outcome-state i) {
  background: #6ee7b7;
  box-shadow: 0 0 0 3px rgb(110 231 183 / 0.12);
}
</style>
