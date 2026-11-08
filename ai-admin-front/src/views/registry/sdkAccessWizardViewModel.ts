const repositoryDocsUrl = 'https://github.com/w8123/EnterpriseAgentFramework/blob/main'
export const integrationGuideUrl = `${repositoryDocsUrl}/docs/integration.md`
export const embedGuideUrl = `${repositoryDocsUrl}/docs/guides/embed-chat.md`
export const workflowGuideUrl = `${repositoryDocsUrl}/docs/guides/first-workflow.md`
export const sdkGuideUrls = {
  java: `${repositoryDocsUrl}/reachai-spring-boot2-starter/README.md`,
  csharp: `${repositoryDocsUrl}/reachai-dotnet/README.md`,
  python: `${repositoryDocsUrl}/reachai-python/README.md`,
  node: `${repositoryDocsUrl}/reachai-node/README.md`,
  go: `${repositoryDocsUrl}/reachai-go/README.md`,
}

export type AiOnboardingStepStatus =
  | 'TODO'
  | 'RUNNING'
  | 'PASS'
  | 'WARN'
  | 'FAIL'
  | 'SKIPPED'

export function sdkAccessCheckStatusLabel(status: string): string {
  if (status === 'PASS') return '通过'
  if (status === 'WARN') return '需确认'
  if (status === 'PENDING') return '待验证'
  return '失败'
}

export function aiAccessStepStatusLabel(status: AiOnboardingStepStatus): string {
  if (status === 'PASS') return '已报告'
  if (status === 'WARN') return '需确认'
  if (status === 'FAIL') return '报告失败'
  if (status === 'RUNNING') return '进行中'
  if (status === 'SKIPPED') return '已声明跳过'
  return '待处理'
}
