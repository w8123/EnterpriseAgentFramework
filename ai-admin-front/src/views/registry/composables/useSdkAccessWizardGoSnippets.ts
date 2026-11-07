import { computed, type Ref } from 'vue'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'

/** The business host must replace both identity callbacks and the sample handler. */
export function useSdkAccessWizardGoSnippets(
  project: Ref<ScanProject | null>,
  manifest: Ref<AiOnboardingManifest | null>,
) {
  const startup = computed(() => {
    const registry = JSON.stringify(manifest.value?.sdk?.config?.registryUrl || 'https://reachai.example.com')
    const projectCode = JSON.stringify(project.value?.projectCode || 'your-project')
    const projectName = JSON.stringify(project.value?.name || '业务系统')
    const baseUrl = JSON.stringify(project.value?.baseUrl || 'https://business.example.com')
    return `package main

import (
  "context"
  "encoding/base64"
  "errors"
  "log"
  "net/http"
  "os"

  reachai "github.com/w8123/EnterpriseAgentFramework/reachai-go"
)

func main() {
  // 密钥由独立的秘密管理服务提供，不写入源码或浏览器。
  key, err := base64.StdEncoding.DecodeString(os.Getenv("REACHAI_CREDENTIAL_KEY"))
  if err != nil { log.Fatal("invalid credential key") }
  store, err := reachai.NewEncryptedFileCredentialStore(os.Getenv("REACHAI_CREDENTIAL_FILE"), key)
  if err != nil { log.Fatal("invalid credential store") }
  sdk, err := reachai.New(reachai.Options{
    RegistryURL: ${registry}, ProjectCode: ${projectCode}, ProjectName: ${projectName}, BaseURL: ${baseUrl},
    EnrollmentToken: os.Getenv("REACHAI_ENROLLMENT_TOKEN"),
    AllowedOrigins: []string{os.Getenv("REACHAI_BUSINESS_ORIGIN")},
  }, reachai.Dependencies{
    CredentialStore: store,
    PrincipalResolver: func(r *http.Request, agentID string) (*reachai.EmbedPrincipal, error) {
      return nil, errors.New("请接入已验证的业务登录态")
    },
    SecurityBridge: func(ctx context.Context, identity reachai.Invocation, next func(context.Context) (any, error)) (any, error) {
      return nil, errors.New("请接入业务身份、租户和数据权限校验")
    },
  })
  if err != nil { log.Fatal("invalid ReachAI configuration") }
  err = sdk.RegisterCapability(reachai.Capability{
    Name: "order.query", Title: "查询订单", SideEffect: "READ", BusinessIdentityRequired: true,
    InputSchema: map[string]any{"type":"object", "properties":map[string]any{"orderId":map[string]any{"type":"string"}}, "required":[]string{"orderId"}},
    OutputSchema: map[string]any{"type":"object", "properties":map[string]any{"id":map[string]any{"type":"string"}}, "required":[]string{"id"}},
    Handler: func(ctx context.Context, args map[string]any) (any, error) {
      return nil, errors.New("请调用已授权的业务服务")
    },
  })
  if err != nil { log.Fatal("invalid capability declaration") }
  // 在已有业务认证与 CSRF 中间件之后挂载路由，并在监听后调用 sdk.Start(ctx)。
  http.Handle("/reachai/", sdk.Handler())
}`
  })
  return { startup }
}
