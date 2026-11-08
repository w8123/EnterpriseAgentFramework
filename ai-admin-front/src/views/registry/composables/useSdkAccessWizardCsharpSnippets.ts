import { computed, type Ref } from 'vue'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'

export function useSdkAccessWizardCsharpSnippets(
  project: Ref<ScanProject | null>,
  manifest: Ref<AiOnboardingManifest | null>,
) {
  const application = computed(() => JSON.stringify({
    ReachAI: {
      RegistryUrl: manifest.value?.sdk?.config?.registryUrl || 'https://reachai.example.com',
      ProjectCode: project.value?.projectCode || 'your-project',
      ProjectName: project.value?.name || '业务系统',
      BaseUrl: project.value?.baseUrl || 'https://business.example.com',
      ContextPath: project.value?.contextPath || '',
      Environment: project.value?.environment || 'dev',
      CredentialFilePath: '<应用包外的持久凭据文件路径>',
    },
  }, null, 2))

  const startup = `using ReachAI.AspNetCore;

// 用实际业务类型替换示例类型，解析经过认证的员工身份与业务会话。
builder.Services.AddScoped<IReachAiEmbedPrincipalResolver, YourEmbedPrincipalResolver>();
builder.Services.AddScoped<IReachAiSecurityContextBridge, YourSecurityContextBridge>();
builder.Services.AddReachAI(builder.Configuration.GetSection("ReachAI"))
    .AddCapabilitiesFrom<YourBusinessCapabilities>();

// 在宿主的认证与授权中间件之后注册。
app.MapReachAI();`

  return { application, startup }
}
