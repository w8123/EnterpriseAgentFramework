<template>
  <div class="setup-visual" role="img" :aria-label="content.label">
    <svg class="setup-visual__connections" viewBox="0 0 340 260" fill="none" aria-hidden="true">
      <path v-for="(node, index) in content.nodes" :key="node.name" :d="PATHS[index]" />
      <circle cx="170" cy="130" r="64" />
      <circle cx="170" cy="130" r="94" />
    </svg>
    <div class="setup-visual__core glass-surface-shell" aria-hidden="true">
      <el-icon><component :is="content.icon" /></el-icon>
    </div>
    <div
      v-for="(node, index) in content.nodes"
      :key="node.name"
      class="setup-visual__node glass-surface-control"
      :class="[`setup-visual__node--${index}`, { 'setup-visual__node--primary': stage === 'employee' && index < 2 }]"
      aria-hidden="true"
    >
      <img v-if="node.src" :src="node.src" alt="">
      <span v-else class="setup-visual__glyph">{{ node.glyph }}</span>
      <span>{{ node.name }}</span>
    </div>
    <span class="setup-visual__caption" aria-hidden="true">{{ content.caption }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Connection, Cpu, Share, User } from '@element-plus/icons-vue'
import deepseekLogo from '@/assets/model-providers/deepseek.png'
import qianfanLogo from '@/assets/model-providers/qianfan.png'
import kimiLogo from '@/assets/model-providers/kimi.png'
import openaiLogo from '@/assets/model-providers/openai.png'
import workbuddyLogo from '@/assets/getting-started/workbuddy-color.svg'
import codexLogo from '@/assets/ai-coding-providers/codex.svg'
import businessPageIcon from '@/assets/getting-started/entry-page.svg'
import portalIcon from '@/assets/getting-started/entry-portal.svg'
import type { GettingStartedStepKey } from './gettingStarted'

const props = defineProps<{ stage: GettingStartedStepKey }>()
type VisualNode = { name: string; src?: string; glyph?: string }
type VisualContent = { icon: Component; caption: string; label: string; nodes: VisualNode[] }

const VISUALS: Record<GettingStartedStepKey, VisualContent> = {
  model: {
    icon: Cpu,
    caption: '一个入口，连接主流模型',
    label: '可连接 DeepSeek、千帆、Kimi、OpenAI 等模型厂商',
    nodes: [
      { name: 'DeepSeek', src: deepseekLogo },
      { name: 'OpenAI', src: openaiLogo },
      { name: 'Kimi', src: kimiLogo },
      { name: '千帆', src: qianfanLogo },
    ],
  },
  system: {
    icon: Connection,
    caption: '让已有系统拥有 AI 入口',
    label: '通过 Java SDK 或 OpenAPI 接入已有业务系统',
    nodes: [
      { name: 'Java SDK', glyph: 'SDK' },
      { name: 'OpenAPI', glyph: 'API' },
      { name: '业务方法', glyph: '{ }' },
      { name: 'HTTP API', glyph: '↗' },
    ],
  },
  publish: {
    icon: Share,
    caption: '把业务能力交给 AI 工具',
    label: '将选定的业务能力发布为工具集，调用受身份、权限与审批约束',
    nodes: [
      { name: '业务方法', glyph: '{ }' },
      { name: 'Workflow', glyph: '⋈' },
      { name: 'HTTP API', glyph: '↗' },
      { name: '工具集', glyph: 'MCP' },
    ],
  },
  employee: {
    icon: User,
    caption: '在业务页面或企业门户对话，也可连接 AI 工具',
    label: '员工可在业务系统页面或企业门户与 AI 助手对话，也可连接 WorkBuddy、Codex 等 AI 工具',
    nodes: [
      { name: '业务系统', src: businessPageIcon },
      { name: '企业门户', src: portalIcon },
      { name: 'WorkBuddy', src: workbuddyLogo },
      { name: 'Codex', src: codexLogo },
    ],
  },
}

const PATHS = [
  'M 94 54 H 112 Q 134 54 134 78 V 108 Q 134 130 152 130',
  'M 246 54 H 228 Q 206 54 206 78 V 108 Q 206 130 188 130',
  'M 94 206 H 112 Q 134 206 134 182 V 152 Q 134 130 152 130',
  'M 246 206 H 228 Q 206 206 206 182 V 152 Q 206 130 188 130',
]
const content = computed(() => VISUALS[props.stage])
</script>

<style scoped lang="scss">
.setup-visual {
  position: relative;
  width: 340px;
  height: 290px;
  max-width: 100%;
  justify-self: center;
  color: var(--brand-on-surface);
}

.setup-visual__connections {
  position: absolute;
  inset: 0 0 30px;
  width: 100%;
  height: 260px;
  overflow: visible;

  path { stroke: var(--brand-primary); stroke-width: 1.25; opacity: 0.32; }
  circle { stroke: var(--brand-primary); stroke-width: 1; opacity: 0.07; }
}

.setup-visual__core {
  position: absolute;
  top: 91px;
  left: calc(50% - 39px);
  display: grid;
  place-items: center;
  width: 78px;
  height: 78px;
  border-radius: 25px;
  transform: rotate(-8deg);

  .el-icon { font-size: 33px; transform: rotate(8deg); }
}

.setup-visual__node {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 8px;
  width: max-content;
  min-width: 100px;
  box-sizing: border-box;
  padding: 12px;
  border-radius: var(--radius-lg);
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 500;

  img { width: 26px; height: 26px; object-fit: contain; }
}

.setup-visual__node--primary {
  gap: 10px;
  min-width: 122px;
  padding: 14px;
  font-size: 14px;
  font-weight: 600;

  img { width: 34px; height: 34px; }
}

.setup-visual__node--0 { top: 28px; left: 0; transform: rotate(-5deg); }
.setup-visual__node--1 { top: 28px; right: 0; transform: rotate(5deg); }
.setup-visual__node--2 { top: 180px; left: 0; transform: rotate(-3deg); }
.setup-visual__node--3 { top: 180px; right: 0; transform: rotate(4deg); }

.setup-visual__glyph {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  color: var(--brand-on-surface);
  font-family: var(--font-family-mono);
  font-size: 12px;
  font-weight: 600;
}

.setup-visual__caption {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

@media (forced-colors: active) {
  .setup-visual__connections path,
  .setup-visual__connections circle { stroke: CanvasText; opacity: 1; }
}
</style>
