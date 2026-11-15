import { computed, type Ref } from 'vue'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'

function phpString(value: string) {
  return `'${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`
}

/** Configuration is project-specific; secrets and authenticated identities stay in the host. */
export function useSdkAccessWizardPhpSnippets(
  project: Ref<ScanProject | null>,
  manifest: Ref<AiOnboardingManifest | null>,
) {
  const startup = computed(() => {
    const registry = phpString(manifest.value?.sdk?.config?.registryUrl || 'https://reachai.example.com')
    const projectCode = phpString(project.value?.projectCode || 'your-project')
    const projectName = phpString(project.value?.name || '业务系统')
    const baseUrl = phpString(project.value?.baseUrl || 'https://business.example.com')
    return `<?php
// reachai.php：入口文件和命令行共用，返回配置好的实例。
// public/index.php 里：$reach = require __DIR__ . '/../reachai.php'; if ($reach->handleGlobals()) { return; }
require __DIR__ . '/vendor/autoload.php';

use ReachAI\\ReachAI;
use ReachAI\\Options;
use ReachAI\\EncryptedFileCredentialStore;
use ReachAI\\EmbedPrincipal;
use ReachAI\\InvocationContext;
use ReachAI\\Http\\Request;

$reach = new ReachAI(Options::fromEnvironment([
    'registryUrl' => ${registry},
    'projectCode' => ${projectCode},
    'projectName' => ${projectName},
    'baseUrl' => ${baseUrl},
]), [
    // 加密凭据文件放在 Web 根目录之外，密钥由 vendor/bin/reachai generate-key 生成并放进环境变量。
    'credentialStore' => new EncryptedFileCredentialStore(
        (string) getenv('REACHAI_CREDENTIAL_FILE'), (string) getenv('REACHAI_CREDENTIAL_KEY')),
    // 用业务系统实际实现替换两个回调：只能从已登录会话取得员工；
    // 受信调用进入业务系统后仍须执行自己的角色、租户和数据权限检查。
    'principalResolver' => function (Request $request, string $agentId): ?EmbedPrincipal {
        throw new \\LogicException('请接入业务登录会话');
    },
    'securityBridge' => function (InvocationContext $identity, callable $next) {
        throw new \\LogicException('请接入业务身份与权限校验，然后 return $next();');
    },
]);

$reach->capability('order.query', function (string $orderId): array {
    throw new \\LogicException('请调用已授权的业务方法');
}, [
    'title' => '查询订单', 'sideEffect' => 'READ', 'businessIdentityRequired' => true,
    'parameters' => ['orderId' => ['type' => 'string', 'description' => '订单号']],
    'returns' => ['type' => 'object', 'properties' => [
        'orderId' => ['type' => 'string', 'required' => true],
        'status' => ['type' => 'string', 'required' => true],
    ]],
]);

return $reach;`
  })
  const lifecycle = computed(() => `# 首次注册（消费一次性 REACHAI_ENROLLMENT_TOKEN），之后只用心跳
vendor/bin/reachai register --bootstrap=reachai.php
# crontab：每分钟一次心跳
* * * * * cd /www/your-app && vendor/bin/reachai heartbeat --bootstrap=reachai.php
# Laravel: php artisan reachai heartbeat    ThinkPHP: php think reachai heartbeat`)
  return { startup, lifecycle }
}
