import { computed, type Ref } from 'vue'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'

/** Backend example intentionally requires the host to implement identity and data authorization. */
export function useSdkAccessWizardNodeSnippets(project: Ref<ScanProject | null>, manifest: Ref<AiOnboardingManifest | null>) {
  const startup = computed(() => {
    const registry = JSON.stringify(manifest.value?.sdk?.config?.registryUrl || 'https://reachai.example.com')
    const projectCode = JSON.stringify(project.value?.projectCode || 'your-project')
    const projectName = JSON.stringify(project.value?.name || '业务系统')
    const baseUrl = JSON.stringify(project.value?.baseUrl || 'https://business.example.com')
    return `const express = require('express')
const { ReachAI, EncryptedFileCredentialStore, createReachRouter, currentInvocation } = require('@reachai/node-sdk')

const app = express()
// 在此之前接入业务系统自己的登录会话与 CSRF 防护。
const reach = new ReachAI({
  registryUrl: ${registry},
  projectCode: ${projectCode},
  projectName: ${projectName},
  baseUrl: ${baseUrl},
  enrollmentToken: process.env.REACHAI_ENROLLMENT_TOKEN,
  allowedOrigins: [process.env.REACHAI_BUSINESS_ORIGIN],
}, {
  credentialStore: new EncryptedFileCredentialStore(
    process.env.REACHAI_CREDENTIAL_FILE, process.env.REACHAI_CREDENTIAL_KEY),
  principalResolver: async (request, agentId) => {
    throw new Error('请从已验证的业务登录会话取得用户、租户与角色')
  },
  securityBridge: async (identity, callNext) => {
    throw new Error('请恢复业务身份并执行真实业务权限校验')
  },
})
reach.capability({
  name: 'order.query', title: '查询订单', sideEffect: 'READ', businessIdentityRequired: true,
  inputSchema: { type: 'object', properties: { orderId: { type: 'string' } }, required: ['orderId'] },
  outputSchema: { type: 'object', properties: { id: { type: 'string' }, status: { type: 'string' } }, required: ['id', 'status'] },
  handler: async ({ orderId }) => {
    const identity = currentInvocation()
    // 结合 identity 与业务库的订单所有权进行授权。
    throw new Error('请调用已授权的业务服务')
  },
})
app.use(createReachRouter(reach))
const server = app.listen(3000, () => {
  void reach.start().catch(() => console.error('ReachAI 注册失败，请检查平台与凭据配置'))
})
process.on('SIGTERM', () => { void reach.stop().finally(() => server.close()) })`
  })
  return { startup }
}
