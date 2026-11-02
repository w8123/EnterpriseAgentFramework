<template>
  <WorkbenchPage class="my-home-page">
    <LiquidGlassCard as="section" source="wallpaper" class="my-home-panel" aria-labelledby="my-home-title">
      <el-icon class="my-home-icon" aria-hidden="true"><ChatDotRound /></el-icon>
      <h1 id="my-home-title">我的</h1>
      <p>这里是每个人都有的个人空间：在业务系统里用 AI 助手办事，把自己的 AI 工具接进来，管理 AI 记住的关于你的事。</p>

      <nav class="my-home-links" aria-label="我的">
        <RouterLink v-if="hasPlatformRole" class="my-home-link" to="/me/ai-connections" data-testid="my-home-ai-connections">
          <strong>AI 工具接入</strong>
          <small>让自己的 Claude Code、Codex 以你的身份使用 ReachAI</small>
        </RouterLink>
        <RouterLink class="my-home-link" to="/me/memories" data-testid="my-home-memories">
          <strong>我的记忆</strong>
          <small>查看和删除 AI 记住的关于你的事</small>
        </RouterLink>
      </nav>

      <h2>在业务系统里用 AI</h2>
      <ol>
        <li>在业务系统页面右下角打开 AI 助手，直接对话办业务。</li>
        <li>在 AI 助手面板里点「在我的 AI 工具中使用」，复制一行到自己的 Claude Code 或 Codex，就能在里面以本人身份办业务。</li>
        <li>面板里点「在浏览器里管理」，可以看连接、审批进度、可下载的做法（Skill）、最近办的事和我的记忆。</li>
      </ol>
      <p v-if="!hasPlatformRole" class="my-home-hint" data-testid="my-home-no-role">
        你的账号还没有管理端角色。需要开发或管理智能体时，请联系平台管理员分配「开发人员」或「管理人员」角色。
      </p>
    </LiquidGlassCard>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'
import { LiquidGlassCard } from '@/components/common/liquid-glass'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { platformSessionUser } from '@/auth/platformSession'

const hasPlatformRole = computed(() => (platformSessionUser.value?.permissions ?? []).length > 0)
</script>

<style scoped lang="scss">
.my-home-page {
  display: grid;
  place-items: center;
  min-height: 60vh;
}

.my-home-panel {
  width: min(600px, 100%);
  display: grid;
  gap: 12px;
  padding: 32px 28px;
  border-radius: var(--radius-xl);

  h1 {
    margin: 4px 0 0;
    color: var(--text-primary);
    font-size: 20px;
    line-height: 28px;
  }

  h2 {
    margin: 8px 0 0;
    color: var(--text-primary);
    font-size: 15px;
  }

  p,
  li {
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  ol {
    margin: 0;
    padding-left: 20px;
    display: grid;
    gap: 6px;
  }
}

.my-home-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.my-home-link {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  color: var(--text-primary);
  text-decoration: none;
  transition: border-color 0.16s ease, background 0.16s ease;

  strong {
    font-size: 14px;
  }

  small {
    color: var(--text-secondary);
    font-size: 12px;
  }

  &:hover {
    border-color: color-mix(in srgb, var(--brand-primary) 40%, transparent);
    background: color-mix(in srgb, var(--brand-primary) 8%, transparent);
  }
}

.my-home-hint {
  color: var(--text-tertiary, var(--text-secondary));
}

.my-home-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  color: var(--brand-primary, var(--status-info));
  font-size: 26px;
}
</style>
