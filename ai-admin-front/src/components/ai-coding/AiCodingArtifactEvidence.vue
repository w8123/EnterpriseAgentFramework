<script setup lang="ts">
import { computed } from 'vue'
import {
  aiCodingArtifactEvidence,
  type AiCodingReportedCheck,
} from '@/utils/aiCodingArtifactEvidence'

const props = defineProps<{
  applicationResult: unknown
}>()

const material = computed(() =>
  aiCodingArtifactEvidence(props.applicationResult),
)

function checkLabel(status: string) {
  return {
    PASS: '通过',
    WARN: '需确认',
    FAIL: '未通过',
    NOT_RUN: '未执行',
  }[status] || status
}

function checkTagType(status: string) {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  return 'warning'
}

function checkDetail(check: AiCodingReportedCheck) {
  return check.evidence || check.command || '未提供执行依据'
}
</script>

<template>
  <div v-if="material" class="ai-artifact-evidence">
    <p v-if="material.summary" class="ai-artifact-summary">
      {{ material.summary }}
    </p>

    <div v-if="material.files.length" class="ai-artifact-material-block">
      <strong>相关改动</strong>
      <div class="ai-artifact-file-list">
        <code v-for="file in material.files" :key="file">{{ file }}</code>
      </div>
    </div>

    <div v-if="material.checks.length" class="ai-artifact-material-block">
      <strong>测试结果</strong>
      <div class="ai-artifact-check-list">
        <span v-for="check in material.checks" :key="check.name">
          <el-tag
            effect="plain"
            size="small"
            :type="checkTagType(check.status)"
          >
            {{ checkLabel(check.status) }}
          </el-tag>
          <span>
            <b>{{ check.name }}</b>
            <small>{{ checkDetail(check) }}</small>
          </span>
        </span>
      </div>
    </div>

    <div class="ai-artifact-material-block">
      <strong>真实浏览器材料</strong>
      <div v-if="material.browser" class="ai-artifact-browser">
        <header>
          <span>
            <b>{{ material.browser.browser }}</b>
            <code>{{ material.browser.url }}</code>
          </span>
          <el-tag
            effect="plain"
            size="small"
            :type="material.browser.passed ? 'success' : 'danger'"
          >
            {{ material.browser.passed ? 'AI 报告通过' : 'AI 报告未通过' }}
          </el-tag>
        </header>
        <p>{{ material.browser.evidence }}</p>
        <ul v-if="material.browser.scenarios.length">
          <li
            v-for="scenario in material.browser.scenarios"
            :key="scenario"
          >
            {{ scenario }}
          </li>
        </ul>
        <div
          v-if="material.browser.screenshots.length"
          class="ai-artifact-screenshot-list"
        >
          <code
            v-for="screenshot in material.browser.screenshots"
            :key="screenshot"
          >
            {{ screenshot }}
          </code>
        </div>
      </div>
      <p v-else class="ai-artifact-browser-empty">
        AI 未回传结构化浏览器材料，不能据此判断页面入口或交互已完成。
      </p>
    </div>
  </div>
</template>

<style scoped>
.ai-artifact-evidence {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);
}

.ai-artifact-summary,
.ai-artifact-browser p,
.ai-artifact-browser-empty {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.78rem;
  line-height: 1.6;
}

.ai-artifact-material-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}

.ai-artifact-material-block > strong {
  color: var(--text-primary);
  font-size: 0.75rem;
}

.ai-artifact-file-list,
.ai-artifact-screenshot-list {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
}

.ai-artifact-file-list code,
.ai-artifact-screenshot-list code,
.ai-artifact-browser header code {
  max-width: 100%;
  padding: 4px 7px;
  overflow: hidden;
  border-radius: 6px;
  color: var(--text-secondary);
  background: var(--surface-solid-control);
  font-size: 0.68rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-artifact-check-list {
  display: grid;
  gap: 7px;
}

.ai-artifact-check-list > span {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 8px;
}

.ai-artifact-check-list > span > span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.ai-artifact-check-list b,
.ai-artifact-browser b {
  color: var(--text-primary);
  font-size: 0.72rem;
}

.ai-artifact-check-list small {
  color: var(--text-muted);
  font-size: 0.68rem;
  line-height: 1.45;
}

.ai-artifact-browser {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
  padding: 11px;
  border-radius: var(--radius-sm);
  background: var(--surface-solid-control);
}

.ai-artifact-browser header {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.ai-artifact-browser header > span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.ai-artifact-browser ul {
  display: grid;
  margin: 0;
  padding-left: 18px;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 0.7rem;
  line-height: 1.5;
}

.ai-artifact-browser-empty {
  padding: 10px;
  border-radius: var(--radius-sm);
  color: var(--status-warning);
  background: color-mix(in srgb, var(--status-warning) 7%, transparent);
}
</style>
