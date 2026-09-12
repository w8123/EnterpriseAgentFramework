<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'

type LifecycleStep = 'source' | 'review' | 'catalog' | 'workflow' | 'exposure'

const props = defineProps<{
  active: LifecycleStep
  projectCount?: number
  pendingCount?: number
  catalogCount?: number
}>()

const steps = computed(() => [
  {
    key: 'source' as const,
    index: '01',
    label: '业务接入',
    hint: props.projectCount == null ? 'SDK、扫描或平台配置' : `${props.projectCount} 个平台项目`,
    path: '/registry/projects',
  },
  {
    key: 'review' as const,
    index: '02',
    label: '变更评审',
    hint: props.pendingCount == null ? 'SDK 变化先决策' : `${props.pendingCount} 项待处理`,
    path: '/capability/review',
  },
  {
    key: 'catalog' as const,
    index: '03',
    label: '能力目录',
    hint: props.catalogCount == null ? '形成当前已纳管定义' : `${props.catalogCount} 项目录定义`,
    path: '/capability',
  },
  {
    key: 'workflow' as const,
    index: '04',
    label: 'Workflow 编排',
    hint: '按稳定标识引用',
    path: '/workflows',
  },
  {
    key: 'exposure' as const,
    index: '05',
    label: 'MCP 开放',
    hint: '按发布修订冻结',
    path: '/mcp-hub',
  },
])
</script>

<template>
  <section class="lifecycle-strip glass-surface-panel" aria-labelledby="capability-lifecycle-title">
    <div class="lifecycle-strip__intro">
      <span>治理链路</span>
      <strong id="capability-lifecycle-title">从业务接入到运行开放</strong>
      <small>目录不是配置终点，而是 Workflow 与开放协议共同使用的生效资产。</small>
    </div>

    <ol class="lifecycle-strip__steps">
      <li v-for="(step, index) in steps" :key="step.key">
        <RouterLink
          :to="step.path"
          :class="{ 'is-active': step.key === props.active }"
          :aria-current="step.key === props.active ? 'step' : undefined"
        >
          <span class="lifecycle-strip__index">{{ step.index }}</span>
          <span class="lifecycle-strip__copy">
            <strong>{{ step.label }}</strong>
            <small>{{ step.hint }}</small>
          </span>
        </RouterLink>
        <el-icon v-if="index < steps.length - 1" class="lifecycle-strip__arrow" aria-hidden="true">
          <ArrowRight />
        </el-icon>
      </li>
    </ol>
  </section>
</template>

<style scoped lang="scss">
.lifecycle-strip {
  display: grid;
  grid-template-columns: minmax(220px, 0.8fr) minmax(0, 2.2fr);
  align-items: center;
  gap: var(--section-gap);
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.lifecycle-strip__intro {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.lifecycle-strip__intro > span {
  color: var(--brand-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.lifecycle-strip__intro > strong {
  color: var(--text-primary);
  font-size: 16px;
}

.lifecycle-strip__intro > small,
.lifecycle-strip__copy small {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.lifecycle-strip__steps {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.lifecycle-strip__steps li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px;
  align-items: center;
  min-width: 0;
}

.lifecycle-strip__steps li:last-child {
  grid-template-columns: minmax(0, 1fr);
}

.lifecycle-strip__steps a {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-align: left;
  text-decoration: none;
  background: transparent;
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) var(--motion-easing-standard),
    background var(--motion-duration-fast) var(--motion-easing-standard);
}

.lifecycle-strip__steps a:hover,
.lifecycle-strip__steps a:focus-visible {
  border-color: var(--border-readable);
  background: var(--surface-glass-control);
  outline: none;
}

.lifecycle-strip__steps a.is-active {
  border-color: color-mix(in srgb, var(--brand-primary) 38%, var(--border-divider));
  color: var(--brand-primary);
  background: var(--surface-glass-selected);
}

.lifecycle-strip__index {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  border: 1px solid var(--border-divider);
  border-radius: 9px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
  background: var(--surface-solid-panel);
}

.is-active .lifecycle-strip__index {
  border-color: var(--brand-primary);
  color: var(--surface-solid-panel);
  background: var(--brand-primary);
}

.lifecycle-strip__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.lifecycle-strip__copy strong,
.lifecycle-strip__copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lifecycle-strip__copy strong {
  font-size: 13px;
}

.lifecycle-strip__arrow {
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 1180px) {
  .lifecycle-strip {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .lifecycle-strip__steps {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .lifecycle-strip__steps li,
  .lifecycle-strip__steps li:last-child {
    grid-template-columns: 1fr;
  }

  .lifecycle-strip__arrow {
    display: none;
  }
}
</style>
