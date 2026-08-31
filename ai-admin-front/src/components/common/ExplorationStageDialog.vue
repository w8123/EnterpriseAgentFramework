<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, CircleCheckFilled, SwitchButton } from '@element-plus/icons-vue'
import { logoutPlatform } from '@/api/platformAuth'
import {
  acknowledgePlatformExplorationNotice,
  platformSessionState,
  platformSessionId,
  requiresPlatformExplorationAcknowledgement,
} from '@/auth/platformSession'
import AppDialog from '@/components/common/AppDialog.vue'

const router = useRouter()
const visible = ref(false)
const accepted = ref(false)
const leaving = ref(false)

const currentSessionId = computed(() => platformSessionId.value)

watch(
  [platformSessionState, currentSessionId, requiresPlatformExplorationAcknowledgement],
  ([sessionState, sessionId, requiresAcknowledgement]) => {
    if (sessionState !== 'AUTHENTICATED' || !sessionId) {
      visible.value = false
      accepted.value = false
      return
    }
    visible.value = requiresAcknowledgement
    accepted.value = false
  },
  { immediate: true },
)

const noticeItems = [
  '功能、接口与数据结构可能持续调整',
  '请勿接入生产数据或关键业务',
  '建议仅用于体验、验证与内部测试',
]

function enterPlatform() {
  if (!accepted.value) return
  if (acknowledgePlatformExplorationNotice()) {
    visible.value = false
  }
}

async function exitPlatform() {
  leaving.value = true
  try {
    await logoutPlatform()
    await router.replace('/login')
  } catch {
    // Keep the live cookie session available so the user can retry.
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <AppDialog
    v-model="visible"
    class="exploration-stage-dialog"
    modal-class="exploration-stage-overlay"
    title=""
    width="920px"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :destroy-on-close="false"
    aria-label="ReachAI 探索阶段使用说明"
  >
    <section class="exploration-stage">
      <div class="exploration-scene">
        <img
          class="exploration-scene__image"
          src="/exploration-stage-illustration.png"
          alt="ReachAI 机器人正在搭建探索中的智能体平台"
        />
      </div>

      <div class="exploration-content">
        <div class="exploration-pill">
          <span class="pill-orbit" />
          探索预览
        </div>

        <h2>欢迎来到 <span>ReachAI</span> 探索站</h2>
        <p class="exploration-lead">
          当前平台仍处于功能探索与快速迭代阶段，<strong>暂不建议直接用于生产环境。</strong>
        </p>

        <ul class="exploration-checklist">
          <li v-for="item in noticeItems" :key="item">
            <el-icon><CircleCheckFilled /></el-icon>
            <span>{{ item }}</span>
          </li>
        </ul>

        <div class="exploration-acknowledgement" :class="{ 'is-accepted': accepted }">
          <el-checkbox v-model="accepted">
            我已了解上述说明，并愿意继续体验
          </el-checkbox>
        </div>

        <div class="exploration-actions">
          <el-button size="large" :loading="leaving" @click="exitPlatform">
            <el-icon><SwitchButton /></el-icon>
            退出登录
          </el-button>
          <el-button
            type="primary"
            size="large"
            :disabled="!accepted"
            @click="enterPlatform"
          >
            我已了解，进入平台
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
    </section>
  </AppDialog>
</template>

<style scoped lang="scss">
:global(.exploration-stage-overlay) {
  background: rgb(14 24 43 / 0.56);
  -webkit-backdrop-filter: blur(10px) saturate(0.82);
  backdrop-filter: blur(10px) saturate(0.82);
}

:global(.exploration-stage-dialog.el-dialog) {
  max-block-size: calc(100vh - 48px);
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.22);
  border-radius: 24px;
  box-shadow:
    0 32px 90px rgb(10 22 44 / 0.38),
    0 0 0 1px rgb(255 255 255 / 0.72) inset,
    0 0 36px rgb(var(--brand-primary-rgb) / 0.18);
}

:global(.exploration-stage-dialog.el-dialog .el-dialog__header) {
  display: none;
}

:global(.exploration-stage-dialog.el-dialog .el-dialog__body) {
  max-block-size: none;
  overflow: auto;
  padding: 0;
}

:global([data-theme='dark'] .exploration-stage-dialog.el-dialog) {
  border-color: rgb(var(--brand-primary-rgb) / 0.34);
  box-shadow:
    0 32px 96px rgb(0 0 0 / 0.68),
    0 0 0 1px rgb(255 255 255 / 0.08) inset,
    0 0 42px rgb(var(--brand-primary-rgb) / 0.2);
}

.exploration-stage {
  display: grid;
  grid-template-columns: minmax(320px, 0.84fr) minmax(0, 1.16fr);
  min-height: 548px;
  color: var(--text-primary);
}

.exploration-scene {
  position: relative;
  min-height: 548px;
  overflow: hidden;
  border-inline-end: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  background: rgb(var(--brand-selected-rgb) / 0.64);
  isolation: isolate;
}

.exploration-scene::after {
  content: '';
  position: absolute;
  inset: 0 0 0 auto;
  width: 18px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgb(255 255 255 / 0.24));
}

.exploration-scene__image {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 548px;
  object-fit: cover;
  object-position: center;
}

.exploration-content {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  padding: 52px 46px 44px;
  background:
    radial-gradient(circle at 100% 0%, rgb(var(--brand-primary-rgb) / 0.08), transparent 34%),
    var(--surface-glass-overlay);
}

.exploration-pill {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 8px;
  margin-block-end: 20px;
  padding: 7px 13px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.42);
  border-radius: 999px;
  color: var(--brand-active);
  font-size: 14px;
  font-weight: 700;
  background: rgb(var(--brand-selected-rgb) / 0.34);
  box-shadow: 0 6px 18px rgb(var(--brand-primary-rgb) / 0.08);
}

.pill-orbit {
  position: relative;
  width: 19px;
  height: 13px;
  border: 2px solid currentColor;
  border-radius: 50%;
  transform: rotate(-24deg);
}

.pill-orbit::after {
  content: '';
  position: absolute;
  inset: 2px auto auto 5px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.exploration-content h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(28px, 3vw, 36px);
  line-height: 1.24;
  letter-spacing: -0.035em;
}

.exploration-content h2 span {
  color: var(--brand-primary);
}

.exploration-lead {
  margin: 18px 0 0;
  color: var(--text-secondary);
  font-size: 16px;
  line-height: 1.85;
}

.exploration-lead strong {
  color: var(--text-primary);
  font-weight: 700;
}

.exploration-checklist {
  display: grid;
  gap: 13px;
  margin: 25px 0 0;
  padding: 0;
  list-style: none;
}

.exploration-checklist li {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.exploration-checklist .el-icon {
  flex: 0 0 auto;
  color: var(--brand-primary);
  font-size: 19px;
}

.exploration-acknowledgement {
  margin-block-start: 28px;
  padding: 15px 16px;
  border: 1px solid var(--border-readable);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
  transition:
    border-color var(--motion-duration-normal) ease,
    background var(--motion-duration-normal) ease,
    box-shadow var(--motion-duration-normal) ease;
}

.exploration-acknowledgement.is-accepted {
  border-color: rgb(var(--brand-primary-rgb) / 0.45);
  background: var(--surface-glass-selected);
  box-shadow: 0 8px 22px rgb(var(--brand-primary-rgb) / 0.1);
}

.exploration-acknowledgement :deep(.el-checkbox) {
  height: auto;
  align-items: flex-start;
  white-space: normal;
}

.exploration-acknowledgement :deep(.el-checkbox__input) {
  margin-block-start: 2px;
}

.exploration-acknowledgement :deep(.el-checkbox__label) {
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 650;
  line-height: 1.5;
  white-space: normal;
}

.exploration-actions {
  display: grid;
  grid-template-columns: minmax(0, 0.86fr) minmax(0, 1.34fr);
  gap: 12px;
  margin-block-start: 18px;
}

.exploration-actions .el-button {
  min-width: 0;
  height: 48px;
  margin: 0;
  border-radius: var(--radius-md);
  font-weight: 700;
}

.exploration-actions .el-button--primary {
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.24);
}

.exploration-actions .el-button--primary.is-disabled {
  border: 1px solid var(--border-subtle);
  color: var(--text-disabled);
  background: var(--surface-solid-disabled);
  box-shadow: none;
  filter: saturate(0.35);
}

@media (max-width: 820px) {
  :global(.exploration-stage-dialog.el-dialog) {
    width: min(680px, calc(100vw - 28px)) !important;
  }

  .exploration-stage {
    grid-template-columns: 238px minmax(0, 1fr);
  }

  .exploration-content {
    padding: 40px 30px 34px;
  }

  .exploration-content h2 {
    font-size: 29px;
  }
}

@media (max-width: 620px) {
  :global(.exploration-stage-dialog.el-dialog) {
    width: calc(100vw - 20px) !important;
    max-block-size: calc(100vh - 20px);
    margin-block-start: 10px !important;
    border-radius: 20px;
  }

  .exploration-stage {
    grid-template-columns: 1fr;
  }

  .exploration-scene {
    min-height: 190px;
    border-inline-end: 0;
    border-block-end: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  }

  .exploration-scene::after {
    inset: auto 0 0;
    width: auto;
    height: 18px;
    background: linear-gradient(180deg, transparent, rgb(255 255 255 / 0.24));
  }

  .exploration-scene__image {
    height: 190px;
    min-height: 190px;
    object-position: center 48%;
  }

  .exploration-content {
    padding: 28px 22px 22px;
  }

  .exploration-pill {
    margin-block-end: 14px;
  }

  .exploration-content h2 {
    font-size: 27px;
  }

  .exploration-lead {
    margin-block-start: 12px;
    font-size: 15px;
  }

  .exploration-checklist {
    gap: 9px;
    margin-block-start: 18px;
  }

  .exploration-acknowledgement {
    margin-block-start: 20px;
  }

  .exploration-actions {
    grid-template-columns: 1fr;
  }

  .exploration-actions .el-button--primary {
    grid-row: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .exploration-acknowledgement {
    transition: none;
  }
}
</style>
