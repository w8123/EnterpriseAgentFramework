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
import { applyPlatformLogin, loginPlatform } from '@/api/platformAuth'

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
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
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

.login-button {
  width: 100%;
  margin-top: 8px;
}
</style>
