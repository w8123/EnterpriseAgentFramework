<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="brand">
        <div class="brand-mark">R</div>
        <div>
          <h1>ReachAI</h1>
          <p>Platform Console</p>
        </div>
      </div>

      <div class="development-login-hint">
        本地开发默认账号：<code>admin</code> / <code>admin123</code>。生产部署必须关闭 LOCAL 登录或替换默认凭据。
      </div>

      <el-form class="login-form" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="Username">
          <el-input v-model="form.username" autocomplete="username" size="large" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
            size="large"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button class="login-button" type="primary" size="large" :loading="loading" @click="handleLogin">
          Sign in
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { applyPlatformLogin, loginPlatform } from '@/api/platformAuth'
import { sanitizePlatformRedirect } from '@/auth/platformSession'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: 'admin123',
})

async function handleLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('Please enter username and password')
    return
  }
  loading.value = true
  try {
    const { data } = await loginPlatform({ username: form.username, password: form.password })
    applyPlatformLogin(data)
    const redirect = sanitizePlatformRedirect(route.query.redirect)
    await router.replace(redirect)
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 503) {
      ElMessage.error('平台登录尚未配置或暂不可用，请联系管理员。')
      return
    }
    ElMessage.error('用户名或密码错误，请重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.16), transparent 30rem),
    linear-gradient(135deg, #0f172a 0%, #111827 52%, #172554 100%);
  padding: 24px;
}

.login-panel {
  width: min(420px, 100%);
  padding: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.35);
}

.brand {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 28px;

  h1 {
    margin: 0;
    font-size: 28px;
    line-height: 1.1;
    color: #0f172a;
  }

  p {
    margin: 4px 0 0;
    color: #64748b;
  }
}

.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  background: #2563eb;
}

.login-form {
  :deep(.el-form-item__label) {
    color: #334155;
    font-weight: 600;
  }
}

.development-login-hint {
  margin: -10px 0 22px;
  padding: 10px 12px;
  border: 1px solid #dbeafe;
  border-radius: 6px;
  color: #475569;
  background: #eff6ff;
  font-size: 13px;
  line-height: 1.55;

  code {
    color: #1d4ed8;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  }
}

.login-button {
  width: 100%;
  margin-top: 8px;
}
</style>
