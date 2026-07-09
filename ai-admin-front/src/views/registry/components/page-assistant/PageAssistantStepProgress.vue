<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

export interface WizardStepItem {
  index: number
  key: WizardStepKey
  title: string
  desc: string
  done: boolean
}

const props = defineProps<{
  steps: WizardStepItem[]
  displayedStep: WizardStepKey
}>()

const emit = defineEmits<{
  selectStep: [key: WizardStepKey]
  wheel: [event: WheelEvent]
}>()

const completedStepCount = computed(() => props.steps.filter((step) => step.done).length)
const completedPercent = computed(() =>
  props.steps.length ? Math.round((completedStepCount.value / props.steps.length) * 100) : 0,
)
</script>

<template>
  <section
    class="step-progress access-progress--refined page-assistant-progress"
    aria-label="页面助手创建步骤"
    @wheel="emit('wheel', $event)"
  >
    <div class="access-progress">
      <span>
        接入进度
        <strong>{{ completedStepCount }}/{{ steps.length }}</strong>
        已完成
      </span>
      <div class="access-progress-track" aria-hidden="true">
        <i :style="{ width: `${completedPercent}%` }" />
      </div>
    </div>

    <button
      v-for="step in steps"
      :key="step.key"
      class="progress-step"
      :class="{ active: displayedStep === step.key, done: step.done }"
      type="button"
      :aria-current="displayedStep === step.key ? 'step' : undefined"
      @click="emit('selectStep', step.key)"
    >
      <span class="step-copy">
        <span class="step-title-line">
          <span class="step-number">{{ step.index }}</span>
          <strong>{{ step.title }}</strong>
        </span>
        <small>{{ step.desc }}</small>
      </span>
      <el-icon v-if="displayedStep === step.key" class="step-caret"><ArrowRight /></el-icon>
    </button>
  </section>
</template>

<style scoped lang="scss">
.page-assistant-progress.access-progress--refined {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  min-height: calc(100vh - 208px);
  padding: 18px 12px 20px !important;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.66);
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.58), rgb(var(--brand-selected-rgb) / 0.36));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 48px rgb(var(--brand-primary-rgb) / 0.14);
  box-sizing: border-box;
  backdrop-filter: blur(24px) saturate(1.08);
}

.access-progress {
  padding: 0 14px 12px !important;
}

.access-progress span {
  color: #38546b;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0;
}

.access-progress strong {
  margin: 0 8px;
  color: var(--brand-active);
  font-size: 18px;
  font-weight: 900;
}

.access-progress-track {
  height: 5px;
  margin-top: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: inset 0 1px 2px rgb(var(--brand-active-rgb) / 0.08);
}

.access-progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-hover));
  transition: width 0.24s ease;
}

.progress-step {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px;
  align-items: center;
  width: 100%;
  min-height: 62px !important;
  padding: 9px 12px 9px 14px !important;
  border: 1px solid transparent !important;
  border-radius: 8px;
  appearance: none;
  background: transparent !important;
  box-shadow: none !important;
  color: #203653;
  cursor: pointer;
  font: inherit;
  overflow: visible;
  text-align: left;
}

.progress-step::before {
  content: '';
  position: absolute;
  top: 45px !important;
  bottom: -19px !important;
  left: 29px !important;
  width: 2px;
  border-radius: 999px;
  background: linear-gradient(
    180deg,
    rgb(var(--brand-active-rgb) / 0.16),
    rgb(var(--brand-active-rgb) / 0.06)
  ) !important;
  transform: none !important;
}

.progress-step:last-of-type::before {
  display: none;
}

.progress-step:hover {
  border-color: rgb(var(--brand-active-rgb) / 0.14) !important;
  background: rgba(255, 255, 255, 0.32) !important;
}

.progress-step.active {
  border-color: rgb(var(--brand-active-rgb) / 0.3) !important;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.76), rgb(var(--brand-selected-rgb) / 0.38)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.88),
    0 16px 30px rgb(var(--brand-active-rgb) / 0.13) !important;
}

.step-copy {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 4px;
  min-width: 0;
}

.step-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.step-number {
  position: relative;
  z-index: 1;
  display: inline-flex;
  width: 30px !important;
  height: 30px !important;
  flex: 0 0 30px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(var(--brand-active-rgb) / 0.18) !important;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.74) !important;
  box-shadow:
    0 0 0 5px rgba(255, 255, 255, 0.34),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  color: #5f748f !important;
  font-size: 13px;
  font-weight: 900;
}

.progress-step.active .step-number {
  border-color: transparent !important;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover)) !important;
  box-shadow:
    0 0 0 5px rgb(var(--brand-active-rgb) / 0.1),
    0 9px 18px rgb(var(--brand-active-rgb) / 0.22) !important;
  color: #fff !important;
}

.progress-step.done .step-number {
  border-color: rgb(34 197 94 / 0.2) !important;
  background: rgb(220 252 231 / 0.9) !important;
  color: #16a34a !important;
}

.step-copy strong {
  min-width: 0;
  overflow: hidden;
  color: #1d2a44 !important;
  font-size: 14px !important;
  font-weight: 900;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-copy small {
  margin-left: 40px;
  padding-left: 0 !important;
  color: #647891 !important;
  font-size: 12px !important;
  line-height: 1.2;
}

.progress-step.active .step-copy small {
  color: var(--brand-active) !important;
  font-weight: 800;
}

.step-caret {
  position: relative;
  z-index: 1;
  color: var(--brand-active) !important;
  opacity: 0.72;
  filter: none !important;
}
</style>
