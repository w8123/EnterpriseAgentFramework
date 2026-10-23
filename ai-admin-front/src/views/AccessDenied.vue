<template>
  <main class="access-denied-page">
    <LiquidGlassCard as="section" source="wallpaper" class="access-denied-panel" role="alert">
      <el-icon class="access-denied-icon" aria-hidden="true"><WarningFilled /></el-icon>
      <h1>没有访问权限</h1>
      <p>你当前的角色不能访问这个页面。如需开通，请联系平台管理员分配开发人员或运营人员角色。</p>
      <LiquidGlassButton type="primary" @click="goToWorkspace">返回概览</LiquidGlassButton>
    </LiquidGlassCard>
  </main>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { WarningFilled } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassCard } from '@/components/common/liquid-glass'

const router = useRouter()

function goToWorkspace() {
  router.push('/dashboard')
}
</script>

<style scoped lang="scss">
.access-denied-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px 16px;
  box-sizing: border-box;
  background: var(--surface-page-background);
  background-size: cover;
  background-position: var(--theme-wallpaper-position, center);
}

.access-denied-panel {
  width: min(440px, 100%);
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 32px 28px;
  border-radius: var(--radius-xl);
  text-align: center;

  h1 {
    margin: 4px 0 0;
    color: var(--text-primary);
    font-size: 20px;
    line-height: 28px;
  }

  p {
    margin: 0 0 8px;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.access-denied-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  color: var(--status-warning);
  font-size: 26px;
}
</style>
