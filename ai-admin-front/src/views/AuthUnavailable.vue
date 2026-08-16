<template>
  <main class="auth-unavailable-page">
    <section class="auth-unavailable-panel">
      <div class="brand-mark">R</div>
      <h1>暂时无法验证登录状态</h1>
      <p>
        ReachAI 平台认证服务暂时不可用。为保护项目、工作流和平台配置，系统没有打开管理控制台。
      </p>
      <p class="hint">请确认 Control 服务可用后重试；系统不会清除当前会话，除非服务明确返回未授权。</p>
      <div class="actions">
        <el-button type="primary" :loading="retrying" @click="retry">重新验证</el-button>
        <el-button :disabled="retrying" @click="startNewLogin">清除本地会话并登录</el-button>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  bootstrapPlatformSession,
  markPlatformSessionAnonymous,
  sanitizePlatformRedirect,
} from '@/auth/platformSession'

const router = useRouter()
const route = useRoute()
const retrying = ref(false)

async function retry() {
  retrying.value = true
  try {
    const state = await bootstrapPlatformSession()
    if (state === 'AUTHENTICATED') {
      await router.replace(sanitizePlatformRedirect(route.query.redirect))
      return
    }
    if (state === 'ANONYMOUS') {
      await router.replace({ path: '/login', query: { redirect: route.query.redirect } })
      return
    }
    ElMessage.warning('认证服务仍不可用，请稍后重试')
  } finally {
    retrying.value = false
  }
}

async function startNewLogin() {
  markPlatformSessionAnonymous()
  await router.replace({ path: '/login', query: { redirect: route.query.redirect } })
}
</script>

<style scoped lang="scss">
.auth-unavailable-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.16), transparent 30rem),
    linear-gradient(135deg, #0f172a 0%, #111827 52%, #172554 100%);
}

.auth-unavailable-panel {
  width: min(480px, 100%);
  padding: 36px;
  border-radius: 8px;
  color: #334155;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.35);

  h1 {
    margin: 20px 0 12px;
    color: #0f172a;
    font-size: 24px;
  }

  p {
    margin: 0;
    line-height: 1.7;
  }
}

.brand-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  font-weight: 800;
  background: #2563eb;
}

.hint {
  margin-top: 12px !important;
  color: #64748b;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 28px;
}
</style>
