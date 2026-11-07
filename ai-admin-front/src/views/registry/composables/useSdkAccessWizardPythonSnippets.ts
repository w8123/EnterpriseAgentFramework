import { computed, type Ref } from 'vue'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'

/** Configuration is project-specific; secrets and authenticated identities stay in the host. */
export function useSdkAccessWizardPythonSnippets(
  project: Ref<ScanProject | null>,
  manifest: Ref<AiOnboardingManifest | null>,
) {
  const startup = computed(() => {
    const registry = JSON.stringify(manifest.value?.sdk?.config?.registryUrl || 'https://reachai.example.com')
    const projectCode = JSON.stringify(project.value?.projectCode || 'your-project')
    const projectName = JSON.stringify(project.value?.name || '业务系统')
    const baseUrl = JSON.stringify(project.value?.baseUrl || 'https://business.example.com')
    return `import os
from fastapi import FastAPI
from pydantic import BaseModel
from reachai_fastapi import ReachAI, ReachAIOptions, EncryptedFileCredentialStore

app = FastAPI()
options = ReachAIOptions(
    registry_url=${registry},
    project_code=${projectCode},
    project_name=${projectName},
    base_url=${baseUrl},
    enrollment_token=os.environ.get("REACHAI_ENROLLMENT_TOKEN"),
    allowed_origins=[os.environ["REACHAI_BUSINESS_ORIGIN"]],
)
store = EncryptedFileCredentialStore(
    os.environ["REACHAI_CREDENTIAL_FILE"], os.environ["REACHAI_CREDENTIAL_KEY"]
)

# 用业务系统实际实现替换两个回调：只能从已验证会话取得身份；
# 受信调用进入业务系统后仍须执行自己的角色、租户和数据权限检查。
async def resolve_business_principal(request, agent_id):
    raise NotImplementedError("请接入业务登录会话")

async def apply_business_identity(context, call_next):
    raise NotImplementedError("请接入业务身份与权限校验")

reach = ReachAI(options, credential_store=store,
                principal_resolver=resolve_business_principal,
                security_bridge=apply_business_identity)

class Order(BaseModel):
    order_id: str
    status: str

@reach.capability(name="order.query", side_effect="READ", business_identity_required=True)
async def query_order(order_id: str) -> Order:
    raise NotImplementedError("请调用已授权的业务方法")

reach.mount(app)`
  })
  return { startup }
}
