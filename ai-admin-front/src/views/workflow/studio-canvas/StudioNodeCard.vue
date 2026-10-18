<template>
  <div
    class="studio-node"
    :class="[model.kindClass, runClass, { collapsed, 'needs-config': model.needsConfig }]"
  >
    <Handle v-if="model.hasTarget" type="target" :position="Position.Left" />
    <Handle v-if="model.hasSource" type="source" :position="Position.Right" />
    <div class="node-icon"><el-icon><component :is="iconComponent" /></el-icon></div>
    <div v-if="model.kicker" class="node-kicker">{{ model.kicker }}</div>
    <div v-else class="node-head">
      <span class="node-kind">{{ model.kind }}</span>
      <span class="node-state">{{ model.state }}</span>
    </div>
    <div class="node-label">{{ model.label }}</div>
    <div v-if="model.description" class="node-desc">{{ model.description }}</div>
    <div v-if="model.routes.length" class="classifier-routes">
      <div
        v-for="route in model.routes"
        :key="route.id"
        class="classifier-route-row"
        :class="{ 'is-default': route.isDefault }"
      >
        <div class="classifier-route-copy">
          <strong>{{ route.label }}</strong>
          <span>{{ route.meta }}</span>
        </div>
        <Handle
          :id="route.handleId"
          type="source"
          :position="Position.Right"
          class="classifier-route-handle"
        />
      </div>
    </div>
    <div class="node-port-row">{{ model.ports }}</div>
    <div v-if="model.showAlias && outputAlias" class="node-alias">输出别名：{{ outputAlias }}</div>
    <button v-if="runLabel" class="node-runtime" type="button" @click.stop="emit('open-trace')">
      <span class="runtime-dot"></span>
      <span>{{ runLabel }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import {
  Briefcase,
  Coin,
  Collection,
  Connection,
  Document,
  Files,
  Finished,
  Link,
  MagicStick,
  RefreshRight,
  SetUp,
  Switch,
  VideoPlay,
} from '@element-plus/icons-vue'
import LlmModelIcon from '@/components/icons/LlmModelIcon.vue'
import type { StudioNodeCardModel } from './studioNodeCardModel'

const props = defineProps<{
  model: StudioNodeCardModel
  collapsed?: boolean
  outputAlias?: string
  runClass?: string | string[]
  /** Debug/trace state label; the badge shows only while the node has one. */
  runLabel?: string
}>()

const emit = defineEmits<{
  'open-trace': []
}>()

const icons: Record<string, Component> = {
  Briefcase,
  Coin,
  Collection,
  Connection,
  Document,
  Files,
  Finished,
  Link,
  LlmModel: LlmModelIcon,
  MagicStick,
  RefreshRight,
  SetUp,
  Switch,
  VideoPlay,
}

const iconComponent = computed(() => icons[props.model.icon] || SetUp)
</script>

<style scoped lang="scss">

</style>

<style scoped lang="scss">
.studio-node {
  --node-color: #2563eb;
  --node-soft: rgba(37, 99, 235, 0.1);
  --node-line: rgba(37, 99, 235, 0.2);
  position: relative;
  box-sizing: border-box;
  width: 240px;
  min-width: 240px;
  max-width: 240px;
  min-height: 118px;
  padding: 20px 14px 0;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 12px;
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  background:
    radial-gradient(circle at 82% 0%, var(--node-soft), transparent 34%),
    linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  color: #0f172a;
  font-size: 13px;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.11);
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.studio-node::before {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--node-color), color-mix(in srgb, var(--node-color) 56%, #ffffff));
  content: '';
}

.studio-node:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--node-color) 52%, #dbe5f0);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.14);
}

.studio-node .node-icon {
  position: absolute;
  top: 20px;
  left: 14px;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid var(--node-line);
  border-radius: 9px;
  background: var(--node-soft);
  color: var(--node-color);
  font-size: 17px;
  line-height: 1;
}

.studio-node .node-icon .el-icon {
  font-size: 17px;
}

.studio-node .node-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  min-height: 30px;
  margin: 0 0 8px 42px;
}

.studio-node .node-kind {
  display: inline-flex;
  align-items: center;
  max-width: 104px;
  min-height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.045);
  color: #475569;
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
  white-space: nowrap;
}

.studio-node .node-state,
.studio-node .node-kicker {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid var(--node-line);
  border-radius: 999px;
  background: var(--node-soft);
  color: var(--node-color);
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
  white-space: nowrap;
}

.studio-node .node-kicker {
  margin-left: 42px;
  margin-bottom: 8px;
}

.studio-node .node-label {
  overflow: visible;
  color: #0f172a;
  font-size: 15px;
  font-weight: 800;
  line-height: 1.35;
  text-overflow: clip;
  white-space: normal;
  word-break: break-all;
}

.studio-node .node-kicker + .node-label,
.studio-node .node-head + .node-label {
  margin-left: 42px;
}

.studio-node .node-desc {
  display: -webkit-box;
  min-height: 34px;
  margin-top: 9px;
  padding: 8px 10px;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.86);
  color: #475569;
  font-size: 11px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.studio-node .node-alias {
  display: inline-flex;
  max-width: 100%;
  margin-top: 8px;
  padding: 3px 7px;
  border: 1px solid rgba(14, 165, 233, 0.18);
  border-radius: 6px;
  background: rgba(14, 165, 233, 0.08);
  color: #0369a1;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 11px;
}

.node-port-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 10px -14px 0;
  padding: 8px 14px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
  background: rgba(248, 250, 252, 0.76);
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.35;
}

.studio-node.collapsed {
  width: 190px;
  min-width: 190px;
  max-width: 190px;
  min-height: 64px;
  padding: 18px 14px 14px 58px;
}

.studio-node.collapsed .node-desc,
.studio-node.collapsed .node-port-row,
.studio-node.collapsed .node-alias {
  display: none;
}

.studio-node.collapsed .node-head,
.studio-node.collapsed .node-kicker,
.studio-node.collapsed .node-label {
  margin-left: 0;
}

.studio-node.collapsed .node-head {
  min-height: 0;
  margin-bottom: 4px;
}

.studio-node.collapsed .node-label {
  font-size: 14px;
}

.studio-node.collapsed .node-icon {
  top: 18px;
  left: 14px;
}

.node-runtime {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: auto;
  margin-top: 9px;
  padding: 3px 7px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  color: #334155;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.runtime-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #22c55e;
}

.run-error .node-runtime .runtime-dot {
  background: #ef4444;
}

.run-waiting .node-runtime .runtime-dot {
  background: #f59e0b;
}

.run-running .node-runtime .runtime-dot {
  background: #6366f1;
}

.studio-node.run-success {
  border-color: rgba(34, 197, 94, 0.58);
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.18), 0 16px 38px rgba(15, 23, 42, 0.12);
}

.studio-node.run-running {
  --node-color: #6366f1;
  --node-soft: rgba(99, 102, 241, 0.08);
  --node-line: rgba(99, 102, 241, 0.22);
  border-color: rgba(99, 102, 241, 0.62);
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-node.run-error {
  --node-color: #ef4444;
  --node-soft: rgba(239, 68, 68, 0.08);
  --node-line: rgba(239, 68, 68, 0.24);
  border-color: rgba(239, 68, 68, 0.58);
  box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-node.run-waiting {
  --node-color: #f59e0b;
  --node-soft: rgba(245, 158, 11, 0.1);
  --node-line: rgba(245, 158, 11, 0.26);
  border-color: rgba(245, 158, 11, 0.62);
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-node.run-current {
  border-color: rgba(99, 102, 241, 0.76);
  box-shadow:
    0 0 0 3px rgba(99, 102, 241, 0.22),
    0 18px 42px rgba(15, 23, 42, 0.16);
  transform: translateY(-2px);
}

.start-node {
  --node-color: #22c55e;
  --node-soft: rgba(34, 197, 94, 0.1);
  --node-line: rgba(34, 197, 94, 0.24);
}

.condition-routes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.condition-route {
  display: inline-flex;
  min-height: 22px;
  align-items: center;
  padding: 0 8px;
  border: 1px solid rgba(249, 115, 22, 0.2);
  border-radius: 999px;
  background: rgba(255, 247, 237, 0.86);
  color: #c2410c;
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
}

.classifier-routes {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.classifier-route-row {
  position: relative;
  display: flex;
  min-height: 42px;
  align-items: center;
  padding: 8px 28px 8px 10px;
  border: 1px solid rgba(249, 115, 22, 0.22);
  border-radius: 10px;
  background: rgba(255, 247, 237, 0.88);
}

.classifier-route-row.is-default {
  border-style: dashed;
  background: rgba(248, 250, 252, 0.94);
}

.knowledge-node .classifier-route-row {
  border-color: rgba(13, 148, 136, 0.24);
  background: rgba(240, 253, 250, 0.9);
}

.knowledge-node .classifier-route-row.is-default {
  background: rgba(248, 250, 252, 0.94);
}

.knowledge-node .classifier-route-copy strong {
  color: #0f766e;
}

.classifier-route-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.classifier-route-copy strong {
  overflow: hidden;
  color: #9a3412;
  font-size: 12px;
  font-weight: 850;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.classifier-route-copy span {
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.end-node,
.answer-node {
  --node-color: #16a34a;
  --node-soft: rgba(22, 163, 74, 0.1);
  --node-line: rgba(22, 163, 74, 0.24);
}

.llm-node {
  --node-color: #635bff;
  --node-soft: rgba(99, 91, 255, 0.1);
  --node-line: rgba(99, 91, 255, 0.24);
}

.tool-node {
  --node-color: #d97706;
  --node-soft: rgba(217, 119, 6, 0.1);
  --node-line: rgba(217, 119, 6, 0.24);
}

.knowledge-node {
  --node-color: #0d9488;
  --node-soft: rgba(13, 148, 136, 0.1);
  --node-line: rgba(13, 148, 136, 0.24);
}

.user-input-node {
  --node-color: #10b981;
  --node-soft: rgba(16, 185, 129, 0.1);
  --node-line: rgba(16, 185, 129, 0.24);
}

.interaction-node {
  --node-color: #14b8a6;
  --node-soft: rgba(20, 184, 166, 0.1);
  --node-line: rgba(20, 184, 166, 0.24);
}

.page-action-node {
  --node-color: #8b5cf6;
  --node-soft: rgba(139, 92, 246, 0.1);
  --node-line: rgba(139, 92, 246, 0.24);
}

.condition-node {
  --node-color: #f97316;
  --node-soft: rgba(249, 115, 22, 0.11);
  --node-line: rgba(249, 115, 22, 0.26);
}

.variable-node {
  --node-color: #64748b;
  --node-soft: rgba(100, 116, 139, 0.1);
  --node-line: rgba(100, 116, 139, 0.24);
}

.template-node {
  --node-color: #a21caf;
  --node-soft: rgba(162, 28, 175, 0.1);
  --node-line: rgba(162, 28, 175, 0.24);
}

.parameter-node {
  --node-color: #db2777;
  --node-soft: rgba(219, 39, 119, 0.09);
  --node-line: rgba(219, 39, 119, 0.22);
}

.http-node,
.mcp-node {
  --node-color: #4f46e5;
  --node-soft: rgba(79, 70, 229, 0.1);
  --node-line: rgba(79, 70, 229, 0.24);
}

.code-node,
.document-extract-node {
  --node-color: #475569;
  --node-soft: rgba(71, 85, 105, 0.1);
  --node-line: rgba(71, 85, 105, 0.24);
}

.classifier-node {
  --node-color: #ea580c;
  --node-soft: rgba(234, 88, 12, 0.1);
  --node-line: rgba(234, 88, 12, 0.24);
  width: 300px;
  min-width: 300px;
  max-width: 300px;
}

.aggregate-node {
  --node-color: #0891b2;
  --node-soft: rgba(8, 145, 178, 0.1);
  --node-line: rgba(8, 145, 178, 0.24);
}

.approval-node {
  --node-color: #ca8a04;
  --node-soft: rgba(202, 138, 4, 0.1);
  --node-line: rgba(202, 138, 4, 0.24);
}

.loop-node {
  --node-color: #0284c7;
  --node-soft: rgba(2, 132, 199, 0.1);
  --node-line: rgba(2, 132, 199, 0.24);
}

.knowledge-write-node {
  --node-color: #e11d48;
  --node-soft: rgba(225, 29, 72, 0.09);
  --node-line: rgba(225, 29, 72, 0.22);
}

.studio-node.needs-config {
  border-style: dashed;
  border-color: #f59e0b;
  background: #fffbeb;
}
</style>
