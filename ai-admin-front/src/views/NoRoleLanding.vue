<template>
  <WorkbenchPage class="no-role-page">
    <LiquidGlassCard as="section" source="wallpaper" class="no-role-panel" aria-labelledby="no-role-title">
      <el-icon class="no-role-icon" aria-hidden="true"><ChatDotRound /></el-icon>
      <h1 id="no-role-title">你的账号还没有管理端角色</h1>
      <p>ReachAI 管理端给开发人员、运营人员和管理人员使用。如果你是业务人员，请直接在业务系统里使用 AI 助手：</p>
      <ol>
        <li>在业务系统页面右下角打开 AI 助手，直接对话办业务。</li>
        <li>在 AI 助手面板里点「在我的 AI 工具中使用」，复制一行到自己的 Claude Code 或 Codex，就能在里面以本人身份办业务。</li>
      </ol>
      <p class="no-role-hint">需要管理端权限时，请联系平台管理员分配角色。</p>
    </LiquidGlassCard>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { ChatDotRound } from '@element-plus/icons-vue'
import { LiquidGlassCard } from '@/components/common/liquid-glass'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
</script>

<style scoped lang="scss">
.no-role-page {
  display: grid;
  place-items: center;
  min-height: 60vh;
}

.no-role-panel {
  width: min(560px, 100%);
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

.no-role-hint {
  color: var(--text-tertiary, var(--text-secondary));
}

.no-role-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  color: var(--brand-primary, var(--status-info));
  font-size: 26px;
}
</style>
